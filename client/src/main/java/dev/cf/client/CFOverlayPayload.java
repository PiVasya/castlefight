package dev.cf.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One fragment of the server's compressed overlay. The server is Minestom. */
public record CFOverlayPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<CFOverlayPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("castlefight", "overlay_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CFOverlayPayload> CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeBytes(packet.bytes()),
            buf -> {
                if (buf.readableBytes() < 1 || buf.readableBytes() > 16_100)
                    throw new IllegalArgumentException("Некорректная длина пакета КФ");
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new CFOverlayPayload(data);
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
