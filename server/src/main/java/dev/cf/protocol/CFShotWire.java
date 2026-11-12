package dev.cf.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Castle Fight V10.5. One bounded, immutable visual shot packet.
 * Only the server decides whether the attack succeeds or deals damage.
 * The client merely animates a straight arrow from the launch snapshot.
 * Identical source is compiled into server and client, checked by build.sh.
 */
public final class CFShotWire {
    public static final String HELLO_CHANNEL = "castlefight:shot_hello_v1";
    public static final String VISUAL_CHANNEL = "castlefight:shot_visual_v1";
    public static final int VERSION = 1;
    public static final int PACKET_BYTES = 35;
    private static final int MAGIC = 0x43465331; // CFS1

    public record Shot(int entityId, boolean blue,
                       float fromX, float fromY, float fromZ,
                       float toX, float toY, float toZ,
                       int flightTicks) { }

    public static byte[] hello() { return new byte[]{0x43, (byte) VERSION}; }

    public static boolean validHello(byte[] bytes) {
        return bytes != null && bytes.length == 2 && bytes[0] == 0x43 && bytes[1] == VERSION;
    }

    public static byte[] write(Shot shot) throws IOException {
        validate(shot);
        return ByteBuffer.allocate(PACKET_BYTES).putInt(MAGIC).put((byte) VERSION)
                .putInt(shot.entityId()).put((byte) (shot.blue() ? 1 : 0))
                .putFloat(shot.fromX()).putFloat(shot.fromY()).putFloat(shot.fromZ())
                .putFloat(shot.toX()).putFloat(shot.toY()).putFloat(shot.toZ())
                .put((byte) shot.flightTicks()).array();
    }

    public static Shot read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != PACKET_BYTES)
            throw new IOException("Неверная длина визуального выстрела");
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (buf.getInt() != MAGIC || Byte.toUnsignedInt(buf.get()) != VERSION)
            throw new IOException("Неизвестная версия визуального выстрела");
        int id = buf.getInt();
        int team = Byte.toUnsignedInt(buf.get());
        if (team > 1) throw new IOException("Неизвестная команда выстрела");
        Shot shot = new Shot(id, team == 1, buf.getFloat(), buf.getFloat(), buf.getFloat(),
                buf.getFloat(), buf.getFloat(), buf.getFloat(), Byte.toUnsignedInt(buf.get()));
        validate(shot);
        return shot;
    }

    private static void validate(Shot shot) throws IOException {
        if (shot == null || shot.entityId() < 1 || shot.flightTicks() < 1 || shot.flightTicks() > 60)
            throw new IOException("Недопустимый ID или время полёта стрелы");
        float[] xyz = {shot.fromX(),shot.fromY(),shot.fromZ(),shot.toX(),shot.toY(),shot.toZ()};
        for (float v : xyz)
            if (!Float.isFinite(v) || Math.abs(v) > 30_000_000)
                throw new IOException("Некорректные координаты стрелы");
        double dx=shot.toX()-shot.fromX(), dy=shot.toY()-shot.fromY(), dz=shot.toZ()-shot.fromZ();
        double squared = dx*dx + dy*dy + dz*dz;
        if (squared < .0001 || squared > 96.0*96.0)
            throw new IOException("Недопустимая длина полёта стрелы");
    }

    private CFShotWire() { }
}
