package dev.cf.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Only emitted for an explicitly confirmed and locally valid tower placement. */
public record CFBuildRequestPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<CFBuildRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("castlefight","tower_build_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf,CFBuildRequestPayload> CODEC = StreamCodec.of(
            (buf,v)->buf.writeBytes(v.bytes()),buf->{
                if(buf.readableBytes()<17||buf.readableBytes()>90)
                    throw new IllegalArgumentException("Invalid CF build request");
                byte[] data=new byte[buf.readableBytes()];buf.readBytes(data);
                return new CFBuildRequestPayload(data);
            });
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
