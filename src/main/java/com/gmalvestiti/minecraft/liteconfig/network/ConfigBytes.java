package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.HashFunction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Arrays;

public final class ConfigBytes {

    private final byte[] bytes;

    private ConfigBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public static ConfigBytes of(byte[] bytes) {
        return new ConfigBytes(bytes);
    }

    ConfigBytes digest(HashFunction function) {
        return new ConfigBytes(function.hashBytes(bytes).asBytes());
    }

    byte[] bytes() {
        return bytes;
    }

    public int size() {
        return bytes.length;
    }

    public ByteBuf readBuffer() {
        return Unpooled.wrappedBuffer(bytes).asReadOnly();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ConfigBytes value && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
