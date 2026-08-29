package com.gmalvestiti.minecraft.liteconfig.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Arrays;

public final class ConfigBytes {

    private final byte[] bytes;
    private final int hashCode;

    private ConfigBytes(byte[] bytes, boolean copy) {
        this.bytes = copy ? bytes.clone() : bytes;
        this.hashCode = Arrays.hashCode(this.bytes);
    }

    public static ConfigBytes of(byte[] bytes) {
        return new ConfigBytes(bytes, true);
    }

    public static ConfigBytes trusted(byte[] bytes) {
        return new ConfigBytes(bytes, false);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    public void writeTo(ByteBuf buffer) {
        buffer.writeBytes(bytes);
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
        return hashCode;
    }
}
