import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class ClientHandler implements Runnable {

    private final DatagramPacket initialPacket;

    public ClientHandler(DatagramPacket packet) {
        this.initialPacket = packet;
    }

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket()) {

            // Set a timeout for client responses.
            socket.setSoTimeout(TFTPProtocol.TIMEOUT);

            byte[] buf       = initialPacket.getData();
            InetAddress addr = initialPacket.getAddress();
            int port         = initialPacket.getPort();
            socket.connect(addr, port);

            // Parse the TFTP request.
            TFTPProtocol.Request request;
            try {
                request = TFTPProtocol.parseRequest(Arrays.copyOfRange(buf, initialPacket.getOffset(),
                        initialPacket.getOffset() + initialPacket.getLength()));
                resolveFile(request.filename);
            } catch (IllegalArgumentException | IOException ex) {
                sendError(socket, addr, port, 4, "Invalid filename or request");
                return;
            }

            if (request.opcode == 1) {   // Read request (RRQ)
                handleReadRequest(socket, addr, port, request.filename);
            } else if (request.opcode == 2) {   // Write request (WRQ)
                handleWriteRequest(socket, addr, port, request.filename);
            } else {
                sendError(socket, addr, port, 4, "Illegal TFTP operation");
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void handleReadRequest(DatagramSocket socket, InetAddress clientAddr,
                                   int clientPort, String filename) throws IOException {
        File file = resolveFile(filename);
        if (!file.isFile() || !file.canRead()) {
            sendError(socket, clientAddr, clientPort, 1, "File not found");
            return;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] fileBuffer = new byte[TFTPProtocol.BLOCK_SIZE];
            int blockNum      = 1;
            int bytesRead;

            do {
                bytesRead = fis.read(fileBuffer);
                if (bytesRead == -1) bytesRead = 0;

                byte[] dataPacket = TFTPProtocol.createDataPacket(blockNum, fileBuffer, bytesRead);

                boolean ackReceived = false;
                int retries         = 0;

                while (!ackReceived && retries < TFTPProtocol.MAX_RETRIES) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(dataPacket, dataPacket.length, clientAddr, clientPort);
                        socket.send(pkt);

                        byte[] ackBuffer = new byte[517];
                        DatagramPacket ackPkt = new DatagramPacket(ackBuffer, ackBuffer.length);
                        socket.receive(ackPkt);

                        TFTPProtocol.DataPacket ackData = TFTPProtocol.parseDataPacket(ackBuffer, ackPkt.getLength());
                        if (ackData.opcode == 4 && Short.toUnsignedInt(ackData.blockNumber) == blockNum) {
                            ackReceived = true;
                            blockNum = (blockNum + 1) & 0xffff;
                        } else {
                            retries++;
                        }
                    } catch (SocketTimeoutException | IllegalArgumentException toe) {
                        retries++;
                        System.out.println("Timeout, retrying... (" + retries + ")");
                    }
                }

                if (retries >= TFTPProtocol.MAX_RETRIES) {
                    sendError(socket, clientAddr, clientPort, 0, "Max retries reached");
                    return;
                }
            } while (bytesRead == TFTPProtocol.BLOCK_SIZE);
        }
    }


    private void handleWriteRequest(DatagramSocket socket, InetAddress clientAddr,
                                    int clientPort, String filename) throws IOException {
        File file = resolveFile(filename);
        if (file.exists()) {
            sendError(socket, clientAddr, clientPort, 6, "File already exists");
            return;
        }

        boolean complete = false;
        boolean created = false;
        try (OutputStream fos = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;

            // Send initial ACK for WRQ (block 0)
            byte[] ackPacket = TFTPProtocol.createAckPacket(0);
            socket.send(new DatagramPacket(ackPacket, ackPacket.length, clientAddr, clientPort));

            int blockNum = 1;
            boolean transferComplete = false;

            while (!transferComplete) {
                byte[] dataBuffer = new byte[TFTPProtocol.BLOCK_SIZE + 5];
                DatagramPacket dataPkt = new DatagramPacket(dataBuffer, dataBuffer.length);

                boolean dataReceived = false;
                int retries = 0;

                while (!dataReceived && retries < TFTPProtocol.MAX_RETRIES) {
                    try {
                        dataPkt.setLength(dataBuffer.length);
                        socket.receive(dataPkt);
                        TFTPProtocol.DataPacket receivedData = TFTPProtocol.parseDataPacket(dataBuffer, dataPkt.getLength());

                        if (receivedData.opcode == 3 && Short.toUnsignedInt(receivedData.blockNumber) == blockNum) {
                            dataReceived = true;
                            fos.write(receivedData.data);

                            // Send ACK for this block.
                            ackPacket = TFTPProtocol.createAckPacket(blockNum);
                            socket.send(new DatagramPacket(ackPacket, ackPacket.length, clientAddr, clientPort));

                            // If data length is less than block size, this is the last packet.
                            transferComplete = (receivedData.data.length < TFTPProtocol.BLOCK_SIZE);
                            blockNum = (blockNum + 1) & 0xffff;
                        } else {
                            retries++;
                            ackPacket = TFTPProtocol.createAckPacket((blockNum - 1) & 0xffff);
                            socket.send(new DatagramPacket(ackPacket, ackPacket.length, clientAddr, clientPort));
                        }
                    } catch (SocketTimeoutException | IllegalArgumentException ste) {
                        retries++;
                        System.out.println("Timeout, retrying... (" + retries + ")");
                        // Resend last ACK if needed.
                        ackPacket = TFTPProtocol.createAckPacket(blockNum - 1);
                        socket.send(new DatagramPacket(ackPacket, ackPacket.length, clientAddr, clientPort));
                    }
                }

                if (retries >= TFTPProtocol.MAX_RETRIES) {
                    sendError(socket, clientAddr, clientPort, 0, "Max retries reached");
                    return;
                }
            }
            complete = true;
            // ponytail: one final retransmission; extend the dally window for sustained packet loss.
            fos.flush();
            try {
                socket.setSoTimeout(TFTPProtocol.TIMEOUT * 2);
                byte[] duplicate = new byte[517];
                DatagramPacket packet = new DatagramPacket(duplicate, duplicate.length);
                socket.receive(packet);
                TFTPProtocol.DataPacket last = TFTPProtocol.parseDataPacket(duplicate, packet.getLength());
                if (last.opcode == 3 && Short.toUnsignedInt(last.blockNumber) == ((blockNum - 1) & 0xffff)) {
                    socket.send(new DatagramPacket(ackPacket, ackPacket.length, clientAddr, clientPort));
                }
            } catch (SocketTimeoutException | IllegalArgumentException ignored) {
                // The completed file is valid even if no duplicate packet arrives.
            }
        } finally {
            if (created && !complete) Files.deleteIfExists(file.toPath());
        }
    }

    private static File resolveFile(String filename) throws IOException {
        File root = new File(".").getCanonicalFile();
        File file = new File(root, filename).getCanonicalFile();
        if (filename.isEmpty() || filename.equals(".") || filename.equals("..") || filename.length() > 255
                || filename.contains("/") || filename.contains("\\") || filename.contains(":")
                || !root.equals(file.getParentFile())) {
            throw new IOException("Filename must stay in the server's working directory");
        }
        return file;
    }


    private void sendError(DatagramSocket socket, InetAddress clientAddr,
                           int clientPort, int errorCode, String message) throws IOException {
        byte[] errorPacket = TFTPProtocol.createErrorPacket(errorCode, message);
        socket.send(new DatagramPacket(errorPacket, errorPacket.length, clientAddr, clientPort));
    }
}
