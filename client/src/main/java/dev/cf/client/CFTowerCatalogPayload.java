package dev.cf.client;

import dev.cf.protocol.CFBuildWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server sends a compressed snapshot on connection/race change, never on item hover. */
public record CFTowerCatalogPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<CFTowerCatalogPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("castlefight","tower_catalog_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf,CFTowerCatalogPayload> CODEC = StreamCodec.of(
            (buf,v)->buf.writeBytes(v.bytes()), buf->{
                if(buf.readableBytes()<1||buf.readableBytes()>CFBuildWire.MAX_CATALOG_BYTES)
                    throw new IllegalArgumentException("Invalid CF tower catalog length");
                byte[] data=new byte[buf.readableBytes()];buf.readBytes(data);
                return new CFTowerCatalogPayload(data);
            });
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
