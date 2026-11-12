package dev.cf.client;

import dev.cf.protocol.CFMobStatsWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Minestom -> client: authoritative CF stats for the aimed entity. */
public record CFMobStatsResponsePayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<CFMobStatsResponsePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("castlefight", "mob_stats_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CFMobStatsResponsePayload> CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeBytes(packet.bytes()),
            buf -> {
                if (buf.readableBytes() < 6 || buf.readableBytes() > CFMobStatsWire.MAX_RESPONSE_BYTES)
                    throw new IllegalArgumentException("Некорректная длина характеристик КФ");
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new CFMobStatsResponsePayload(bytes);
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
