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

    static final String SERVER_HOST = "localhost";
    static final int SERVER_PORT = 5000;
    static final int BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) throws Exception {

        // Support server commands as well as the original download syntax.
        if (args.length >= 1) {
            switch (args[0].toUpperCase(Locale.ROOT)) {
                case "LIST" -> {
                    if (args.length != 1) {
                        printUsage();
                        return;
                    }
                    listRemoteFiles();
                    return;
                }
                case "INFO" -> {
                    if (args.length != 2) {
                        printUsage();
                        return;
                    }
                    long size = getRemoteFileSize(args[1]);
                    System.out.println("File: " + args[1]);
                    System.out.println("Size: " + size + " bytes");
                    return;
                }
                case "HASH" -> {
                    if (args.length != 2) {
                        printUsage();
                        return;
                    }
                    System.out.println("File: " + args[1]);
                    System.out.println("SHA-256: " + getRemoteFileHash(args[1]));
                    return;
                }
                case "GET" -> {
                    if (args.length != 5) {
                        printUsage();
                        return;
                    }
                    long offset = Long.parseLong(args[2]);
                    long length = Long.parseLong(args[3]);
                    String mode = args[4].toUpperCase(Locale.ROOT);
                    if (offset < 0 || length < 0) {
                        throw new IllegalArgumentException("Offset and length must be non-negative");
                    }
                    if (!mode.equals("IO") && !mode.equals("NIO")) {
                        throw new IllegalArgumentException("Mode must be IO or NIO");
                    }
                    Path outputDirectory = Paths.get("files").toAbsolutePath().normalize();
                    Files.createDirectories(outputDirectory);
                    Path safeName = Paths.get(args[1]).getFileName();
                    if (safeName == null || safeName.toString().isBlank()) {
                        throw new IllegalArgumentException("File must be a valid filename");
                    }
                    Path outputPath = outputDirectory.resolve(
                            safeName.toString() + ".range-" + offset + "-" + length
                    ).normalize();
                    getRemoteByteRange(args[1], offset, length, mode, outputPath);
                    System.out.println("GET completed.");
                    System.out.println("Output: " + outputPath);
                    System.out.println("Offset: " + offset);
                    System.out.println("Length: " + Files.size(outputPath) + " bytes");
                    return;
                }
                case "HELP", "--HELP", "-H" -> {
                    printUsage();
                    return;
                }
                default -> {
                    // Continue to the original download argument parser.
                }
            }
        }

        // Usage: java -cp src Client <file> <output> <workers> <IO|NIO>
        if (args.length != 4) {
            printUsage();
            return;
        }

        String fileName = args[0];
        // Always save downloaded files inside the local files/ directory.
        // Use only the supplied output filename to prevent writing elsewhere.
        Path outputName = Paths.get(args[1]).getFileName();
        if (outputName == null || outputName.toString().isBlank()) {
            throw new IllegalArgumentException("Output must be a valid filename");
        }
        Path outputDirectory = Paths.get("files").toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        Path outputPath = outputDirectory.resolve(outputName).normalize();
        if (!outputPath.startsWith(outputDirectory)) {
            throw new IllegalArgumentException("Output must be inside the files directory");
        }
        int workerCount = Integer.parseInt(args[2]);
        String transferMode = args[3].toUpperCase(Locale.ROOT);

        if (workerCount < 1) {
            throw new IllegalArgumentException(
                    "Workers must be at least 1"
            );
        }

        if (!transferMode.equals("IO")
                && !transferMode.equals("NIO")) {
            throw new IllegalArgumentException(
                    "Mode must be IO or NIO"
            );
        }

        // Get the source file size from the server.
        long sourceFileSize = getRemoteFileSize(fileName);

        System.out.println("File: " + fileName);
        System.out.println("Output: " + outputPath);
        System.out.println("Size: " + sourceFileSize + " bytes");
        System.out.println("Workers: " + workerCount);
        System.out.println("Mode: " + transferMode);

        // Create the output directory if it does not exist.
        Path parentDirectory = outputPath.toAbsolutePath().getParent();

        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }

        // Create or resize the destination file.
        try (RandomAccessFile outputFile =
                     new RandomAccessFile(outputPath.toFile(), "rw")) {
            outputFile.setLength(sourceFileSize);
        }

        // Split the source file into non-overlapping byte ranges.
        List<ByteRange> downloadRanges =
                createDownloadRanges(sourceFileSize, workerCount);

        // Create one virtual thread for each download worker.
        ExecutorService downloadExecutor =
                Executors.newVirtualThreadPerTaskExecutor();

        List<Future<?>> downloadTasks = new ArrayList<>();

        long startTime = System.nanoTime();

        try {
            // Start one download task for each byte range.
            for (int i = 0; i < workerCount; i++) {

                final int workerId = i + 1;
                final ByteRange byteRange = downloadRanges.get(i);

                downloadTasks.add(downloadExecutor.submit(() -> {
                    try {
                        downloadByteRange(
                                workerId,
                                fileName,
                                outputPath,
                                byteRange,
                                transferMode
                        );
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Worker " + workerId + " failed",
                                e
                        );
                    }
                }));
            }

            // Wait until every worker finishes successfully.
            for (Future<?> downloadTask : downloadTasks) {
                downloadTask.get();
            }

        } finally {
            // Ensure the executor is closed even if a worker fails.
            downloadExecutor.shutdown();

            try {
                if (!downloadExecutor.awaitTermination(
                        1, TimeUnit.MINUTES)) {
                    downloadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                downloadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        long endTime = System.nanoTime();
        double elapsedSeconds =
                (endTime - startTime) / 1_000_000_000.0;

        // Verify the final file size.
        long downloadedFileSize = Files.size(outputPath);

        if (downloadedFileSize != sourceFileSize) {
            System.out.println("Verification: FAIL (size mismatch)");
            throw new IOException(
                    "File size verification failed. Expected "
                            + sourceFileSize + " bytes but got "
                            + downloadedFileSize + " bytes."
            );
        }

        // Calculate the source and destination SHA-256 hashes.
        String sourceHash = getRemoteFileHash(fileName);
        String downloadedHash = calculateSha256(outputPath);

        double fileSizeMB =
                downloadedFileSize / (1024.0 * 1024.0);

        double throughputMBps =
                elapsedSeconds > 0
                        ? fileSizeMB / elapsedSeconds
                        : 0.0;

        System.out.println();
        System.out.println("Download completed.");
        System.out.printf("Time: %.3f seconds%n", elapsedSeconds);
        System.out.printf("Throughput: %.2f MB/s%n", throughputMBps);
        System.out.println("Size: " + downloadedFileSize + " bytes");
        System.out.println("Source SHA-256: " + sourceHash);
        System.out.println("Output SHA-256: " + downloadedHash);

        // Only report PASS when both hashes match.
        if (!sourceHash.equals(downloadedHash)) {
            System.out.println(
                    "Verification: FAIL (SHA-256 mismatch)"
            );
            throw new IOException(
                    "File content verification failed."
            );
        }

        System.out.println("Verification: PASS");
    }

    static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -cp src Client LIST");
        System.out.println("  java -cp src Client INFO <file>");
        System.out.println("  java -cp src Client HASH <file>");
        System.out.println("  java -cp src Client GET <file> <offset> <length> <IO|NIO>");
        System.out.println("  java -cp src Client <file> <output> <workers> <IO|NIO>");
    }

    // List files using the LIST command. The response ends with END.
    static void listRemoteFiles() throws IOException {
        try (SocketChannel socket = connectToServer()) {
            sendLine(socket, "LIST");

            boolean receivedEnd = false;
            String response;
            while ((response = readLine(socket)) != null) {
                if (response.equals("END")) {
                    receivedEnd = true;
                    break;
                }
                if (response.startsWith("ERROR")) {
                    throw new IOException(response);
                }
                System.out.println(response);
            }

            if (!receivedEnd) {
                throw new EOFException("Server disconnected before LIST response ended");
            }
        }
    }

    // Get the source file size using the INFO command.
    static long getRemoteFileSize(String fileName)
            throws IOException {

        try (SocketChannel socket = connectToServer()) {

            sendLine(socket, "INFO " + fileName);

            String response = readLine(socket);

            if (response == null) {
                throw new EOFException("Server disconnected");
            }

            if (response.startsWith("ERROR")) {
                throw new IOException(response);
            }

            if (!response.startsWith("SIZE ")) {
                throw new IOException(
                        "Invalid response: " + response
                );
            }

            return Long.parseLong(response.substring(5).trim());
        }
    }

    // Get the source file's SHA-256 hash using the HASH command.
    static String getRemoteFileHash(String fileName)
            throws IOException {

        try (SocketChannel socket = connectToServer()) {

            sendLine(socket, "HASH " + fileName);

            String response = readLine(socket);

            if (response == null) {
                throw new EOFException("Server disconnected");
            }

            if (response.startsWith("ERROR")) {
                throw new IOException(response);
            }

            if (!response.startsWith("HASH ")) {
                throw new IOException(
                        "Invalid response: " + response
                );
            }

            return response.substring(5).trim();
        }
    }

    // Request an exact byte range and save only that range as a separate local file.
    static void getRemoteByteRange(String fileName, long offset, long length,
                                   String transferMode, Path outputPath) throws IOException {
        try (SocketChannel socket = connectToServer()) {
            sendLine(socket, "GET " + fileName + " " + offset + " " + length + " " + transferMode);
            String response = readLine(socket);
            if (response == null) throw new EOFException("Server disconnected");
            if (response.startsWith("ERROR")) throw new IOException(response);
            if (!response.equals("OK " + length)) {
                throw new IOException("Invalid response: " + response);
            }

            try (FileChannel output = FileChannel.open(outputPath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                long remaining = length;
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int bytesRead = socket.read(buffer);
                    if (bytesRead < 0) throw new EOFException("Connection closed before range completed");
                    if (bytesRead == 0) continue;
                    buffer.flip();
                    while (buffer.hasRemaining()) output.write(buffer);
                    remaining -= bytesRead;
                }
            }
            if (Files.size(outputPath) != length) {
                throw new IOException("Range size verification failed");
            }
        }
    }

    // Open a TCP connection to the server.
    static SocketChannel connectToServer()
            throws IOException {

        SocketChannel socket = SocketChannel.open();

        try {
            socket.connect(
                    new InetSocketAddress(
                            SERVER_HOST,
                            SERVER_PORT
                    )
            );
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    // Divide the file into non-overlapping byte ranges.
    static List<ByteRange> createDownloadRanges(
            long fileSize,
            int workerCount
    ) {

        List<ByteRange> downloadRanges = new ArrayList<>();

        long baseRangeLength = fileSize / workerCount;
        long currentOffset = 0;

        for (int i = 0; i < workerCount; i++) {
            // The first workers receive equal-sized ranges.
            // The last worker receives all remaining bytes, including the remainder.
            long rangeLength = (i == workerCount - 1)
                    ? fileSize - currentOffset
                    : baseRangeLength;

            downloadRanges.add(new ByteRange(currentOffset, rangeLength));
            currentOffset += rangeLength;
        }

        // Keep this invariant explicit: every source byte is assigned exactly once.
        if (currentOffset != fileSize) {
            throw new IllegalStateException("Byte ranges do not cover the entire file");
        }

        return downloadRanges;
    }

    // Download one byte range using a separate TCP connection.
    static void downloadByteRange(
            int workerId,
            String fileName,
            Path outputPath,
            ByteRange byteRange,
            String transferMode
    ) throws IOException {

        try (SocketChannel socket = connectToServer()) {

            // Request the assigned offset and length from the server.
            sendLine(
                    socket,
                    "GET "
                            + fileName
                            + " "
                            + byteRange.offset
                            + " "
                            + byteRange.length
                            + " "
                            + transferMode
            );

            String response = readLine(socket);

            if (response == null) {
                throw new EOFException("Server disconnected");
            }

            if (response.startsWith("ERROR")) {
                throw new IOException(response);
            }

            if (!response.equals("OK " + byteRange.length)) {
                throw new IOException(
                        "Invalid response: " + response
                );
            }

            // Write downloaded bytes at the correct file offset.
            receiveByteRange(socket, outputPath, byteRange);

            System.out.println(
                    "Worker " + workerId
                            + " completed: offset="
                            + byteRange.offset
                            + ", length="
                            + byteRange.length
            );
        }
    }

    // Receive bytes and write them to the assigned file positions.
    static void receiveByteRange(
            SocketChannel socket,
            Path outputPath,
            ByteRange byteRange
    ) throws IOException {

        try (RandomAccessFile outputFile =
                     new RandomAccessFile(outputPath.toFile(), "rw")) {

            FileChannel outputChannel = outputFile.getChannel();
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

            long remainingBytes = byteRange.length;
            long currentPosition = byteRange.offset;

            // Continue reading until the entire range is received.
            while (remainingBytes > 0) {

                int requestedBytes = (int) Math.min(
                        BUFFER_SIZE,
                        remainingBytes
                );

                buffer.clear();
                buffer.limit(requestedBytes);

                int bytesRead = socket.read(buffer);

                if (bytesRead == -1) {
                    throw new EOFException(
                            "Connection closed before range completed"
                    );
                }

                if (bytesRead == 0) {
                    continue;
                }

                buffer.flip();

                // Positional writes prevent workers overwriting each other.
                while (buffer.hasRemaining()) {
                    int bytesWritten = outputChannel.write(
                            buffer,
                            currentPosition
                    );

                    if (bytesWritten == 0) {
                        continue;
                    }

                    currentPosition += bytesWritten;
                }

                remainingBytes -= bytesRead;
            }
        }
    }

    // Send a UTF-8 command terminated by a newline.
    static void sendLine(
            SocketChannel socket,
            String message
    ) throws IOException {

        ByteBuffer buffer =
                StandardCharsets.UTF_8.encode(message + "\n");

        while (buffer.hasRemaining()) {
            socket.write(buffer);
        }
    }

    // Read one newline-terminated response from the server.
    static String readLine(SocketChannel socket)
            throws IOException {

        ByteArrayOutputStream responseBytes =
                new ByteArrayOutputStream();

        ByteBuffer buffer = ByteBuffer.allocate(1);

        while (true) {
            buffer.clear();

            int bytesRead = socket.read(buffer);

            if (bytesRead == -1) {
                if (responseBytes.size() == 0) {
                    return null;
                }

                throw new EOFException("Connection closed");
            }

            if (bytesRead == 0) {
                continue;
            }

            buffer.flip();
            byte currentByte = buffer.get();

            if (currentByte == '\n') {
                break;
            }

            if (currentByte != '\r') {
                responseBytes.write(currentByte);
            }
        }

        return responseBytes.toString(StandardCharsets.UTF_8);
    }

    // Calculate the SHA-256 hash of a local file.
    static String calculateSha256(Path filePath)
            throws IOException {

        final MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }

        try (InputStream input = Files.newInputStream(filePath)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            // Include every byte in the hash calculation.
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        StringBuilder hashText = new StringBuilder();

        // Convert the hash bytes to a hexadecimal string.
        for (byte hashByte : digest.digest()) {
            hashText.append(String.format("%02x", hashByte & 0xff));
        }

        return hashText.toString();
    }

    // Store the offset and length assigned to one worker.
    static class ByteRange {

        final long offset;
        final long length;

        ByteRange(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }
    }
}