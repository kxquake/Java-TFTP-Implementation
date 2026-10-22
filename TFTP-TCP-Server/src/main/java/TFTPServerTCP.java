import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public class TFTPServerTCP {

    private static final int DEFAULT_PORT = 12345;   // Port > 1024
    private static final int BLOCK_SIZE   = 512;
    private static final String MODE      = "octet";

    public static void main(String[] args) {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        ExecutorService threadPool = Executors.newCachedThreadPool();

        String bind = args.length > 1 ? args[1] : "127.0.0.1";
        try (ServerSocket serverSocket = new ServerSocket(port, 50, InetAddress.getByName(bind))) {

            System.out.println("TFTP Server (TCP) listening on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(15000);
                threadPool.execute(new ClientHandler(clientSocket));
            }

        } catch (IOException ex) {
            System.err.println("Server error: " + ex.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }


    // Inner class for handling TCP client connections.
    private static class ClientHandler implements Runnable {

        private final Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (DataInputStream in  = new DataInputStream(clientSocket.getInputStream());
                 DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {

                // Read the request: [opcode] + [filename terminated by 0] + [mode terminated by 0]
                short opcode = in.readShort();
                String filename = readNullTerminatedString(in);
                String mode     = readNullTerminatedString(in);
                try {
                    resolveFile(filename);
                } catch (IOException invalid) {
                    sendError(out, 2, "Filename must stay in the server's working directory");
                    return;
                }

                if (!mode.equalsIgnoreCase(MODE)) {
                    sendError(out, 0, "Unsupported mode. Only octet mode is supported.");
                    return;
                }

                if (opcode == 1) {     // RRQ (read)
                    handleReadRequest(filename, in, out);
                } else if (opcode == 2) {   // WRQ (write)
                    handleWriteRequest(filename, in, out);
                } else {
                    sendError(out, 4, "Illegal TFTP operation");
                }

            } catch (FileNotFoundException fnfe) {
                try {
                    sendError(new DataOutputStream(clientSocket.getOutputStream()), 1, "File not found");
                } catch (IOException ioe) {
                    System.err.println("Error sending error message: " + ioe.getMessage());
                }
            } catch (IOException ioe) {
                System.err.println("Client handling error: " + ioe.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException ioe) {
                    System.err.println("Error closing client socket: " + ioe.getMessage());
                }
            }
        }


        private void handleReadRequest(String filename, DataInputStream in, DataOutputStream out) throws IOException {
            File file = resolveFile(filename);
            if (!file.exists() || !file.isFile()) {
                sendError(out, 1, "File not found");
                return;
            }

            try (FileInputStream fileIn = new FileInputStream(file)) {
                byte[] buffer = new byte[BLOCK_SIZE];
                int bytesRead;
                int blockNum = 1;

                do {
                    bytesRead = fileIn.read(buffer);
                    if (bytesRead == -1) bytesRead = 0;
                    // Create DATA packet (opcode + block number + data)
                    out.writeShort(3);
                    out.writeShort(blockNum);
                    out.writeShort(bytesRead);
                    out.write(buffer, 0, bytesRead);

                    if (in.readUnsignedShort() != 4 || in.readUnsignedShort() != blockNum) {
                        throw new IOException("Invalid ACK");
                    }
                    blockNum = (blockNum + 1) & 0xffff;
                } while (bytesRead == BLOCK_SIZE);
            }
        }

        private void handleWriteRequest(String filename, DataInputStream in, DataOutputStream out) throws IOException {
            File file = resolveFile(filename);
            if (file.exists()) {
                sendError(out, 6, "File already exists");
                return;
            }

            boolean complete = false;
            boolean created = false;
            try (OutputStream fileOut = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;

                // Send initial ACK (block 0) for WRQ.
                out.writeShort(4);
                out.writeShort(0);

                byte[] buffer = new byte[BLOCK_SIZE];
                int blockNum = 1;
                int bytesRead;

                do {
                    short dataOpcode = in.readShort();
                    if (dataOpcode != 3) {
                        sendError(out, 4, "Illegal TFTP operation");
                        return;
                    }

                    int receivedBlock = in.readUnsignedShort();
                    if (receivedBlock != blockNum) {
                        sendError(out, 0, "Invalid block number");
                        return;
                    }

                    bytesRead = in.readUnsignedShort();
                    if (bytesRead > BLOCK_SIZE) {
                        sendError(out, 4, "Invalid block length");
                        return;
                    }
                    in.readFully(buffer, 0, bytesRead);
                    if (bytesRead > 0) {
                        fileOut.write(buffer, 0, bytesRead);
                    }

                    // Send ACK for the received block.
                    out.writeShort(4);
                    out.writeShort(blockNum);

                    blockNum = (blockNum + 1) & 0xffff;

                } while (bytesRead == BLOCK_SIZE);
                complete = true;
            } finally {
                if (created && !complete) Files.deleteIfExists(file.toPath());
            }
        }

        private void sendError(DataOutputStream out, int errorCode, String errorMsg) throws IOException {
            out.writeShort(5); // ERROR opcode
            out.writeShort(errorCode);
            out.write(errorMsg.getBytes(StandardCharsets.UTF_8));
            out.writeByte(0);   // Null terminator
        }

        // Helper method to read a null-terminated string.
        private String readNullTerminatedString(DataInputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte b;
            while ((b = in.readByte()) != 0) {
                if (baos.size() >= 1024) throw new IOException("Unterminated request string");
                baos.write(b);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }

        private File resolveFile(String filename) throws IOException {
            File root = new File(".").getCanonicalFile();
            File file = new File(root, filename).getCanonicalFile();
            if (filename.isEmpty() || filename.equals(".") || filename.equals("..") || filename.length() > 255
                    || filename.contains("/") || filename.contains("\\") || filename.contains(":")
                    || !root.equals(file.getParentFile())) throw new IOException("Invalid filename");
            return file;
        }
    }
}
