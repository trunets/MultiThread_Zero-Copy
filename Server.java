import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.PrintWriter;

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

class Server {
    // Create a server socket 
    ServerSocket serverSocket = new ServerSocket(5000);

    while (true) {
        Socket client = serverSocket.accept();

        // Create "in" for recieve input from client
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
        
        // Create "out" for send output to client
        PrintWriter out = new PrintWriter(client.getOutputStream(), true);

        // Read input from client
        String request = in.readLine();
        String[] parts = request.split(" ");

        if (request.equals("LIST")) {
            // request LIST
            // respond FILE <name> <size> <hash>

            // send file list to client
            
            

        } else if (request.startsWith("INFO")) {
            // request INFO <filename>
            // respond SIZE <bytes> or ERROR <code> <message>
            String filename = parts[1];


            
        } else if (request.startsWith("GET")) {
            // request GET <filename> <offset> <length>
            // respond DATA <bytes> or ERROR <code> <message>
            String filename = parts[1];
            long offset = Long.valueOf(parts[2]);
            long length = Long.valueOf(parts[3]);

        } else if (request.startsWith("ERROR")) {
            // request ERROR <code> <message>
            // respond ข้อความที่ Client นำไปจัดการได้
            String code = parts[1];
            String message = parts[2];
        }

        // respond to client request
        System.out.println("Client: " + request);


    }

}