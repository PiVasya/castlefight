package dev.cf.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * V10.6 compact Fabric-only visual launch signal.
 * Exactly 9 payload bytes: shooter entity id (4), target entity id (4), style id (1).
 * Coordinates, speed, animations, particles, textures, damage and paths are NOT sent.
 * All rendering logic lives in the client. Channel v2 fixes the wire version.
 * Both copies of this class must stay byte-identical.
 */
public final class CFShotWire {
    public static final String HELLO_CHANNEL = "castlefight:shot_hello_v2";
    public static final String VISUAL_CHANNEL = "castlefight:shot_visual_v2";
    public static final int VERSION = 2;
    public static final int PACKET_BYTES = 9;
    public static final int MAX_STYLE_ID = 255;

    public record Shot(int shooterId, int targetId, int styleId) { }

    public static byte[] hello() { return new byte[] {(byte) VERSION}; }

    public static boolean validHello(byte[] bytes) {
        return bytes != null && bytes.length == 1 && Byte.toUnsignedInt(bytes[0]) == VERSION;
    }

    public static byte[] write(Shot shot) throws IOException {
        validate(shot);
        return ByteBuffer.allocate(PACKET_BYTES).putInt(shot.shooterId())
                .putInt(shot.targetId()).put((byte) shot.styleId()).array();
    }

    public static Shot read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != PACKET_BYTES)
            throw new IOException("Invalid Castle Fight shot length");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        Shot shot = new Shot(buffer.getInt(), buffer.getInt(), Byte.toUnsignedInt(buffer.get()));
        validate(shot);
        return shot;
    }

    private static void validate(Shot shot) throws IOException {
        if (shot == null || shot.shooterId() < 1 || shot.targetId() < 1
                || shot.shooterId() == shot.targetId()
                || shot.styleId() < 0 || shot.styleId() > MAX_STYLE_ID)
            throw new IOException("Invalid Castle Fight shooter/target/style id");
    }

    private CFShotWire() { }
}
