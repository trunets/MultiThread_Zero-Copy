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
 


 * Protocol (text command line ending with \n, then optional binary payload)
 *
 *   LIST                              -> FILE <name> <size>\n  (one per file) then END\n
 *   INFO <file>                       -> SIZE <bytes>\n
 *   GET <file> <offset> <length> [IO|NIO]
 *                                     -> OK <length>\n + exactly <length> raw bytes
 *   Any failure                       -> ERROR <code> <message>\n
 *
 * Error codes:
 *   400 Bad request     (unknown command, missing/non-numeric arguments)
 *   404 File not found  (missing file or path traversal attempt)
 *   416 Invalid range   (offset < 0, length < 0, offset + length > file size)
 *   500 Internal error  (file cannot be opened / unexpected failure before OK)
 *
 * Notes:
 *   - After an ERROR the connection stays open and the next command is read.
 *   - Once "OK <length>" has been sent, an error can no longer be reported in-band;
 *     if the transfer fails the server closes the connection (client sees a short read).
 *   - File names must not contain spaces (commands are split on whitespace).
 */
public class Server {
    static final int PORT = 5000;
    static final int POOL_SIZE = 20; // must be >= 10 workers x number of clients you test with
    static final Path FILES_DIR = Paths.get("files").toAbsolutePath().normalize();
    static final int BUF_SIZE = 64 * 1024;
 
    public static void main(String[] args) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        // ServerSocketChannel gives channel-backed sockets, so s.getChannel() works for transferTo
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(PORT));
            System.out.println("Server listening on port " + PORT);
            System.out.println("Serving files from " + FILES_DIR);
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
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                System.out.println("Client: " + line);
 
                switch (p[0].toUpperCase()) {
                    case "LIST" -> list(out);
                    case "INFO" -> info(out, p);
                    case "GET" -> get(s, out, p);
                    default -> error(out, 400, "Unknown command");
                }
            }
        } catch (IOException e) {
            // includes mid-transfer failures: just drop the connection
            System.err.println("Client error: " + e.getMessage());
        }
    }
 
    // ---------- helpers ----------
 
    static void send(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes("UTF-8"));
        out.flush();
    }
 
    static void error(OutputStream out, int code, String msg) throws IOException {
        send(out, "ERROR " + code + " " + msg + "\n");
    }
 
    /** Resolves a file name inside FILES_DIR; returns null if missing or outside the folder. */
    static Path resolve(String name) {
        try {
            Path f = FILES_DIR.resolve(name).normalize();
            if (!f.startsWith(FILES_DIR) || !Files.isRegularFile(f)) return null;
            return f;
        } catch (InvalidPathException e) {
            return null;
        }
    }
 
    // ---------- commands ----------
 
    static void list(OutputStream out) throws IOException {
        if (!Files.isDirectory(FILES_DIR)) {
            error(out, 500, "Files directory not found");
            return;
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> st = Files.list(FILES_DIR)) {
            for (Path f : (Iterable<Path>) st.filter(Files::isRegularFile).sorted()::iterator) {
                sb.append("FILE ").append(f.getFileName()).append(' ').append(Files.size(f)).append('\n');
            }
        }
        sb.append("END\n");
        send(out, sb.toString());
    }
 
    static void info(OutputStream out, String[] p) throws IOException {
        if (p.length < 2) {
            error(out, 400, "Usage: INFO <file>");
            return;
        }
        Path f = resolve(p[1]);
        if (f == null) {
            error(out, 404, "File not found");
            return;
        }
        send(out, "SIZE " + Files.size(f) + "\n");
    }
 
    static void get(Socket s, OutputStream out, String[] p) throws IOException {
        if (p.length < 4) {
            error(out, 400, "Usage: GET <file> <offset> <length> [IO|NIO]");
            return;
        }
        Path f = resolve(p[1]);
        if (f == null) {
            error(out, 404, "File not found");
            return;
        }
 
        long offset, length;
        try {
            offset = Long.parseLong(p[2]);
            length = Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            error(out, 400, "Offset and length must be numbers");
            return;
        }
 
        boolean nio = false;
        if (p.length > 4) {
            if (p[4].equalsIgnoreCase("NIO")) nio = true;
            else if (!p[4].equalsIgnoreCase("IO")) {
                error(out, 400, "Mode must be IO or NIO");
                return;
            }
        }
 
        long size = Files.size(f);
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            error(out, 416, "Invalid range");
            return;
        }
 
        // Open the file BEFORE sending OK so an open failure can still be reported as ERROR 500
        FileChannel fc;
        try {
            fc = FileChannel.open(f, StandardOpenOption.READ);
        } catch (IOException e) {
            error(out, 500, "Cannot open file");
            return;
        }
 
        try (fc) {
            send(out, "OK " + length + "\n");
            if (nio) {
                sendNio(s, fc, offset, length);
            } else {
                sendTraditional(out, fc, offset, length);
            }
        }
    }
 
    /** NIO / native transfer path: kernel copies file -> socket (zero-copy where supported). */
    static void sendNio(Socket s, FileChannel fc, long offset, long length) throws IOException {
        WritableByteChannel target = s.getChannel();
        if (target == null) throw new IOException("Socket has no channel");
        long pos = offset, remaining = length;
        while (remaining > 0) {
            long n = fc.transferTo(pos, remaining, target);
            if (n <= 0) throw new EOFException("transferTo made no progress");
            pos += n;
            remaining -= n;
        }
    }
 
    /** Traditional I/O path: read into a byte[] buffer, write with OutputStream. */
    static void sendTraditional(OutputStream out, FileChannel fc, long offset, long length) throws IOException {
        fc.position(offset);
        InputStream is = Channels.newInputStream(fc);
        byte[] buf = new byte[BUF_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int n = is.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new EOFException("File shorter than expected");
            out.write(buf, 0, n);
            remaining -= n;
        }
        out.flush();
    }
}
