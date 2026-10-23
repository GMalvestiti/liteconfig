package com.gmalvestiti.minecraft.liteconfig.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.lang.reflect.Array;

final class ArrayValueCodecs {

    private ArrayValueCodecs() {}

    static StreamCodec<ByteBuf, Object> array(
        Class<?> componentType,
        StreamCodec<ByteBuf, Object> elementCodec,
        boolean customElement
    ) {
        return StreamCodec.of((buffer, values) -> {
            int length = Array.getLength(values);

            SyncCodecBounds.requireCollectionSize(length, "array");
            ByteBufCodecs.VAR_INT.encode(buffer, length);

            for (int index = 0; index < length; index++) {
                elementCodec.encode(buffer, Array.get(values, index));
            }
        }, buffer -> {

            int length = SyncCodecBounds.readCollectionSize(buffer, "array");
            int minimumBytes = customElement ? 0
                : componentType.isPrimitive() ? primitiveWidth(componentType) : 1;

            if (buffer.readableBytes() < length * minimumBytes) {
                throw new DecoderException("Truncated synced array");
            }

            Object values = Array.newInstance(componentType, length);
            for (int index = 0; index < length; index++) {
                Array.set(values, index, elementCodec.decode(buffer));
            }

            return values;
        });
    }

    private static int primitiveWidth(Class<?> type) {
        if (type == long.class || type == double.class) {
            return Long.BYTES;
        }

        if (type == int.class || type == float.class) {
            return Integer.BYTES;
        }

        return type == short.class ? Short.BYTES : 1;
    }
}
