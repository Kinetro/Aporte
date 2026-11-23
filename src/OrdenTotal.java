import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class OrdenTotal {

    record Evento(long lamport, int proceso, String linea) {
    }

    public static void main(String[] args) throws IOException {
        List<String> archivos = List.of("coordinador.log", "v1.log", "v2.log", "v3.log");
        List<Evento> eventos = new ArrayList<>();

        for (String archivo : archivos) {
            Path ruta = Path.of("logs", archivo);
            if (!Files.exists(ruta)) {
                System.out.println("Aviso: no existe " + ruta);
                continue;
            }

            try (Stream<String> lineas = Files.lines(ruta, StandardCharsets.UTF_8)) {
                lineas.forEach(linea -> {
                    Evento evento = parsear(linea);
                    if (evento != null) {
                        eventos.add(evento);
                    }
                });
            }
        }

        if (eventos.isEmpty()) {
            System.out.println("No hay registros para ordenar en logs/");
            return;
        }

        eventos.sort(Comparator.comparingLong(Evento::lamport)
                .thenComparingInt(Evento::proceso));

        Path salida = Path.of("logs", "orden_total.log");
        StringBuilder contenido = new StringBuilder();
        for (Evento evento : eventos) {
            contenido.append(evento.linea()).append(System.lineSeparator());
        }
        Files.writeString(salida, contenido.toString(), StandardCharsets.UTF_8);

        System.out.println("Eventos leidos: " + eventos.size());
        System.out.println("Archivo generado: logs/orden_total.log");
        System.out.println();
        System.out.println("Primeros eventos en orden total:");
        eventos.stream()
                .limit(10)
                .forEach(e -> System.out.println("  " + e.linea()));
    }

    static Evento parsear(String linea) {
        if (linea == null || linea.isBlank()) {
            return null;
        }
        String[] partes = linea.split(";", 4);
        if (partes.length < 4) {
            return null;
        }
        try {
            long lamport = Long.parseLong(partes[0].trim());
            int proceso = Integer.parseInt(partes[1].trim());
            return new Evento(lamport, proceso, linea);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
