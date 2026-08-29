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

public record ConfigSyncHandshakeS2CPacket(Map<String, ConfigBytes> hashes) implements CustomPacketPayload {

    public ConfigSyncHandshakeS2CPacket {
        hashes = ConfigPayloads.immutableHashes(hashes);
    }

    public static final Identifier ID = Identifier.fromNamespaceAndPath(LiteConfigCommon.MOD_ID, "config_sync_handshake");

    public static final Type<ConfigSyncHandshakeS2CPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<ByteBuf, ConfigSyncHandshakeS2CPacket> STREAM_CODEC =
        StreamCodec.of(ConfigSyncHandshakeS2CPacket::encode, ConfigSyncHandshakeS2CPacket::decode);

    private static void encode(ByteBuf buffer, ConfigSyncHandshakeS2CPacket payload) {
        ConfigSyncProtocol.encodeVersion(buffer);
        ConfigPayloads.encodeEntries(buffer, payload.hashes);
    }

    private static ConfigSyncHandshakeS2CPacket decode(ByteBuf buffer) {
        ConfigSyncProtocol.decodeVersion(buffer);
        return new ConfigSyncHandshakeS2CPacket(ConfigPayloads.decodeHashes(buffer));
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
