# Multi-Threaded File Download

## Project Structure
```text
MultiThread_Zero-Copy/
├── files/
│   └── test.bin
├── src/
│   ├── Server.java
│   ├── Client.java
│   ├── Server.class
│   └── Client.class
└── README.md
```

## Compile
Run from the project root:

```powershell
javac -d src src\Server.java src\Client.java
```

## Run

**Terminal 1 — Start the server**
```powershell
java -cp src Server
```

**Terminal 2 — Run the client**
```powershell
java -cp src Client test.bin output.bin 10 NIO
```

## Client Arguments
```text
Client <file> <output> <workers> <IO|NIO>
```

- `file`: Filename inside the server's `files/` directory
- `output`: Local destination file
- `workers`: Number of download threads
- `IO`: Traditional buffered I/O
- `NIO`: Uses `FileChannel.transferTo()`

## Testing
Try 1 and 10 workers with both `IO` and `NIO`. Compare download times and verify the downloaded file matches the original.

The server listens on port `5000` by default.