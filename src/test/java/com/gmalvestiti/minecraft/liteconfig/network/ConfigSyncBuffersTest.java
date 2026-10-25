package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.exception.SyncEncodingException;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSyncBuffersTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsExactLimitWithoutGrowingPastIt(boolean direct) {
        ByteBuf buffer = direct ? ConfigSyncBuffers.VALUES.directBuffer() : ConfigSyncBuffers.VALUES.buffer();
        try {
            buffer.writeZero(ConfigSyncProtocol.MAX_VALUE_BYTES);
            assertEquals(ConfigSyncProtocol.MAX_VALUE_BYTES, buffer.writerIndex());
            assertEquals(ConfigSyncProtocol.MAX_VALUE_BYTES, buffer.capacity());
            SyncEncodingException failure = assertThrows(SyncEncodingException.class, () -> buffer.writeByte(0));
            assertEquals("sync.value-size", failure.rule());
            assertEquals(ConfigSyncProtocol.MAX_VALUE_BYTES, buffer.capacity());
            assertEquals(ConfigSyncProtocol.MAX_VALUE_BYTES, buffer.writerIndex());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedBulkWriteBeforeAllocation() {
        ByteBuf buffer = ConfigSyncBuffers.VALUES.buffer();
        try {
            int capacity = buffer.capacity();
            assertThrows(SyncEncodingException.class,
                () -> buffer.writeZero(ConfigSyncProtocol.MAX_VALUE_BYTES + 1));
            assertEquals(capacity, buffer.capacity());
            assertEquals(0, buffer.writerIndex());
        } finally {
            buffer.release();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsOversizedInitialAndExplicitCapacities(boolean direct) {
        int oversized = ConfigSyncProtocol.MAX_VALUE_BYTES + 1;
        assertThrows(SyncEncodingException.class, () -> {
            if (direct) {
                ConfigSyncBuffers.VALUES.directBuffer(oversized);
            } else {
                ConfigSyncBuffers.VALUES.buffer(oversized);
            }
        });
        ByteBuf buffer = direct ? ConfigSyncBuffers.VALUES.directBuffer() : ConfigSyncBuffers.VALUES.buffer();
        try {
            int capacity = buffer.capacity();
            assertThrows(SyncEncodingException.class, () -> buffer.capacity(oversized));
            assertEquals(capacity, buffer.capacity());
        } finally {
            buffer.release();
        }
    }
}
