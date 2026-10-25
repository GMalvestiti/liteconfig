package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.exception.SyncEncodingException;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;

final class ConfigSyncBuffers extends AbstractByteBufAllocator {

    static final ConfigSyncBuffers VALUES = new ConfigSyncBuffers();

    private ConfigSyncBuffers() {
        super(false);
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        requireCapacity(initialCapacity);
        return new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity) {
            @Override
            public ByteBuf capacity(int capacity) {
                requireCapacity(capacity);
                return super.capacity(capacity);
            }
        };
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        requireCapacity(initialCapacity);
        return new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity) {
            @Override
            public ByteBuf capacity(int capacity) {
                requireCapacity(capacity);
                return super.capacity(capacity);
            }
        };
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    @Override
    public int calculateNewCapacity(int minimum, int maximum) {
        requireCapacity(minimum);
        return super.calculateNewCapacity(minimum, Math.min(maximum, ConfigSyncProtocol.MAX_VALUE_BYTES));
    }

    private static void requireCapacity(int capacity) {
        if (capacity > ConfigSyncProtocol.MAX_VALUE_BYTES) {
            throw new SyncEncodingException("sync.value-size",
                "encoded value requires %d bytes; maximum is %d bytes"
                    .formatted(capacity, ConfigSyncProtocol.MAX_VALUE_BYTES));
        }
    }
}
