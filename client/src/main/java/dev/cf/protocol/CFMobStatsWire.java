package dev.cf.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Castle Fight hovering-mob protocol v1. This file MUST remain byte-for-byte
 * identical in server/ and client/. Never trust the client for combat stats.
 * Client sends only an entity ID; server validates the target and supplies stats.
 * Sending -1 clears the active target. One server response contains at most 1 KB.
 */
public final class CFMobStatsWire {
    public static final String REQUEST_CHANNEL = "castlefight:mob_query_v1";
    public static final String RESPONSE_CHANNEL = "castlefight:mob_stats_v1";
    public static final int VERSION = 1;
    public static final int MAX_RESPONSE_BYTES = 1024;
    private static final int DATA = 1;
    private static final int CLEAR = 0;
    private CFMobStatsWire() { }

    public record Stats(int entityId, String name, String team, int lane,
                        int hp10, int maxHp10, int damage10, String damageType,
                        String armorType, int critDamage10, int critChance,
                        double speed, double attackRange, long cooldownMs) {
        public Stats {
            if (entityId < 0 || name == null || name.isBlank() || name.length() > 80
                    || !("blue".equals(team) || "red".equals(team)) || lane < 0 || lane > 4
                    || maxHp10 < 1 || maxHp10 > 100_000 || hp10 < 0 || hp10 > maxHp10
                    || damage10 < 0 || damage10 > 100_000 || critDamage10 < 0
                    || critDamage10 > 100_000 || critChance < 0 || critChance > 100
                    || damageType == null || damageType.length() > 32
                    || armorType == null || armorType.length() > 32
                    || !Double.isFinite(speed) || speed < 0 || speed > 10
                    || !Double.isFinite(attackRange) || attackRange < 0 || attackRange > 256
                    || cooldownMs < 0 || cooldownMs > 600_000)
                throw new IllegalArgumentException("Неверные характеристики моба КФ");
        }
        public double healthRatio() { return (double) hp10 / maxHp10; }
    }

    public record Reply(int entityId, Stats stats) {
        public Reply {
            if (entityId < -1 || (stats != null && stats.entityId() != entityId))
                throw new IllegalArgumentException("Несовпадение ID моба");
        }
    }

    /** Client -> server. Exactly five bytes; no client-supplied statistics. */
    public static byte[] query(int entityId) {
        if (entityId < -1) throw new IllegalArgumentException("Bad entity ID");
        return new byte[] {(byte) VERSION, (byte)(entityId >>> 24), (byte)(entityId >>> 16),
                (byte)(entityId >>> 8), (byte) entityId};
    }

    public static int readQuery(byte[] payload) throws IOException {
        if (payload == null || payload.length != 5 || (payload[0] & 255) != VERSION)
            throw new IOException("Неподдерживаемый запрос моба КФ");
        int id = ((payload[1] & 255) << 24) | ((payload[2] & 255) << 16)
                | ((payload[3] & 255) << 8) | (payload[4] & 255);
        if (id < -1) throw new IOException("Неверный ID цели");
        return id;
    }

    public static byte[] clear(int requestedId) throws IOException {
        return write(new Reply(requestedId, null));
    }

    /** Server -> client. All fields come from the authoritative CFUnit. */
    public static byte[] write(Reply reply) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(reply.stats() == null ? CLEAR : DATA);
            out.writeInt(reply.entityId());
            if (reply.stats() != null) {
                Stats s = reply.stats();
                out.writeUTF(s.name());
                out.writeUTF(s.team());
                out.writeByte(s.lane());
                out.writeInt(s.hp10());
                out.writeInt(s.maxHp10());
                out.writeInt(s.damage10());
                out.writeUTF(s.damageType());
                out.writeUTF(s.armorType());
                out.writeInt(s.critDamage10());
                out.writeByte(s.critChance());
                out.writeDouble(s.speed());
                out.writeDouble(s.attackRange());
                out.writeLong(s.cooldownMs());
            }
        }
        if (bytes.size() > MAX_RESPONSE_BYTES) throw new IOException("Stats packet too large");
        return bytes.toByteArray();
    }

    public static Reply readReply(byte[] payload) throws IOException {
        if (payload == null || payload.length < 6 || payload.length > MAX_RESPONSE_BYTES)
            throw new IOException("Неверный размер характеристик КФ");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readUnsignedByte() != VERSION) throw new IOException("Неверная версия данных КФ");
            int flag = in.readUnsignedByte();
            if (flag != CLEAR && flag != DATA) throw new IOException("Неверный тип ответа КФ");
            int entityId = in.readInt();
            if (entityId < -1) throw new IOException("Неверный ID цели");
            Stats data = null;
            if (flag == DATA) {
                data = new Stats(entityId, in.readUTF(), in.readUTF(), in.readUnsignedByte(),
                        in.readInt(), in.readInt(), in.readInt(), in.readUTF(), in.readUTF(),
                        in.readInt(), in.readUnsignedByte(), in.readDouble(), in.readDouble(), in.readLong());
            }
            if (in.available() != 0) throw new IOException("Лишние байты характеристик КФ");
            return new Reply(entityId, data);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректные характеристики КФ", e);
        }
    }
}
