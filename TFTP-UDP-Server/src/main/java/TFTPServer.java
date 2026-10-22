import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

public class TFTPServer {
    private static final int TFTP_PORT = 1069;
    private static final int MAX_PACKET_SIZE = 516;

    public static void main(String[] args) {
        ExecutorService executor = Executors.newCachedThreadPool();
        Set<SocketAddress> active = ConcurrentHashMap.newKeySet();

        int port = args.length > 0 ? Integer.parseInt(args[0]) : TFTP_PORT;
        String bind = args.length > 1 ? args[1] : "127.0.0.1";
        try (DatagramSocket socket = new DatagramSocket(port, InetAddress.getByName(bind))) {
            System.out.println("TFTP Server started on port " + TFTP_PORT);

            while (true) {
                byte[] buffer = new byte[MAX_PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                SocketAddress peer = packet.getSocketAddress();
                // A retried initial request must not create a second writer/transfer port.
                if (active.add(peer)) {
                    executor.execute(() -> {
                        try {
                            new ClientHandler(packet).run();
                        } finally {
                            active.remove(peer);
                        }
                    });
                }


            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }
    }
}
