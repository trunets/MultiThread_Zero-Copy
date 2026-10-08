import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class Server {

    static final int PORT = 5000;
    static final int POOL_SIZE = 20;
    static final Path FILES_DIR =
            Paths.get("files").toAbsolutePath().normalize();
    static final int BUF_SIZE = 64 * 1024;

    public static void main(String[] args) throws IOException {

        // Create workers for concurrent clients.
        ExecutorService pool =
                Executors.newFixedThreadPool(POOL_SIZE);

        // Start TCP server.
        try (ServerSocketChannel server =
                     ServerSocketChannel.open()) {

            server.bind(new InetSocketAddress(PORT));

            System.out.println("Server listening on port " + PORT);
            System.out.println("Serving files from " + FILES_DIR);

            // Accept clients continuously.
            while (true) {
                SocketChannel client = server.accept();
                pool.submit(() -> handle(client));
            }
        }
    }

    // Handle commands from one client.
    static void handle(SocketChannel client) {

        try (
                client;
                BufferedReader in = new BufferedReader(
                        Channels.newReader(
                                client,
                                StandardCharsets.UTF_8.newDecoder(),
                                -1
                        )
                );
                OutputStream out = Channels.newOutputStream(client)
        ) {
            String line;

            while ((line = in.readLine()) != null) {

                // Parse command arguments.
                String[] p = line.trim().split("\\s+");

                if (p.length == 0 || p[0].isEmpty())
                    continue;

                switch (p[0].toUpperCase()) {
                    case "LIST" -> list(out);
                    case "INFO" -> info(out, p);
                    case "GET" -> get(client, out, p);
                    default -> error(out, 400, "Unknown command");
                }
            }

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    // Send a text response.
    static void send(OutputStream out, String msg)
            throws IOException {

        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // Send an error response.
    static void error(
            OutputStream out,
            int code,
            String msg
    ) throws IOException {

        send(out, "ERROR " + code + " " + msg + "\n");
    }

    // Validate and resolve a file path.
    static Path resolve(String name) {

        try {
            Path file = FILES_DIR.resolve(name).normalize();

            if (!file.startsWith(FILES_DIR)
                    || !Files.isRegularFile(file)) {
                return null;
            }

            return file;

        } catch (InvalidPathException e) {
            return null;
        }
    }

    // Return all available files.
    static void list(OutputStream out) throws IOException {

        if (!Files.isDirectory(FILES_DIR)) {
            error(out, 500, "Files directory not found");
            return;
        }

        StringBuilder result = new StringBuilder();

        try (Stream<Path> files = Files.list(FILES_DIR)) {

            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> {
                        try {
                            result.append("FILE ")
                                    .append(file.getFileName())
                                    .append(" ")
                                    .append(Files.size(file))
                                    .append("\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        result.append("END\n");
        send(out, result.toString());
    }

    // Return the size of one file.
    static void info(
            OutputStream out,
            String[] p
    ) throws IOException {

        if (p.length < 2) {
            error(out, 400, "Usage: INFO <file>");
            return;
        }

        Path file = resolve(p[1]);

        if (file == null) {
            error(out, 404, "File not found");
            return;
        }

        send(out, "SIZE " + Files.size(file) + "\n");
    }

    // Validate GET and start the selected transfer method.
    static void get(
            SocketChannel client,
            OutputStream out,
            String[] p
    ) throws IOException {

        if (p.length < 4) {
            error(out, 400,
                    "Usage: GET <file> <offset> <length> [IO|NIO]");
            return;
        }

        Path file = resolve(p[1]);

        if (file == null) {
            error(out, 404, "File not found");
            return;
        }

        long offset;
        long length;

        try {
            offset = Long.parseLong(p[2]);
            length = Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            error(out, 400, "Invalid offset or length");
            return;
        }

        // Select IO or NIO transfer.
        boolean nio = p.length > 4 &&
                p[4].equalsIgnoreCase("NIO");

        if (p.length > 4 &&
                !p[4].equalsIgnoreCase("IO") &&
                !p[4].equalsIgnoreCase("NIO")) {

            error(out, 400, "Mode must be IO or NIO");
            return;
        }

        long size = Files.size(file);

        // Check that the requested range is valid.
        if (offset < 0 ||
                length < 0 ||
                offset > size ||
                length > size - offset) {

            error(out, 416, "Invalid range");
            return;
        }

        try (FileChannel fc =
                     FileChannel.open(file, StandardOpenOption.READ)) {

            // Tell client exactly how many bytes will follow.
            send(out, "OK " + length + "\n");

            if (nio) {
                sendNio(client, fc, offset, length);
            } else {
                sendIO(out, fc, offset, length);
            }
        }
    }

    // Transfer bytes using NIO transferTo().
    static void sendNio(
            SocketChannel client,
            FileChannel file,
            long offset,
            long length
    ) throws IOException {

        long position = offset;
        long remaining = length;

        while (remaining > 0) {

            long sent = file.transferTo(
                    position,
                    remaining,
                    client
            );

            if (sent <= 0)
                throw new EOFException("Transfer failed");

            position += sent;
            remaining -= sent;
        }
    }

    // Transfer bytes using traditional buffered I/O.
    static void sendIO(
            OutputStream out,
            FileChannel file,
            long offset,
            long length
    ) throws IOException {

        file.position(offset);

        InputStream in = Channels.newInputStream(file);
        byte[] buffer = new byte[BUF_SIZE];

        long remaining = length;

        while (remaining > 0) {

            int read = in.read(
                    buffer,
                    0,
                    (int) Math.min(
                            buffer.length,
                            remaining
                    )
            );

            if (read < 0)
                throw new EOFException("File ended early");

            out.write(buffer, 0, read);
            remaining -= read;
        }

        out.flush();
    }
}