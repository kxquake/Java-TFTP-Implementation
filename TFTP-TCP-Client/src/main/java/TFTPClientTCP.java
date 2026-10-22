import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public class TFTPClientTCP {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT     = 12345;
    private static final int BLOCK_SIZE     = 512;
    private static final String MODE        = "octet";

    public static void main(String[] args) {
        Scanner userInput = new Scanner(System.in);

        System.out.println("TFTP Client (TCP)");
        System.out.print("Enter server host [" + DEFAULT_HOST + "]: ");
        String host = userInput.nextLine().trim();
        if (host.isEmpty()) {
            host = DEFAULT_HOST;
        }

        System.out.print("Enter server port [" + DEFAULT_PORT + "]: ");
        String portStr = userInput.nextLine().trim();
        int port = (portStr.isEmpty()) ? DEFAULT_PORT : Integer.parseInt(portStr);

        while (true) {

            System.out.println("\nOptions:");
            System.out.println("1. Get file from server (RRQ)");
            System.out.println("2. Send file to server (WRQ)");
            System.out.println("3. Exit");
            System.out.print("Enter choice: ");

            int choice;
            try {
                choice = Integer.parseInt(userInput.nextLine());
            } catch (NumberFormatException e) {
                System.out.println("Invalid choice. Please try again.");
                continue;
            }

            if (choice == 3) {
                break;
            }

            System.out.print("Enter filename: ");
            String filename = userInput.nextLine().trim();

            try {
                if (choice == 1) {
                    receiveFile(host, port, filename);
                } else if (choice == 2) {
                    sendFile(host, port, filename);
                } else {
                    System.out.println("Invalid choice. Please try again.");
                }
            } catch (IOException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }

        userInput.close();
    }


    private static void receiveFile(String host, int port, String filename) throws IOException {
        File file = new File(filename);
        if (file.exists()) throw new IOException("Local file already exists; refusing to overwrite it");
        boolean complete = false;
        boolean created = false;

        try (Socket socket = new Socket(host, port);
             DataOutputStream out  = new DataOutputStream(socket.getOutputStream());
             DataInputStream in   = new DataInputStream(socket.getInputStream())) {
            socket.setSoTimeout(15000);

            // Build and send RRQ packet: [opcode (2 bytes)] + [filename] + [0] + [mode] + [0]
            out.writeShort(1);   // RRQ opcode
            out.write(filename.getBytes(StandardCharsets.UTF_8));
            out.writeByte(0);
            out.write(MODE.getBytes());
            out.writeByte(0);

            try (OutputStream fileOut = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;

                byte[] buffer     = new byte[BLOCK_SIZE];
                int blockNumber   = 1;
                int bytesRead     = 0;

                do {
                    // Read incoming DATA packet
                    short opcode = in.readShort();

                    if (opcode == 5) { // ERROR packet
                        int errorCode = in.readShort();
                        String errorMsg = readNullTerminatedString(in);
                        throw new IOException("Server error " + errorCode + ": " + errorMsg);
                    } else if (opcode != 3) { // Not DATA opcode
                        throw new IOException("Unexpected opcode: " + opcode);
                    }

                    int receivedBlock = in.readUnsignedShort();
                    if (receivedBlock != blockNumber) {
                        throw new IOException("Invalid block number received");
                    }

                    bytesRead = in.readUnsignedShort();
                    if (bytesRead > BLOCK_SIZE) throw new IOException("Invalid block length");
                    in.readFully(buffer, 0, bytesRead);
                    if (bytesRead > 0) {
                        fileOut.write(buffer, 0, bytesRead);
                    }

                    // Send ACK for the current block
                    out.writeShort(4);   // ACK opcode
                    out.writeShort(blockNumber);

                    blockNumber = (blockNumber + 1) & 0xffff;

                } while (bytesRead == BLOCK_SIZE);

                complete = true;
                System.out.println("File received successfully");
            }
        } finally {
            if (created && !complete) Files.deleteIfExists(file.toPath());
        }
    }


    private static void sendFile(String host, int port, String filename) throws IOException {
        File file = new File(filename);
        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException(filename);
        }

        try (Socket socket = new Socket(host, port);
             DataOutputStream out  = new DataOutputStream(socket.getOutputStream());
             DataInputStream in   = new DataInputStream(socket.getInputStream());
             FileInputStream fileIn = new FileInputStream(file)) {
            socket.setSoTimeout(15000);

            // Build and send WRQ packet: [opcode (2 bytes)] + [filename] + [0] + [mode] + [0]
            out.writeShort(2);   // WRQ opcode
            out.write(filename.getBytes(StandardCharsets.UTF_8));
            out.writeByte(0);
            out.write(MODE.getBytes());
            out.writeByte(0);

            // Wait for ACK (block number 0)
            short opcode = in.readShort();
            if (opcode == 5) {
                int errorCode = in.readShort();
                String errorMsg = readNullTerminatedString(in);
                throw new IOException("Server error " + errorCode + ": " + errorMsg);
            } else if (opcode != 4) {
                throw new IOException("Unexpected opcode: " + opcode);
            }

            short ackBlock = in.readShort();
            if (ackBlock != 0) {
                throw new IOException("Invalid initial block number");
            }

            byte[] buffer = new byte[BLOCK_SIZE];
            int bytesRead;
            int blockNumber = 1;

            do {
                bytesRead = fileIn.read(buffer);
                if (bytesRead == -1) bytesRead = 0;
                // Build and send DATA packet: [opcode] + [block #] + [data]
                out.writeShort(3); // DATA opcode
                out.writeShort(blockNumber);
                out.writeShort(bytesRead);
                out.write(buffer, 0, bytesRead);

                // Wait for ACK response
                opcode = in.readShort();
                if (opcode == 5) {
                    int errorCode = in.readShort();
                    String errorMsg = readNullTerminatedString(in);
                    throw new IOException("Server error " + errorCode + ": " + errorMsg);
                } else if (opcode != 4) {
                    throw new IOException("Unexpected opcode: " + opcode);
                }

                int receivedAck = in.readUnsignedShort();
                if (receivedAck != blockNumber) {
                    throw new IOException("Invalid block number in ACK");
                }

                blockNumber = (blockNumber + 1) & 0xffff;
            } while (bytesRead == BLOCK_SIZE);

            System.out.println("File sent successfully");
        }
    }


    // Helper method to read a null-terminated string from a DataInputStream.
    private static String readNullTerminatedString(DataInputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte b;
        while ((b = in.readByte()) != 0) {
            if (baos.size() >= 1024) throw new IOException("Unterminated server message");
            baos.write(b);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
