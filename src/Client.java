import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class Client {

    static final String HOST = "localhost";
    static final int PORT = 5000;
    static final int BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) throws Exception {

        // Usage: java Client <file> <output> <workers> <IO|NIO>
        if (args.length < 4) {
            System.out.println(
                    "Usage: java Client <file> <output> <workers> <IO|NIO>"
            );
            return;
        }

        String fileName = args[0];
        Path output = Paths.get(args[1]);
        int workers = Integer.parseInt(args[2]);
        String mode = args[3].toUpperCase();

        if (workers < 1) {
            throw new IllegalArgumentException(
                    "Workers must be at least 1"
            );
        }

        if (!mode.equals("IO") && !mode.equals("NIO")) {
            throw new IllegalArgumentException(
                    "Mode must be IO or NIO"
            );
        }

        // Get the remote file size.
        long fileSize = getFileSize(fileName);

        System.out.println("File: " + fileName);
        System.out.println("Size: " + fileSize + " bytes");
        System.out.println("Workers: " + workers);
        System.out.println("Mode: " + mode);

        // Create the output file with the correct size.
        Path parent = output.toAbsolutePath().getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (RandomAccessFile file =
                     new RandomAccessFile(output.toFile(), "rw")) {
            file.setLength(fileSize);
        }

        // Split the file into non-overlapping ranges.
        List<Range> ranges =
                createRanges(fileSize, workers);

        ExecutorService pool =
                Executors.newFixedThreadPool(workers);

        List<Future<?>> tasks = new ArrayList<>();

        long start = System.nanoTime();

        // Start one connection for each range.
        for (int i = 0; i < workers; i++) {

            final int workerId = i + 1;
            final Range range = ranges.get(i);

            tasks.add(pool.submit(() -> {
                try {
                    downloadRange(
                            workerId,
                            fileName,
                            output,
                            range,
                            mode
                    );
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Worker " + workerId + " failed",
                            e
                    );
                }
            }));
        }

        // Wait for all workers to finish.
        for (Future<?> task : tasks) {
            task.get();
        }

        pool.shutdown();

        long end = System.nanoTime();

        double seconds =
                (end - start) / 1_000_000_000.0;

        // Verify size and calculate SHA-256.
        long finalSize = Files.size(output);
        String hash = sha256(output);

        double mb =
                finalSize / (1024.0 * 1024.0);

        double throughput =
                mb / seconds;

        System.out.println();
        System.out.println("Download completed.");
        System.out.printf("Time: %.3f seconds%n", seconds);
        System.out.printf("Throughput: %.2f MB/s%n", throughput);
        System.out.println("Size: " + finalSize + " bytes");
        System.out.println("SHA-256: " + hash);

        // Verify the final file size.
        if (finalSize != fileSize) {
            throw new IOException(
                    "File size verification failed"
            );
        }

        System.out.println("Verification: PASS");
    }

    // Get the remote file size using INFO.
    static long getFileSize(String fileName)
            throws IOException {

        try (SocketChannel channel = connect()) {

            sendLine(
                    channel,
                    "INFO " + fileName
            );

            String response = readLine(channel);

            if (response == null) {
                throw new EOFException(
                        "Server disconnected"
                );
            }

            if (response.startsWith("ERROR")) {
                throw new IOException(response);
            }

            if (!response.startsWith("SIZE ")) {
                throw new IOException(
                        "Invalid response: " + response
                );
            }

            return Long.parseLong(
                    response.substring(5).trim()
            );
        }
    }

    // Create a TCP connection to the server.
    static SocketChannel connect()
            throws IOException {

        SocketChannel channel =
                SocketChannel.open();

        channel.connect(
                new InetSocketAddress(
                        HOST,
                        PORT
                )
        );

        return channel;
    }

    // Divide the file into non-overlapping ranges.
    static List<Range> createRanges(
            long fileSize,
            int workers
    ) {

        List<Range> ranges = new ArrayList<>();

        long base = fileSize / workers;
        long remainder = fileSize % workers;

        long offset = 0;

        for (int i = 0; i < workers; i++) {

            // Give the remaining bytes to the first workers.
            long length =
                    base + (i < remainder ? 1 : 0);

            ranges.add(
                    new Range(offset, length)
            );

            offset += length;
        }

        return ranges;
    }

    // Download one range using its own connection.
    static void downloadRange(
            int workerId,
            String fileName,
            Path output,
            Range range,
            String mode
    ) throws IOException {

        try (SocketChannel channel = connect()) {

            // Request only this worker's range.
            sendLine(
                    channel,
                    "GET "
                            + fileName
                            + " "
                            + range.offset
                            + " "
                            + range.length
                            + " "
                            + mode
            );

            String response =
                    readLine(channel);

            if (response == null) {
                throw new EOFException(
                        "Server disconnected"
                );
            }

            if (response.startsWith("ERROR")) {
                throw new IOException(response);
            }

            if (!response.equals(
                    "OK " + range.length)) {

                throw new IOException(
                        "Invalid response: " + response
                );
            }

            // Write received bytes to this range.
            receiveRange(
                    channel,
                    output,
                    range
            );

            System.out.println(
                    "Worker " + workerId
                            + " completed: offset="
                            + range.offset
                            + ", length="
                            + range.length
            );
        }
    }

    // Receive bytes and write directly to the correct offset.
    static void receiveRange(
            SocketChannel channel,
            Path output,
            Range range
    ) throws IOException {

        try (RandomAccessFile file =
                     new RandomAccessFile(
                             output.toFile(),
                             "rw"
                     )) {

            FileChannel outputChannel =
                    file.getChannel();

            ByteBuffer buffer =
                    ByteBuffer.allocate(BUFFER_SIZE);

            long remaining = range.length;
            long position = range.offset;

            // Read until this range is complete.
            while (remaining > 0) {

                int size = (int) Math.min(
                        BUFFER_SIZE,
                        remaining
                );

                buffer.clear();
                buffer.limit(size);

                int read = channel.read(buffer);

                if (read == -1) {
                    throw new EOFException(
                            "Connection closed early"
                    );
                }

                if (read == 0) {
                    continue;
                }

                buffer.flip();

                // Write at the assigned file position.
                while (buffer.hasRemaining()) {
                    outputChannel.write(
                            buffer,
                            position
                    );
                }

                position += read;
                remaining -= read;
            }
        }
    }

    // Send one command line to the server.
    static void sendLine(
            SocketChannel channel,
            String line
    ) throws IOException {

        ByteBuffer buffer =
                StandardCharsets.UTF_8.encode(
                        line + "\n"
                );

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    // Read one response line from the server.
    static String readLine(
            SocketChannel channel
    ) throws IOException {

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        ByteBuffer buffer =
                ByteBuffer.allocate(1);

        while (true) {

            buffer.clear();

            int read = channel.read(buffer);

            if (read == -1) {

                if (output.size() == 0) {
                    return null;
                }

                throw new EOFException(
                        "Connection closed"
                );
            }

            if (read == 0) {
                continue;
            }

            buffer.flip();

            byte value = buffer.get();

            if (value == '\n') {
                break;
            }

            if (value != '\r') {
                output.write(value);
            }
        }

        return output.toString(
                StandardCharsets.UTF_8
        );
    }

    // Calculate SHA-256 for file verification.
    static String sha256(Path file)
            throws Exception {

        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");

        try (InputStream input =
                     Files.newInputStream(file)) {

            byte[] buffer =
                    new byte[BUFFER_SIZE];

            int read;

            // Read the complete file.
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] hash = digest.digest();

        StringBuilder result =
                new StringBuilder();

        // Convert hash bytes to hexadecimal.
        for (byte b : hash) {
            result.append(
                    String.format("%02x", b)
            );
        }

        return result.toString();
    }

    // Store one worker's byte range.
    static class Range {

        final long offset;
        final long length;

        Range(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }
    }
}