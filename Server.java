import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.nio.channels.*;
import java.util.stream.*;
/*
 * Server Requirements:
 * - Listen for TCP connections.
 * - Support multiple clients concurrently.
 * - Handle LIST, INFO, GET, and ERROR commands.
 * - Validate filename, offset, and length.
 * - Send only the requested file range.
 * - Support Traditional I/O and NIO/native transfer.
 * - Handle resources and invalid requests safely.
 * - Support performance testing with 1 and 10 workers.
 */

public class Server {
    static final int PORT = 5000;
    static final int POOL_SIZE = 20; // must be >= 10 workers x number of clients you test with
    static final Path FILES_DIR = Paths.get("files");

    public static void main(String[] args) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(PORT));
            System.out.println("Server listening on port " + PORT);
            while (true) {
                Socket s = ssc.accept().socket();
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
                    case "LIST" -> list(out);
                    case "INFO" -> info(out, p);
                    case "GET" -> get(s, out, p);
                    default -> send(out, "ERROR 400 Unknown command\n");
                }
            }
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    //PROTOCOL
    static void send(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes("UTF-8"));
        out.flush();
    }

    static Path resolve(String name) {
        Path f = FILES_DIR.resolve(name).normalize();
        // block path traversal like ../../etc/passwd
        if (!f.startsWith(FILES_DIR) || !Files.isRegularFile(f))
            return null;
        return f;
    }

    static void list(OutputStream out) throws IOException {
        try (Stream<Path> st = Files.list(FILES_DIR)) {
            for (Path f : (Iterable<Path>) st.filter(Files::isRegularFile)::iterator) {
                send(out, "FILE " + f.getFileName() + " " + Files.size(f) + "\n");
            }
        }
        send(out, "END\n");
    }

    static void info(OutputStream out, String[] p) throws IOException {
        if (p.length < 2) {
            send(out, "ERROR 400 Usage: INFO <file>\n");
            return;
        }
        Path f = resolve(p[1]);
        if (f == null) {
            send(out, "ERROR 404 File not found\n");
            return;
        }
        send(out, "SIZE " + Files.size(f) + "\n");
    }

    static void get(Socket s, OutputStream out, String[] p) throws IOException {
        if (p.length < 4) {
            send(out, "ERROR 400 Usage: GET <file> <offset> <length> [IO|NIO]\n");
            return;
        }
        Path f = resolve(p[1]);
        if (f == null) {
            send(out, "ERROR 404 File not found\n");
            return;
        }

        long offset, length;
        try {
            offset = Long.parseLong(p[2]);
            length = Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            send(out, "ERROR 400 Bad number\n");
            return;
        }
        long size = Files.size(f);
        if (offset < 0 || length < 0 || offset + length > size) {
            send(out, "ERROR 416 Invalid range\n");
            return;
        }
        boolean nio = p.length > 4 && p[4].equalsIgnoreCase("NIO");

        send(out, "OK " + length + "\n");

        if (nio) {
            // native/zero-copy path
            try (FileChannel fc = FileChannel.open(f, StandardOpenOption.READ)) {
                WritableByteChannel target = s.getChannel(); // needs SocketChannel-backed socket (see below)
                long pos = offset, remaining = length;
                while (remaining > 0) {
                    long n = fc.transferTo(pos, remaining, target);
                    pos += n;
                    remaining -= n;
                }
            }
        } else {
            // traditional I/O path
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                raf.seek(offset);
                byte[] buf = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0)
                        break;
                    out.write(buf, 0, n);
                    remaining -= n;
                }
                out.flush();
            }
        }
    }
}
