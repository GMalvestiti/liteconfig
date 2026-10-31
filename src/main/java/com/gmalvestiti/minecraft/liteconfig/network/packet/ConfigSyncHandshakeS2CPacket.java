package com.gmalvestiti.minecraft.liteconfig.network.packet;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigBytes;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigPayloads;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.UUID;

public record ConfigSyncHandshakeS2CPacket(
    boolean joining,
    UUID serverId,
    Map<String, ConfigBytes> hashes
) implements CustomPacketPayload {

    private static final int HEADER_BYTES = 1 + 2 * Long.BYTES;

    public ConfigSyncHandshakeS2CPacket {
        hashes = ConfigPayloads.immutableHashes(hashes);
    }

    public static final Identifier ID = Identifier.fromNamespaceAndPath(LiteConfigCommon.MOD_ID, "config_sync_handshake");

    public static final Type<ConfigSyncHandshakeS2CPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<ByteBuf, ConfigSyncHandshakeS2CPacket> STREAM_CODEC =
        StreamCodec.of(ConfigSyncHandshakeS2CPacket::encode, ConfigSyncHandshakeS2CPacket::decode);

    private static void encode(ByteBuf buffer, ConfigSyncHandshakeS2CPacket payload) {
        buffer.writeBoolean(payload.joining);
        buffer.writeLong(payload.serverId.getMostSignificantBits());
        buffer.writeLong(payload.serverId.getLeastSignificantBits());
        ConfigPayloads.encodeEntries(buffer, payload.hashes);
    }

    private static ConfigSyncHandshakeS2CPacket decode(ByteBuf buffer) {
        ConfigSyncProtocol.requireReadable(buffer, HEADER_BYTES, "handshake header");
        boolean joining = buffer.readBoolean();
        UUID serverId = new UUID(buffer.readLong(), buffer.readLong());
        return new ConfigSyncHandshakeS2CPacket(joining, serverId, ConfigPayloads.decodeHashes(buffer));
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
