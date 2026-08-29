package com.gmalvestiti.minecraft.liteconfig.network.packet;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigPayloads;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol.ENTRIES_PER_PACKET;

public record ConfigSyncRequestC2SPacket(Set<String> configIds) implements CustomPacketPayload {

    public ConfigSyncRequestC2SPacket {
        configIds = ConfigPayloads.immutableIds(configIds);
    }

    public static final Identifier ID = Identifier.fromNamespaceAndPath(LiteConfigCommon.MOD_ID, "config_sync_request");

    public static final Type<ConfigSyncRequestC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<ByteBuf, ConfigSyncRequestC2SPacket> STREAM_CODEC =
        StreamCodec.of(ConfigSyncRequestC2SPacket::encode, ConfigSyncRequestC2SPacket::decode);

    private static void encode(ByteBuf buffer, ConfigSyncRequestC2SPacket payload) {
        ConfigSyncProtocol.encodeVersion(buffer);

        ByteBufCodecs.VAR_INT.encode(buffer, payload.configIds.size());
        payload.configIds.forEach(id -> ByteBufCodecs.STRING_UTF8.encode(buffer, id));
    }

    private static ConfigSyncRequestC2SPacket decode(ByteBuf buffer) {
        ConfigSyncProtocol.decodeVersion(buffer);

        int count = ConfigSyncProtocol.readSize(buffer, ENTRIES_PER_PACKET, "request id count");

        Set<String> ids = new LinkedHashSet<>(count);
        for (int index = 0; index < count; index++) {
            String id = ByteBufCodecs.STRING_UTF8.decode(buffer);

            try {
                ConfigSyncProtocol.validateId(id);
            } catch (IllegalArgumentException failure) {
                throw new DecoderException(failure.getMessage(), failure);
            }

            if (!ids.add(id)) {
                throw new DecoderException("Duplicate config sync id " + id);
            }
        }

        return new ConfigSyncRequestC2SPacket(ids);
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
