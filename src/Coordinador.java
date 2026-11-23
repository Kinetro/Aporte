import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Coordinador {

    static final int ID_PROCESO = 0;
    static final int TOTAL_VENTANILLAS = 3;
    static final long LATIDO_MS = 2_000;
    static final long CAIDA_MS = 6_000;
    static final long PAUSA_INSEGURA_MS = 50;

    static int cupo;
    static String token;
    static String host;
    static int puerto;
    static boolean modoInseguro;

    static final Object bloqueoTurno = new Object();
    static final Object bloqueoLatidos = new Object();
    static final Object bloqueoLog = new Object();

    static final ConcurrentLinkedQueue<Integer> cola = new ConcurrentLinkedQueue<>();

    static final Map<Integer, Long> ultimoLatido = new ConcurrentHashMap<>();
    static final Map<Integer, Boolean> activa = new ConcurrentHashMap<>();
    static final Map<Integer, Boolean> conocida = new ConcurrentHashMap<>();

    static int ultimo = 0;
    static long relojLamport = 0;

    public static void main(String[] args) throws Exception {
        modoInseguro = Arrays.asList(args).contains("--inseguro");
        cargarConfiguracion();
        Files.createDirectories(Path.of("logs"));
        iniciarEstadoVentanillas();

        registrar(tick(), "COORDINADOR_INICIADO",
                "modo=" + (modoInseguro ? "INSEGURO" : "NORMAL")
                        + ",cupo=" + cupo
                        + ",host=" + host
                        + ",puerto=" + puerto);

        Thread monitor = new Thread(Coordinador::vigilarLatidos, "monitor-latidos");
        monitor.setDaemon(true);
        monitor.start();

        System.out.println("TurnoJusto - coordinador (id 0)");
        System.out.println("Escuchando en " + host + ":" + puerto
                + (modoInseguro ? "  [MODO INSEGURO]" : "  [MODO NORMAL]"));
        System.out.println("Cupo de la jornada: " + cupo + " turnos");
        System.out.println("Bitacora: logs/coordinador.log");

        try (ServerSocket servidor = new ServerSocket()) {
            servidor.setReuseAddress(true);
            servidor.bind(new InetSocketAddress(host, puerto));

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                while (true) {
                    Socket cliente = servidor.accept();
                    pool.submit(() -> atender(cliente));
                }
            }
        }
    }

    static void cargarConfiguracion() throws IOException {
        Properties config = new Properties();
        Path ruta = Path.of("config.properties");
        if (!Files.exists(ruta)) {
            ruta = Path.of("src", "config.properties");
        }
        try (InputStream entrada = Files.newInputStream(ruta)) {
            config.load(entrada);
        }
        cupo = Integer.parseInt(config.getProperty("cupo", "10").trim());
        token = config.getProperty("token", "").trim();
        host = config.getProperty("host", "127.0.0.1").trim();
        puerto = Integer.parseInt(config.getProperty("puerto", "5000").trim());
    }

    static void iniciarEstadoVentanillas() {
        for (int v = 1; v <= TOTAL_VENTANILLAS; v++) {
            activa.put(v, false);
            conocida.put(v, false);
            ultimoLatido.put(v, -1L);
        }
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static synchronized long tick() {
        return ++relojLamport;
    }

    static synchronized long recibir(long lamportRemoto) {
        relojLamport = Math.max(relojLamport, lamportRemoto) + 1;
        return relojLamport;
    }

    static void registrar(long lamport, String evento, String detalle) {
        String limpio = detalle == null ? ""
                : detalle.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
        String linea = lamport + ";" + ID_PROCESO + ";" + evento + ";" + limpio
                + System.lineSeparator();
        synchronized (bloqueoLog) {
            try {
                Files.writeString(Path.of("logs", "coordinador.log"), linea,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Error de registro: " + e.getMessage());
            }
        }
    }

    static void atender(Socket cliente) {
        try (cliente;
             BufferedReader entrada = new BufferedReader(
                     new InputStreamReader(cliente.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter salida = new BufferedWriter(
                     new OutputStreamWriter(cliente.getOutputStream(), StandardCharsets.UTF_8))) {

            cliente.setSoTimeout(5_000);
            String linea = entrada.readLine();
            if (linea == null) {
                return;
            }
            String respuesta = procesar(linea);
            salida.write(respuesta);
            salida.newLine();
            salida.flush();

        } catch (IOException e) {
            System.err.println("Error atendiendo conexion: " + e.getMessage());
        }
    }

    static String procesar(String mensaje) {
        String[] partes = mensaje.split(";", -1);
        if (partes.length != 4) {
            long l = tick();
            registrar(l, "FORMATO_INVALIDO", "mensaje=" + mensaje);
            return "RECHAZADA;motivo=FORMATO_INVALIDO;lamport=" + l;
        }

        String comando = partes[0].trim().toUpperCase();
        String tokenRecibido = partes[1].trim();
        String textoVentanilla = partes[2].trim();

        long lamportRemoto;
        try {
            lamportRemoto = Long.parseLong(partes[3].trim());
        } catch (NumberFormatException e) {
            long l = tick();
            registrar(l, "FORMATO_INVALIDO", "lamport=" + partes[3]);
            return "RECHAZADA;motivo=FORMATO_INVALIDO;lamport=" + l;
        }

        long marca = recibir(lamportRemoto);

        int ventanilla;
        try {
            ventanilla = Integer.parseInt(textoVentanilla);
        } catch (NumberFormatException e) {
            registrar(marca, "FORMATO_INVALIDO", "ventanilla=" + textoVentanilla);
            return "RECHAZADA;motivo=FORMATO_INVALIDO;lamport=" + marca;
        }
        if (ventanilla < 1 || ventanilla > TOTAL_VENTANILLAS) {
            registrar(marca, "FORMATO_INVALIDO", "ventanilla=" + ventanilla);
            return "RECHAZADA;motivo=FORMATO_INVALIDO;lamport=" + marca;
        }

        if (!token.equals(tokenRecibido)) {
            registrar(marca, "TOKEN_INVALIDO",
                    "ventanilla=V" + ventanilla + ",comando=" + comando);
            System.out.println("[rechazo] token invalido de V" + ventanilla
                    + " para " + comando);
            return "RECHAZADA;motivo=TOKEN_INVALIDO;lamport=" + marca;
        }

        registrar(marca, "SOLICITUD_RECIBIDA",
                "comando=" + comando + ",ventanilla=V" + ventanilla);

        return switch (comando) {
            case "TURNO" -> atenderTurno(ventanilla);
            case "LLAMAR" -> atenderLlamar(ventanilla);
            case "ESTADO" -> atenderEstado(ventanilla);
            case "LATIDO" -> atenderLatido(ventanilla);
            default -> {
                long l = tick();
                registrar(l, "COMANDO_INVALIDO", "comando=" + comando);
                yield "RECHAZADA;motivo=COMANDO_INVALIDO;lamport=" + l;
            }
        };
    }

    static String atenderTurno(int ventanilla) {
        Integer numero = modoInseguro ? asignarTurnoInseguro() : asignarTurnoSeguro();
        int emitidos = leerUltimo();
        long l = tick();

        if (numero == null) {
            registrar(l, "CUPO_AGOTADO",
                    "ventanilla=V" + ventanilla + ",emitidos=" + emitidos + ",cupo=" + cupo);
            System.out.println("[rechazo] cupo agotado (" + emitidos + "/" + cupo + ")");
            return "RECHAZADA;motivo=CUPO_AGOTADO;emitidos=" + emitidos
                    + ";cupo=" + cupo + ";lamport=" + l;
        }

        registrar(l, "TURNO_ASIGNADO",
                "numero=" + numero + ",ventanilla=V" + ventanilla + ",emitidos=" + emitidos);
        return "ASIGNADO;numero=" + numero + ";emitidos=" + emitidos
                + ";cupo=" + cupo + ";lamport=" + l;
    }

    static Integer asignarTurnoSeguro() {
        synchronized (bloqueoTurno) {
            if (ultimo >= cupo) {
                return null;
            }
            int numero = ++ultimo;
            cola.add(numero);
            return numero;
        }
    }

    static Integer asignarTurnoInseguro() {
        int base = ultimo;
        dormir(PAUSA_INSEGURA_MS);
        if (base >= cupo) {
            return null;
        }
        int numero = base + 1;
        ultimo = numero;
        cola.add(numero);
        return numero;
    }

    static String atenderLlamar(int ventanilla) {
        Integer numero = cola.poll();
        long l = tick();
        int pendientes = cola.size();

        if (numero == null) {
            registrar(l, "SIN_TURNOS", "ventanilla=V" + ventanilla);
            return "RECHAZADA;motivo=SIN_TURNOS;lamport=" + l;
        }

        registrar(l, "TURNO_LLAMADO",
                "numero=" + numero + ",ventanilla=V" + ventanilla + ",pendientes=" + pendientes);
        return "LLAMADO;numero=" + numero + ";pendientes=" + pendientes + ";lamport=" + l;
    }

    static String atenderEstado(int ventanilla) {
        int emitidos = leerUltimo();
        int pendientes = cola.size();
        long l = tick();

        StringBuilder respuesta = new StringBuilder("ESTADO")
                .append(";emitidos=").append(emitidos)
                .append(";pendientes=").append(pendientes)
                .append(";cupo=").append(cupo);
        for (int v = 1; v <= TOTAL_VENTANILLAS; v++) {
            respuesta.append(";V").append(v).append('=').append(estadoVentanilla(v));
        }
        respuesta.append(";lamport=").append(l);

        registrar(l, "ESTADO_CONSULTADO",
                "ventanilla=V" + ventanilla + ",emitidos=" + emitidos + ",pendientes=" + pendientes);
        return respuesta.toString();
    }

    static String atenderLatido(int ventanilla) {
        boolean primeraVez;
        boolean reactivada;

        synchronized (bloqueoLatidos) {
            boolean eraActiva = Boolean.TRUE.equals(activa.get(ventanilla));
            boolean yaConocida = Boolean.TRUE.equals(conocida.get(ventanilla));
            ultimoLatido.put(ventanilla, System.currentTimeMillis());
            activa.put(ventanilla, true);
            conocida.put(ventanilla, true);
            primeraVez = !yaConocida;
            reactivada = !eraActiva && yaConocida;
        }

        long l = tick();
        if (primeraVez) {
            registrar(l, "VENTANILLA_ACTIVA",
                    "ventanilla=V" + ventanilla + ",primeraConexion=true");
            System.out.println("[latido] V" + ventanilla + " ACTIVA");
        } else if (reactivada) {
            registrar(l, "VENTANILLA_ACTIVA",
                    "ventanilla=V" + ventanilla + ",reactivada=true");
            System.out.println("[latido] V" + ventanilla + " ACTIVA (reactivada)");
        }
        registrar(l, "LATIDO_RECIBIDO", "ventanilla=V" + ventanilla);
        return "LATIDO_OK;lamport=" + l;
    }

    static void vigilarLatidos() {
        while (true) {
            dormir(500);
            long ahora = System.currentTimeMillis();

            for (int v = 1; v <= TOTAL_VENTANILLAS; v++) {
                boolean caida = false;
                long silencio = 0;

                synchronized (bloqueoLatidos) {
                    if (Boolean.TRUE.equals(activa.get(v))) {
                        long ultimoVisto = ultimoLatido.getOrDefault(v, -1L);
                        if (ultimoVisto > 0 && ahora - ultimoVisto > CAIDA_MS) {
                            activa.put(v, false);
                            caida = true;
                            silencio = ahora - ultimoVisto;
                        }
                    }
                }

                if (caida) {
                    long l = tick();
                    registrar(l, "VENTANILLA_CAIDA",
                            "ventanilla=V" + v + ",silencioMs=" + silencio
                                    + ",umbralMs=" + CAIDA_MS);
                    System.out.println("[latido] V" + v + " CAIDA (sin latido "
                            + silencio + " ms)");
                }
            }
        }
    }

    static int leerUltimo() {
        synchronized (bloqueoTurno) {
            return ultimo;
        }
    }

    static String estadoVentanilla(int ventanilla) {
        return Boolean.TRUE.equals(activa.get(ventanilla)) ? "ACTIVA" : "CAIDA";
    }
}
