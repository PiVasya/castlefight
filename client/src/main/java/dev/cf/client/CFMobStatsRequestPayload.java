package dev.cf.client;

import dev.cf.protocol.CFMobStatsWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Minestom: just entity ID. */
public record CFMobStatsRequestPayload(int entityId) implements CustomPacketPayload {
    public static final Type<CFMobStatsRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("castlefight", "mob_query_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CFMobStatsRequestPayload> CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeBytes(CFMobStatsWire.query(packet.entityId())),
            buf -> {
                if (buf.readableBytes() != 5) throw new IllegalArgumentException("Неверный размер запроса КФ");
                byte[] bytes = new byte[5];
                buf.readBytes(bytes);
                try { return new CFMobStatsRequestPayload(CFMobStatsWire.readQuery(bytes)); }
                catch (java.io.IOException e) { throw new IllegalArgumentException(e); }
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
