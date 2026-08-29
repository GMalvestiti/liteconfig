package com.gmalvestiti.minecraft.liteconfig.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;

final class SyncCodecBounds {

    private static final int MAX_COLLECTION_ENTRIES = 16_384;

    private SyncCodecBounds() {}

    static int readCollectionSize(ByteBuf buffer, String kind) {
        int size = ByteBufCodecs.VAR_INT.decode(buffer);
        if (size < 0 || size > MAX_COLLECTION_ENTRIES) {
            throw new DecoderException("Invalid synced " + kind + " size " + size + "; maximum is " + MAX_COLLECTION_ENTRIES);
        }
        return size;
    }

    static void requireCollectionSize(int size, String kind) {
        if (size > MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException("Synced " + kind + " exceeds " + MAX_COLLECTION_ENTRIES + " entries");
        }
    }

    static void requireLongArrayReadable(ByteBuf buffer, int bytes) {
        if (buffer.readableBytes() < bytes) {
            throw new DecoderException("Truncated synced long array: expected " + bytes + " bytes, found " + buffer.readableBytes());
        }
    }
}
