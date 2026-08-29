package com.gmalvestiti.minecraft.liteconfig.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;

import java.nio.charset.StandardCharsets;

public final class ConfigSyncProtocol {

    public static final int VERSION = 1;
    public static final int ENTRIES_PER_PACKET = 64;
    public static final int HASH_BYTES = 32;
    static final int MAX_ID_BYTES = 512;
    static final int MAX_PACKET_BYTES = 1_000_000;
    public static final int MAX_CONFIG_BYTES = MAX_PACKET_BYTES - 4_096;
    public static final int MAX_VALUE_BYTES = MAX_CONFIG_BYTES;
    public static final int MAX_MANIFEST_ENTRIES = 8_192;
    public static final int MAX_TRANSACTION_ENTRIES = 8_192;
    public static final int MAX_TRANSACTION_BYTES = 32 * 1_024 * 1_024;
    public static final int MAX_PENDING_TRANSACTIONS = 16;
    public static final int MAX_TRANSACTION_PACKETS =
        (MAX_TRANSACTION_ENTRIES + ENTRIES_PER_PACKET - 1) / ENTRIES_PER_PACKET;

    private ConfigSyncProtocol() {}

    public static void encodeVersion(ByteBuf buffer) {
        ByteBufCodecs.VAR_INT.encode(buffer, VERSION);
    }

    public static void decodeVersion(ByteBuf buffer) {
        int version = ByteBufCodecs.VAR_INT.decode(buffer);

        if (version != VERSION) {
            throw new DecoderException("Unsupported LiteConfig sync protocol " + version + "; expected " + VERSION);
        }
    }

    public static int readSize(ByteBuf buffer, int maximum, String label) {
        int size = ByteBufCodecs.VAR_INT.decode(buffer);

        if (size < 0 || size > maximum) {
            throw new DecoderException("Invalid LiteConfig " + label + " size " + size + "; maximum is " + maximum);
        }

        return size;
    }

    public static void requireReadable(ByteBuf buffer, int bytes, String label) {
        if (buffer.readableBytes() < bytes) {
            throw new DecoderException("Truncated LiteConfig " + label + ": expected " + bytes + " bytes, found " + buffer.readableBytes());
        }
    }

    public static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Config sync id must not be blank");
        }

        int bytes = id.getBytes(StandardCharsets.UTF_8).length;

        if (bytes > MAX_ID_BYTES) {
            throw new IllegalArgumentException("Config sync id exceeds " + MAX_ID_BYTES + " UTF-8 bytes");
        }
    }

    static int entryBytes(String id, ConfigBytes value) {
        return id.getBytes(StandardCharsets.UTF_8).length + value.size() + 10;
    }
}
