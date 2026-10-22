import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class TFTPPacket {

    // Base packet class
    public static class Packet {
        public final short opcode;
        public Packet(short opcode) {
            this.opcode = opcode;
        }
    }

    // DATA packet (opcode 3)
    public static class DataPacket extends Packet {
        public final short blockNumber;
        public final byte[] data;

        public DataPacket(short blockNumber, byte[] data) {
            super(TFTPConstants.OP_DATA);
            this.blockNumber = blockNumber;
            this.data        = data;
        }
    }

    // ACK packet (opcode 4)
    public static class AckPacket extends Packet {
        public final short blockNumber;

        public AckPacket(short blockNumber) {
            super(TFTPConstants.OP_ACK);
            this.blockNumber = blockNumber;
        }
    }

    // ERROR packet (opcode 5)
    public static class ErrorPacket extends Packet {
        public final short errorCode;
        public final String message;

        public ErrorPacket(short errorCode, String message) {
            super(TFTPConstants.OP_ERROR);
            this.errorCode = errorCode;
            this.message   = message;
        }
    }


    ////////////// Packet Creation Methods //////////////

    public static byte[] createRRQ(String filename) {
        validateFilename(filename);
        ByteBuffer buffer = ByteBuffer.allocate(2 + filename.length() + 1 + "octet".length() + 1);
        buffer.putShort(TFTPConstants.OP_RRQ);
        buffer.put(filename.getBytes(StandardCharsets.US_ASCII));
        buffer.put((byte) 0);
        buffer.put("octet".getBytes());
        buffer.put((byte) 0);
        return buffer.array();
    }

    public static byte[] createWRQ(String filename) {
        validateFilename(filename);
        ByteBuffer buffer = ByteBuffer.allocate(2 + filename.length() + 1 + "octet".length() + 1);
        buffer.putShort(TFTPConstants.OP_WRQ);
        buffer.put(filename.getBytes(StandardCharsets.US_ASCII));
        buffer.put((byte) 0);
        buffer.put("octet".getBytes());
        buffer.put((byte) 0);
        return buffer.array();
    }

    public static byte[] createDataPacket(int blockNumber, byte[] data, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + length);
        buffer.putShort(TFTPConstants.OP_DATA);
        buffer.putShort((short) blockNumber);
        buffer.put(data, 0, length);
        return buffer.array();
    }

    public static byte[] createAckPacket(int blockNumber) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putShort(TFTPConstants.OP_ACK);
        buffer.putShort((short) blockNumber);
        return buffer.array();
    }

    ////////////// Packet Parsing Method //////////////

    public static Packet parsePacket(byte[] data, int length) {
        if (length < 4 || length > 516 || length > data.length) throw new IllegalArgumentException("Invalid packet length");
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        short opcode = buffer.getShort();

        switch (opcode) {
            case TFTPConstants.OP_RRQ:
            case TFTPConstants.OP_WRQ:
                // Skip filename and mode for RRQ/WRQ since we don't need them in responses
                return new Packet(opcode);
            case TFTPConstants.OP_DATA:
                return new DataPacket(buffer.getShort(), getRemainingData(buffer));
            case TFTPConstants.OP_ACK:
                if (length != 4) throw new IllegalArgumentException("Invalid ACK length");
                return new AckPacket(buffer.getShort());
            case TFTPConstants.OP_ERROR:
                return new ErrorPacket(buffer.getShort(), TFTPConstants.readNullTerminatedString(buffer));
            default:
                throw new IllegalArgumentException("Invalid opcode: " + opcode);
        }
    }

    private static byte[] getRemainingData(ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private static void validateFilename(String filename) {
        if (filename.isEmpty() || filename.length() > 255 || filename.indexOf(0) >= 0
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(filename)) {
            throw new IllegalArgumentException("Use a nonempty ASCII filename of at most 255 characters");
        }
    }
}
