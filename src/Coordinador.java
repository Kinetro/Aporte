import java.io.*;
import java.net.*;
import org.ietf.jgss.Oid;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

public class Coodinador{

    static int ultimo = 0, cupo;
    static String token;
    static Queue<Integer> cola = new ArrayDeque<>();

    static synchronized  void registar (String evento, String detalle) {
       
        try{
            Files.createDirectories(Path.of("logs")); 
            Files.writeString(Path.of("logs/Coodinador.log"), "0;0;" + evento + ";" + detalle + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);            

        } catch(IOException e) {

            System.err.println("Error de registro: " + e.getMessage());
        }
    }

    static synchronized String procesar(String mensaje){
        
    }

}