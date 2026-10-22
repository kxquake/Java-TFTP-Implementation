import java.io.IOException;
import java.util.Scanner;

public class TFTPClient {

    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);

        System.out.println("TFTP Client");
        System.out.println("1. Download file from server");
        System.out.println("2. Upload file to server");
        System.out.print("Enter choice: ");
        int choice = input.nextInt();
        input.nextLine();  // Consume the newline left by nextInt()

        System.out.print("Server address [127.0.0.1]: ");
        String serverAddr = input.nextLine();
        if (serverAddr.isEmpty()) {
            serverAddr = "127.0.0.1";
        }

        System.out.print("Server port [1069]: ");
        String portInput = input.nextLine();
        int serverPort = portInput.isEmpty() ? TFTPConstants.DEFAULT_PORT : Integer.parseInt(portInput);

        System.out.print("Filename: ");
        String filename = input.nextLine();

        TFTPTransfer transfer = null;
        try {
            transfer = new TFTPTransfer(serverAddr, serverPort);

            switch (choice) {
                case 1:
                    transfer.downloadFile(filename);
                    break;
                case 2:
                    transfer.uploadFile(filename);
                    break;
                default:
                    System.out.println("Invalid choice");
            }
        } catch (IOException ioe) {
            System.err.println("Error: " + ioe.getMessage());
        } finally {
            if (transfer != null) {
                transfer.close();
            }
            input.close();
        }
    }
}
