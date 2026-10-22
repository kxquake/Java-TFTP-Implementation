import java.nio.ByteBuffer;

public class TFTPConstants {
    // Network constants
    public static final int DEFAULT_PORT = 1069;
    public static final int BLOCK_SIZE = 512;
    public static final int TIMEOUT_MS = 3000;
    public static final int MAX_RETRIES = 5;

    // TFTP opcodes
    public static final short OP_RRQ = 1;
    public static final short OP_WRQ = 2;
    public static final short OP_DATA = 3;
    public static final short OP_ACK = 4;
    public static final short OP_ERROR = 5;

    // Helper methods
    public static String readNullTerminatedString(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        byte b;
        while (buffer.hasRemaining()) {
            b = buffer.get();
            if (b == 0) return sb.toString();
            sb.append((char) (b & 0xff));
        }
        throw new IllegalArgumentException("Unterminated packet string");
    }
}
