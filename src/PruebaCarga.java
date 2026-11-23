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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PruebaCarga {

    static String token;
    static String host;
    static int puerto;
    static int cupo;

    public static void main(String[] args) throws Exception {
        int solicitudes = 30;
        if (args.length > 0) {
            solicitudes = Integer.parseInt(args[0].trim());
        }

        cargarConfiguracion();

        System.out.println("PruebaCarga - " + solicitudes + " solicitudes TURNO simultaneas");
        System.out.println("Coordinador: " + host + ":" + puerto + "   cupo=" + cupo);
        System.out.println();

        List<Integer> asignados = Collections.synchronizedList(new ArrayList<>());
        Map<String, Integer> motivos = new ConcurrentHashMap<>();
        AtomicInteger rechazadas = new AtomicInteger();
        AtomicInteger errores = new AtomicInteger();

        CyclicBarrier barrera = new CyclicBarrier(solicitudes);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tareas = new ArrayList<>();

            for (int i = 0; i < solicitudes; i++) {
                final int ventanilla = (i % 3) + 1;
                tareas.add(pool.submit(() -> {
                    try {
                        barrera.await();
                    } catch (Exception e) {
                        errores.incrementAndGet();
                        return;
                    }

                    try {
                        String respuesta = solicitarTurno(ventanilla);
                        if (respuesta.startsWith("ASIGNADO")) {
                            asignados.add((int) valor(respuesta, "numero"));
                        } else if (respuesta.startsWith("RECHAZADA")) {
                            rechazadas.incrementAndGet();
                            motivos.merge(campo(respuesta, "motivo"), 1, Integer::sum);
                        } else {
                            errores.incrementAndGet();
                        }
                    } catch (IOException e) {
                        errores.incrementAndGet();
                    }
                }));
            }

            for (Future<?> tarea : tareas) {
                tarea.get(60, TimeUnit.SECONDS);
            }
        }

        Set<Integer> distintos = new TreeSet<>(asignados);
        long emitidos = consultarEmitidos();

        System.out.println("Solicitudes lanzadas ................ " + solicitudes);
        System.out.println("Asignadas ........................... " + asignados.size());
        System.out.println("Rechazadas .......................... " + rechazadas.get());
        for (Map.Entry<String, Integer> motivo : motivos.entrySet()) {
            System.out.println("    " + motivo.getKey() + ": " + motivo.getValue());
        }
        System.out.println("Numeros distintos entregados ........ " + distintos.size()
                + " -> " + distintos);
        System.out.println("Numeros duplicados .................. "
                + (asignados.size() - distintos.size()));
        System.out.println("Emitidos segun el coordinador ....... " + emitidos);
        System.out.println("Errores de comunicacion ............. " + errores.get());

        if (asignados.size() == cupo && distintos.size() == cupo) {
            System.out.println();
            System.out.println("Resultado: se asignaron exactamente "
                    + cupo + " numeros distintos, del 1 al " + cupo + ".");
        }
    }

    static String solicitarTurno(int ventanilla) throws IOException {
        String mensaje = "TURNO;" + token + ";" + ventanilla + ";1";
        return enviar(mensaje);
    }

    static long consultarEmitidos() {
        String mensaje = "ESTADO;" + token + ";1;1";
        try {
            String respuesta = enviar(mensaje);
            if (respuesta.startsWith("ESTADO")) {
                return valor(respuesta, "emitidos");
            }
        } catch (IOException e) {
            System.out.println("No se pudo consultar el estado: " + e.getMessage());
        }
        return -1;
    }

    static String enviar(String mensaje) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, puerto), 2_000);
            socket.setSoTimeout(5_000);

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
                return linea;
            }
        }
    }

    static long valor(String respuesta, String clave) {
        return Long.parseLong(campo(respuesta, clave));
    }

    static String campo(String respuesta, String clave) {
        String prefijo = clave + "=";
        String[] campos = respuesta.split(";", -1);
        for (int i = 1; i < campos.length; i++) {
            if (campos[i].startsWith(prefijo)) {
                return campos[i].substring(prefijo.length());
            }
        }
        return "?";
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
}
