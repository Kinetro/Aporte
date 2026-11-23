import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public class Ventanilla {

    static final int ESPERA_CONEXION_MS = 1_500;
    static final int ESPERA_RESPUESTA_MS = 3_000;
    static final long INTERVALO_LATIDO_MS = 2_000;

    static int id;
    static int cupo;
    static String token;
    static String host;
    static int puerto;

    static final Object bloqueoReloj = new Object();
    static final Object bloqueoLog = new Object();
    static long relojLamport = 0;

    static volatile boolean coordinadorDisponible = true;

    record Respuesta(String linea, long lamport) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: java -cp out Ventanilla <1|2|3>");
            return;
        }
        try {
            id = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException e) {
            System.out.println("Identificador invalido: " + args[0]);
            return;
        }
        if (id < 1 || id > 3) {
            System.out.println("El identificador debe ser 1, 2 o 3.");
            return;
        }

        cargarConfiguracion();
        Files.createDirectories(Path.of("logs"));
        registrar(tick(), "VENTANILLA_INICIADA",
                "id=" + id + ",coordinador=" + host + ":" + puerto);

        iniciarLatidos();
        imprimirBanner();
        bucleConsola();
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

    static void imprimirBanner() {
        System.out.println();
        System.out.println("TurnoJusto - ventanilla V" + id);
        System.out.println("Coordinador: " + host + ":" + puerto + "   cupo=" + cupo);
        System.out.println("Comandos: turno | llamar | estado | salir");
        System.out.println();
    }

    static void bucleConsola() throws IOException {
        BufferedReader consola = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String linea;
        while ((linea = consola.readLine()) != null) {
            String comando = linea.trim().toLowerCase();
            switch (comando) {
                case "" -> {
                }
                case "turno", "t" -> ejecutar("TURNO");
                case "llamar", "l" -> ejecutar("LLAMAR");
                case "estado", "e" -> ejecutar("ESTADO");
                case "ayuda", "h", "?" -> imprimirBanner();
                case "salir", "s", "q" -> {
                    registrar(tick(), "VENTANILLA_DETENIDA", "id=" + id);
                    System.out.println("Ventanilla V" + id + " detenida.");
                    return;
                }
                default -> System.out.println("Comando no reconocido. Use: turno | llamar | estado | salir");
            }
        }
        registrar(tick(), "VENTANILLA_DETENIDA", "id=" + id + ",motivo=fin_de_entrada");
    }

    static void ejecutar(String comando) {
        Respuesta respuesta = enviar(comando, true);
        if (respuesta != null) {
            mostrar(respuesta);
        }
    }

    static Respuesta enviar(String comando, boolean interactivo) {
        long marca = tick();
        String mensaje = comando + ";" + token + ";" + id + ";" + marca;
        String evento = "LATIDO".equals(comando) ? "LATIDO_ENVIADO" : "SOLICITUD_ENVIADA";
        registrar(marca, evento, "comando=" + comando);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, puerto), ESPERA_CONEXION_MS);
            socket.setSoTimeout(ESPERA_RESPUESTA_MS);

            try (BufferedWriter salida = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader entrada = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                salida.write(mensaje);
                salida.newLine();
                salida.flush();

                String linea = entrada.readLine();
                if (linea == null) {
                    throw new IOException("el coordinador cerro la conexion");
                }

                long lamportServidor = extraerLamport(linea);
                long marcaRecepcion = lamportServidor >= 0 ? recibir(lamportServidor) : tick();

                if (!"LATIDO".equals(comando)) {
                    registrar(marcaRecepcion, "RESPUESTA_RECIBIDA", linea);
                }
                return new Respuesta(linea, marcaRecepcion);
            }

        } catch (IOException e) {
            long l = tick();
            if (interactivo) {
                registrar(l, "ERROR_CONEXION",
                        "comando=" + comando + ",detalle=" + e.getMessage());
                System.out.println("No se pudo contactar al coordinador en "
                        + host + ":" + puerto + ".");
                System.out.println("Detalle: " + e.getMessage());
                System.out.println("La ventanilla sigue activa; reintente cuando el coordinador vuelva.");
            }
            return null;
        }
    }

    static void mostrar(Respuesta respuesta) {
        String[] campos = respuesta.linea().split(";", -1);
        String resultado = campos[0];

        switch (resultado) {
            case "ASIGNADO" -> System.out.println("Turno asignado: " + campo(campos, "numero")
                    + "   (emitidos " + campo(campos, "emitidos")
                    + "/" + campo(campos, "cupo") + ")");
            case "LLAMADO" -> System.out.println("Atender turno: " + campo(campos, "numero")
                    + "   (pendientes: " + campo(campos, "pendientes") + ")");
            case "ESTADO" -> mostrarEstado(campos);
            case "RECHAZADA" -> System.out.println("Solicitud rechazada: "
                    + campo(campos, "motivo"));
            default -> System.out.println("Respuesta del coordinador: " + respuesta.linea());
        }
    }

    static void mostrarEstado(String[] campos) {
        System.out.println("Estado del coordinador (Lamport "
                + campo(campos, "lamport") + "):");
        System.out.println("  Turnos emitidos : " + campo(campos, "emitidos"));
        System.out.println("  En espera       : " + campo(campos, "pendientes"));
        System.out.println("  Cupo            : " + campo(campos, "cupo"));
        System.out.println("  V1: " + campo(campos, "V1")
                + "   V2: " + campo(campos, "V2")
                + "   V3: " + campo(campos, "V3"));
    }

    static String campo(String[] campos, String clave) {
        String prefijo = clave + "=";
        for (int i = 1; i < campos.length; i++) {
            if (campos[i].startsWith(prefijo)) {
                return campos[i].substring(prefijo.length());
            }
        }
        return "?";
    }

    static long extraerLamport(String linea) {
        String[] campos = linea.split(";", -1);
        try {
            return Long.parseLong(campo(campos, "lamport"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static long tick() {
        synchronized (bloqueoReloj) {
            return ++relojLamport;
        }
    }

    static long recibir(long lamportRemoto) {
        synchronized (bloqueoReloj) {
            relojLamport = Math.max(relojLamport, lamportRemoto) + 1;
            return relojLamport;
        }
    }

    static void registrar(long lamport, String evento, String detalle) {
        String limpio = detalle == null ? ""
                : detalle.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
        String linea = lamport + ";" + id + ";" + evento + ";" + limpio
                + System.lineSeparator();
        synchronized (bloqueoLog) {
            try {
                Files.writeString(Path.of("logs", "v" + id + ".log"), linea,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Error de registro: " + e.getMessage());
            }
        }
    }

    static void iniciarLatidos() {
        Thread hilo = new Thread(() -> {
            while (true) {
                Respuesta respuesta = enviar("LATIDO", false);
                boolean disponible = respuesta != null;

                if (disponible != coordinadorDisponible) {
                    coordinadorDisponible = disponible;
                    long l = tick();
                    if (disponible) {
                        registrar(l, "COORDINADOR_DISPONIBLE",
                                "host=" + host + ",puerto=" + puerto);
                        System.out.println("[latido] Coordinador disponible de nuevo.");
                    } else {
                        registrar(l, "COORDINADOR_NO_DISPONIBLE",
                                "host=" + host + ",puerto=" + puerto);
                        System.out.println("[latido] Coordinador NO disponible.");
                    }
                }
                dormir(INTERVALO_LATIDO_MS);
            }
        }, "latido-V" + id);
        hilo.setDaemon(true);
        hilo.start();
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
