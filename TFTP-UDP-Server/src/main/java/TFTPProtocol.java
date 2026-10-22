import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class TFTPProtocol {
    // Some constants
    public static final int BLOCK_SIZE = 512;
    public static final int TIMEOUT = 3000;
    public static final int MAX_RETRIES = 5;

    // Makes RRQ packet
    public static byte[] createRRQ(String filename) {
        return makeRequest((short)1, filename);
    }

    // Makes WRQ packet
    public static byte[] createWRQ(String filename) {
        return makeRequest((short)2, filename);
    }

    // Creates DATA packet
    public static byte[] createDataPacket(int block, byte[] data, int len) {
        ByteBuffer buf = ByteBuffer.allocate(4 + len);
        buf.putShort((short)3); // DATA
        buf.putShort((short)block);
        buf.put(data, 0, len);
        return buf.array();
    }

    // Creates ACK packet
    public static byte[] createAckPacket(int block) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short)4); // ACK
        buf.putShort((short)block);
        return buf.array();
    }

    // Creates ERROR packet
    public static byte[] createErrorPacket(int code, String msg) {
        byte[] msgBytes = msg.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(5 + msgBytes.length);
        buf.putShort((short)5); // ERROR
        buf.putShort((short)code);
        buf.put(msgBytes);
        buf.put((byte)0); // null byte
        return buf.array();
    }

    // Parses request packet
    public static Request parseRequest(byte[] data) {
        if (data.length < 4) throw new IllegalArgumentException("Truncated request");
        ByteBuffer buf = ByteBuffer.wrap(data);
        short opcode = buf.getShort();
        String filename = readString(buf);
        String mode = readString(buf);
        if ((opcode != 1 && opcode != 2) || filename.isEmpty() || !mode.equalsIgnoreCase("octet")) {
            throw new IllegalArgumentException("Expected an octet read/write request");
        }
        return new Request(opcode, filename);
    }

    // Parses data/ack packet
    public static DataPacket parseDataPacket(byte[] data, int len) {
        if (len < 4 || len > 516 || len > data.length) throw new IllegalArgumentException("Invalid packet length");
        ByteBuffer buf = ByteBuffer.wrap(data, 0, len);
        short op = buf.getShort();
        short block = buf.getShort();
        if ((op != 3 && op != 4) || (op == 4 && len != 4)) throw new IllegalArgumentException("Invalid DATA/ACK packet");
        byte[] packetData = new byte[len - 4];
        buf.get(packetData);
        return new DataPacket(op, block, packetData);
    }

    // Helper to make RRQ/WRQ
    private static byte[] makeRequest(short op, String filename) {
        byte[] fn = filename.getBytes();
        byte[] mode = "octet".getBytes();
        ByteBuffer buf = ByteBuffer.allocate(2 + fn.length + 1 + mode.length + 1);
        buf.putShort(op);
        buf.put(fn);
        buf.put((byte)0);
        buf.put(mode);
        buf.put((byte)0);
        return buf.array();
    }

    // Reads null-term string
    private static String readString(ByteBuffer buf) {
        StringBuilder sb = new StringBuilder();
        byte b;
        while (buf.hasRemaining()) {
            b = buf.get();
            if (b == 0) return sb.toString();
            if (b < 0) throw new IllegalArgumentException("Expected an ASCII string");
            sb.append((char)b);
        }
        throw new IllegalArgumentException("Unterminated request string");
    }

    // Simple data classes
    public static class Request {
        public final short opcode;
        public final String filename;

        public Request(short op, String fn) {
            opcode = op;
            filename = fn;
        }
    }

    public static class DataPacket {
        public final short opcode;
        public final short blockNumber;
        public final byte[] data;

        public DataPacket(short op, short block, byte[] d) {
            opcode = op;
            blockNumber = block;
            data = d;
        }
    }
}
