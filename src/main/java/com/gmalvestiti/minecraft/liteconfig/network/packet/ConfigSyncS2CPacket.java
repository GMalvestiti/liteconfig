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

public record ConfigSyncS2CPacket(
    long transactionId,
    boolean last,
    boolean joining,
    Map<String, ConfigBytes> configs
) implements CustomPacketPayload {

    public ConfigSyncS2CPacket {
        configs = ConfigPayloads.immutableEntries(configs);
    }

    public static final Identifier ID = Identifier.fromNamespaceAndPath(LiteConfigCommon.MOD_ID, "config_sync");

    public static final Type<ConfigSyncS2CPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<ByteBuf, ConfigSyncS2CPacket> STREAM_CODEC =
        StreamCodec.of(ConfigSyncS2CPacket::encode, ConfigSyncS2CPacket::decode);

    private static void encode(ByteBuf buffer, ConfigSyncS2CPacket payload) {
        buffer.writeLong(payload.transactionId);
        buffer.writeBoolean(payload.last);
        buffer.writeBoolean(payload.joining);

        ConfigPayloads.encodeEntries(buffer, payload.configs);
    }

    private static ConfigSyncS2CPacket decode(ByteBuf buffer) {
        ConfigSyncProtocol.requireReadable(buffer, Long.BYTES + 2, "transaction id, completion and join flags");

        return new ConfigSyncS2CPacket(
            buffer.readLong(),
            buffer.readBoolean(),
            buffer.readBoolean(),
            ConfigPayloads.decodeEntries(buffer));
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
