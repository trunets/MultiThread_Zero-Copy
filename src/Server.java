import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class Server {
    static final int PORT = 5000;
    static final Path FILES_DIR =
            Paths.get("files").toAbsolutePath().normalize();
    static final int BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) throws IOException {
        ExecutorService clientExecutor =
                Executors.newVirtualThreadPerTaskExecutor();

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(PORT));

            System.out.println("===============================");
            System.out.println("Server started");
            System.out.println("Server listening on port " + PORT);
            System.out.println("Serving files from " + FILES_DIR);
            System.out.println("===============================");

            while (true) {
                SocketChannel client = server.accept();
                clientExecutor.submit(() -> handle(client));
            }
        } finally {
            clientExecutor.shutdown();
        }
    }

    static void handle(SocketChannel client) {
        try (client;
             InputStream input = Channels.newInputStream(client);
             OutputStream output = Channels.newOutputStream(client)) {

            String line;

            while ((line = readLine(input)) != null) {
                // Remove control characters and Telnet-related bytes
                // that may be mixed into a command line.
                line = sanitizeCommand(line).trim();

                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+");

                System.out.println("Received command: " + line);
                System.out.println("Argument count: " + parts.length);

                switch (parts[0].toUpperCase(Locale.ROOT)) {
                    case "LIST" -> {
                        if (parts.length != 1) {
                            sendError(output, 400, "Usage: LIST");
                        } else {
                            list(output);
                        }
                    }
                    case "INFO" -> info(output, parts);
                    case "HASH" -> hash(output, parts);
                    case "GET" -> get(client, output, parts);
                    default -> sendError(output, 400, "Unknown command");
                }
            }
        } catch (IOException e) {
            System.err.println(
                    "Client connection ended: " + e.getMessage());
        }
    }

    static String sanitizeCommand(String line) {
        // Keep printable ASCII and tabs. This protocol uses ASCII commands.
        return line.replaceAll("[^\\x20-\\x7E\\t]", "");
    }

    static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;

        while ((b = input.read()) != -1) {
            if (b == '\n') {
                break;
            }
            buffer.write(b);
        }

        if (b == -1 && buffer.size() == 0) {
            return null;
        }

        byte[] bytes = buffer.toByteArray();
        int length = bytes.length;

        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }

        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    static void send(OutputStream output, String message)
            throws IOException {
        output.write(message.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    static void sendError(OutputStream output, int code, String message)
            throws IOException {
        send(output, "ERROR " + code + " " + message + "\r\n");
    }

    static Path resolve(String fileName) {
        try {
            if (!Files.isDirectory(FILES_DIR)) {
                return null;
            }

            Path realDirectory = FILES_DIR.toRealPath();
            Path candidate = FILES_DIR.resolve(fileName).normalize();

            if (!candidate.startsWith(FILES_DIR)
                    || !Files.isRegularFile(candidate)) {
                return null;
            }

            Path realFile = candidate.toRealPath();

            if (!realFile.startsWith(realDirectory)
                    || !Files.isRegularFile(realFile)) {
                return null;
            }

            return realFile;
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }

    static void list(OutputStream output) throws IOException {
        if (!Files.isDirectory(FILES_DIR)) {
            sendError(output, 500, "Files directory not found");
            return;
        }

        StringBuilder response = new StringBuilder();

        try (Stream<Path> files = Files.list(FILES_DIR)) {
            files.filter(file ->
                            resolve(file.getFileName().toString()) != null)
                    .sorted()
                    .forEach(file -> {
                        try {
                            response.append("FILE ")
                                    .append(file.getFileName())
                                    .append(" ")
                                    .append(Files.size(file))
                                    .append("\r\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        response.append("END\r\n");
        send(output, response.toString());
    }

    static void info(OutputStream output, String[] parts)
            throws IOException {
        if (parts.length != 2) {
            sendError(output, 400, "Usage: INFO <file>");
            return;
        }

        Path filePath = resolve(parts[1]);

        if (filePath == null) {
            sendError(output, 404, "File not found");
            return;
        }

        send(output, "SIZE " + Files.size(filePath) + "\r\n");
    }

    static void hash(OutputStream output, String[] parts)
            throws IOException {
        if (parts.length != 2) {
            sendError(output, 400, "Usage: HASH <file>");
            return;
        }

        Path filePath = resolve(parts[1]);

        if (filePath == null) {
            sendError(output, 404, "File not found");
            return;
        }

        final MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }

        try (InputStream fileInput = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = fileInput.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        send(output,
                "HASH " + HexFormat.of().formatHex(digest.digest())
                        + "\r\n");
    }

    static void get(SocketChannel client, OutputStream output,
                    String[] parts) throws IOException {

        // GET file offset length [IO|NIO]
        if (parts.length != 4 && parts.length != 5) {
            sendError(output, 400,
                    "Usage: GET <file> <offset> <length> [IO|NIO]");
            return;
        }

        Path filePath = resolve(parts[1]);

        if (filePath == null) {
            sendError(output, 404, "File not found");
            return;
        }

        final long offset;
        final long length;

        try {
            offset = Long.parseLong(parts[2]);
            length = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            sendError(output, 400, "Invalid offset or length");
            return;
        }

        boolean useNio = false;

        if (parts.length == 5) {
            if (parts[4].equalsIgnoreCase("NIO")) {
                useNio = true;
            } else if (!parts[4].equalsIgnoreCase("IO")) {
                sendError(output, 400, "Mode must be IO or NIO");
                return;
            }
        }

        long fileSize = Files.size(filePath);

        if (offset < 0 || length < 0
                || offset > fileSize
                || length > fileSize - offset) {
            sendError(output, 416, "Invalid range");
            return;
        }

        try (FileChannel fileChannel =
                     FileChannel.open(filePath, StandardOpenOption.READ)) {

            // Header is followed by exactly 'length' binary bytes.
            String header = "OK " + length + "\r\n";

            if (useNio) {
                writeFully(client,
                        header.getBytes(StandardCharsets.UTF_8));
                sendNio(client, output, fileChannel, offset, length);
            } else {
                send(output, header);
                sendIo(output, fileChannel, offset, length);
            }
        }
    }

    static void sendNio(SocketChannel client, OutputStream output,
                        FileChannel fileChannel, long offset, long length)
            throws IOException {

        long position = offset;
        long remaining = length;

        while (remaining > 0) {
            long bytesSent =
                    fileChannel.transferTo(position, remaining, client);

            if (bytesSent < 0) {
                throw new EOFException(
                        "File ended before requested range was sent");
            }

            if (bytesSent == 0) {
                // Fall back to buffered IO if transferTo makes no progress.
                sendIo(output, fileChannel, position, remaining);
                return;
            }

            position += bytesSent;
            remaining -= bytesSent;
        }
    }

    static void writeFully(SocketChannel channel, byte[] data)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    static void sendIo(OutputStream output, FileChannel fileChannel,
                       long offset, long length) throws IOException {

        fileChannel.position(offset);

        InputStream fileInput = Channels.newInputStream(fileChannel);
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;

        while (remaining > 0) {
            int bytesRead = fileInput.read(
                    buffer, 0,
                    (int) Math.min(buffer.length, remaining));

            if (bytesRead < 0) {
                throw new EOFException(
                        "File ended before requested range was sent");
            }

            if (bytesRead == 0) {
                continue;
            }

            output.write(buffer, 0, bytesRead);
            remaining -= bytesRead;
        }

        output.flush();
    }
}