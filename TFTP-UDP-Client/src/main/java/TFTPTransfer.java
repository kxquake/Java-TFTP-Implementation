import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public class TFTPTransfer {
    private final DatagramSocket socket;
    private final InetAddress serverAddr;
    // The server port becomes mutable after receiving the first response
    private int serverPort;
    // Flag to indicate that the server's ephemeral port has been recorded
    private boolean portUpdated = false;
    private byte[] lastSent;

    public TFTPTransfer(String server, int port) throws IOException {
        this.socket = new DatagramSocket();
        // Set socket timeout as per configuration
        this.socket.setSoTimeout(TFTPConstants.TIMEOUT_MS);
        this.serverAddr = InetAddress.getByName(server);
        // Initially, we use the well-known port specified (default: 1069)
        this.serverPort = port;
    }

    // Download file from the TFTP server
    public void downloadFile(String filename) throws IOException {
        File outputFile = new File(filename);
        if (outputFile.exists()) {
            throw new IOException("Local file already exists; refusing to overwrite it");
        }
        byte[] rrq = TFTPPacket.createRRQ(filename);
        sendPacket(rrq);

        boolean complete = false;
        boolean created = false;
        try (OutputStream fos = Files.newOutputStream(outputFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            int blockNumber = 1;
            boolean isLastPacket = false;

            while (!isLastPacket) {
                // Receive the data packet for the expected block
                TFTPPacket.DataPacket dataPacket = receiveDataPacket(blockNumber);
                fos.write(dataPacket.data);

                // Send ACK for the received block
                sendAck(blockNumber);
                // A packet with fewer bytes than BLOCK_SIZE signals the end of the transfer
                isLastPacket = (dataPacket.data.length < TFTPConstants.BLOCK_SIZE);
                blockNumber = (blockNumber + 1) & 0xffff;
            }
            complete = true;
            // ponytail: one final retransmission; extend the dally window for sustained packet loss.
            try {
                socket.setSoTimeout(TFTPConstants.TIMEOUT_MS * 2);
                byte[] duplicate = new byte[517];
                DatagramPacket packet = new DatagramPacket(duplicate, duplicate.length);
                socket.receive(packet);
                TFTPPacket.Packet last = TFTPPacket.parsePacket(duplicate, packet.getLength());
                if (last instanceof TFTPPacket.DataPacket
                        && Short.toUnsignedInt(((TFTPPacket.DataPacket) last).blockNumber) == ((blockNumber - 1) & 0xffff)) {
                    sendAck((blockNumber - 1) & 0xffff);
                }
            } catch (SocketTimeoutException | IllegalArgumentException ignored) {
                // No final retransmission is also a successful download.
            }
            System.out.println("Download complete!");
        } catch (Exception e) {
            System.err.println("Download failed: " + e.getMessage());
            throw e;
        } finally {
            if (created && !complete) Files.deleteIfExists(outputFile.toPath());
        }
    }

    // Upload file to the TFTP server
    public void uploadFile(String filename) throws IOException {
        File file = new File(filename);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filename);
        }
        byte[] wrq = TFTPPacket.createWRQ(filename);
        sendPacket(wrq);

        // Wait for the ACK of the WRQ (should have block number 0)
        receiveAckPacket(0);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[TFTPConstants.BLOCK_SIZE];
            int blockNumber = 1;
            int bytesRead;
            // Flag to check if the last block had fewer bytes than BLOCK_SIZE
            boolean lastBlockIncomplete = false;

            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] dataPkt = TFTPPacket.createDataPacket(blockNumber, buffer, bytesRead);
                sendPacket(dataPkt);
                receiveAckPacket(blockNumber);
                blockNumber = (blockNumber + 1) & 0xffff;
                lastBlockIncomplete = (bytesRead < TFTPConstants.BLOCK_SIZE);
            }

            // If file size is exactly a multiple of BLOCK_SIZE, send a final empty data packet.
            if (!lastBlockIncomplete) {
                byte[] finalData = TFTPPacket.createDataPacket(blockNumber, new byte[0], 0);
                sendPacket(finalData);
                receiveAckPacket(blockNumber);
            }
            System.out.println("Upload complete!");
        }
    }

    // Helper method: Sends a packet using the (possibly updated) server port
    private void sendPacket(byte[] data) throws IOException {
        lastSent = data;
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverPort);
        socket.send(packet);
    }

    // Receives a DATA packet and ensures the transfer comes from the correct port.
    // Also, if this is the first response, update the serverPort.
    private TFTPPacket.DataPacket receiveDataPacket(int expectedBlock) throws IOException {
        byte[] buf = new byte[TFTPConstants.BLOCK_SIZE + 5];
        for (int attempt = 0; attempt < TFTPConstants.MAX_RETRIES; attempt++) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                if (!packet.getAddress().equals(serverAddr) || (portUpdated && packet.getPort() != serverPort)) continue;

                TFTPPacket.Packet p = TFTPPacket.parsePacket(packet.getData(), packet.getLength());
                if (p instanceof TFTPPacket.DataPacket) {
                    TFTPPacket.DataPacket dataPacket = (TFTPPacket.DataPacket) p;
                    if (Short.toUnsignedInt(dataPacket.blockNumber) == expectedBlock) {
                        recordPeer(packet);
                        return dataPacket;
                    } else if (Short.toUnsignedInt(dataPacket.blockNumber) == ((expectedBlock - 1) & 0xffff)) {
                        sendAck((expectedBlock - 1) & 0xffff);
                    }
                } else if (p instanceof TFTPPacket.ErrorPacket) {
                    throw new IOException("Server error: " + ((TFTPPacket.ErrorPacket) p).message);
                }
            } catch (SocketTimeoutException ste) {
                System.out.println("Timeout waiting for data packet, retry " + (attempt + 1));
                sendPacket(lastSent);
            } catch (IllegalArgumentException malformed) {
                // Ignore malformed traffic rather than accepting it as the peer.
            }
        }
        throw new IOException("Max retries reached while waiting for data packet (block " + expectedBlock + ")");
    }

    // Receives an ACK packet and updates the server port if needed.
    private TFTPPacket.AckPacket receiveAckPacket(int expectedBlock) throws IOException {
        byte[] buf = new byte[517];  // Extra byte makes oversized packets detectable.
        for (int attempt = 0; attempt < TFTPConstants.MAX_RETRIES; attempt++) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                if (!packet.getAddress().equals(serverAddr) || (portUpdated && packet.getPort() != serverPort)) continue;

                TFTPPacket.Packet p = TFTPPacket.parsePacket(packet.getData(), packet.getLength());
                if (p instanceof TFTPPacket.AckPacket) {
                    TFTPPacket.AckPacket ack = (TFTPPacket.AckPacket) p;
                    if (Short.toUnsignedInt(ack.blockNumber) == expectedBlock) {
                        recordPeer(packet);
                        return ack;
                    }
                } else if (p instanceof TFTPPacket.ErrorPacket) {
                    throw new IOException("Server error: " + ((TFTPPacket.ErrorPacket) p).message);
                }
            } catch (SocketTimeoutException ste) {
                System.out.println("Timeout waiting for ACK, retry " + (attempt + 1));
                sendPacket(lastSent);
            } catch (IllegalArgumentException malformed) {
                // Ignore malformed traffic and keep the current transfer state.
            }
        }
        throw new IOException("Max retries reached while waiting for ACK (block " + expectedBlock + ")");
    }

    // Sends an ACK packet for the given block number
    private void recordPeer(DatagramPacket packet) {
        if (!portUpdated) {
            serverPort = packet.getPort();
            portUpdated = true;
            socket.connect(serverAddr, serverPort);
        }
    }

    private void sendAck(int blockNumber) throws IOException {
        byte[] ack = TFTPPacket.createAckPacket(blockNumber);
        sendPacket(ack);
    }

    public void close() {
        socket.close();
    }
}
