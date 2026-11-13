package dev.cf.client;

import dev.cf.protocol.CFShotWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> Fabric: compact shot IDs, NOT damage or target authority. */
public record CFShotVisualPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<CFShotVisualPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("castlefight", "shot_visual_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CFShotVisualPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.bytes()),
            buf -> {
                if (buf.readableBytes() != CFShotWire.PACKET_BYTES)
                    throw new IllegalArgumentException("Неверная длина стрелы КФ");
                byte[] bytes = new byte[CFShotWire.PACKET_BYTES];
                buf.readBytes(bytes);
                return new CFShotVisualPayload(bytes);
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
