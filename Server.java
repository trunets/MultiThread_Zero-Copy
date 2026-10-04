import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class Server {
    static final int PORT = 5000;
    static final int POOL_SIZE = 20; // must be >= 10 workers x number of clients you test with
    static final Path FILES_DIR = Paths.get("files");

    public static void main(String[] args) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        try (ServerSocket ss = new ServerSocket(PORT)) {
            System.out.println("Server listening on port " + PORT);
            while (true) {
                Socket s = ss.accept();
                pool.submit(() -> handle(s));
            }
        }
    }

    static void handle(Socket s) {
        try (s;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                OutputStream out = s.getOutputStream()) {

            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.trim().split(" ");
                System.out.println("Client: " + line);

                switch (p[0]) {
                    case "LIST" -> send(out, "TODO LIST\n");
                    case "INFO" -> send(out, "TODO INFO\n");
                    case "GET" -> send(out, "TODO GET\n");
                    default -> send(out, "ERROR 400 Unknown command\n");
                }
            }
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    static void send(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes("UTF-8"));
        out.flush();
    }
}
