package dev.cf.client;

import dev.cf.protocol.CFShotWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client capability hello: one-byte opt-in so only Fabric receives visual event packets. */
public record CFShotHelloPayload() implements CustomPacketPayload {
    public static final Type<CFShotHelloPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("castlefight", "shot_hello_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CFShotHelloPayload> CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeBytes(CFShotWire.hello()),
            buf -> {
                if (buf.readableBytes() != 1) throw new IllegalArgumentException("Некорректный CF shot hello");
                byte[] bytes = new byte[1]; buf.readBytes(bytes);
                if (!CFShotWire.validHello(bytes)) throw new IllegalArgumentException("Неизвестный CF shot hello");
                return new CFShotHelloPayload();
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
