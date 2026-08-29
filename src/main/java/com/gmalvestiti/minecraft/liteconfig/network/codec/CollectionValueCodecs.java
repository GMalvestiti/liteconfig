package com.gmalvestiti.minecraft.liteconfig.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CollectionValueCodecs {

    private CollectionValueCodecs() {}

    static StreamCodec<ByteBuf, List<Object>> list(
        StreamCodec<ByteBuf, Object> elementCodec
    ) {
        return StreamCodec.of((buffer, values) -> {
            SyncCodecBounds.requireCollectionSize(values.size(), "list");

            ByteBufCodecs.VAR_INT.encode(buffer, values.size());

            values.forEach(value -> elementCodec.encode(buffer, value));
        }, buffer -> {
            int size = SyncCodecBounds.readCollectionSize(buffer, "list");

            List<Object> values = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                values.add(elementCodec.decode(buffer));
            }

            return values;
        });
    }

    static StreamCodec<ByteBuf, Map<Object, Object>> map(
        StreamCodec<ByteBuf, Object> keyCodec,
        StreamCodec<ByteBuf, Object> valueCodec
    ) {
        return StreamCodec.of((buffer, values) -> {
            SyncCodecBounds.requireCollectionSize(values.size(), "map");

            ByteBufCodecs.VAR_INT.encode(buffer, values.size());

            values.forEach((key, value) -> {
                keyCodec.encode(buffer, key);
                valueCodec.encode(buffer, value);
            });
        }, buffer -> {
            int size = SyncCodecBounds.readCollectionSize(buffer, "map");

            Map<Object, Object> values = LinkedHashMap.newLinkedHashMap(size);
            for (int index = 0; index < size; index++) {

                Object key = keyCodec.decode(buffer);
                Object value = valueCodec.decode(buffer);

                if (values.containsKey(key)) {
                    throw new DecoderException("Duplicate key in synced map");
                }

                values.put(key, value);
            }

            return values;
        });
    }

    static StreamCodec<ByteBuf, Set<Object>> set(
        StreamCodec<ByteBuf, Object> elementCodec
    ) {
        return StreamCodec.of((buffer, values) -> {
            SyncCodecBounds.requireCollectionSize(values.size(), "set");

            List<byte[]> encoded = encodeSorted(values, elementCodec);
            ByteBufCodecs.VAR_INT.encode(buffer, encoded.size());

            encoded.forEach(buffer::writeBytes);
        }, buffer -> {
            int size = SyncCodecBounds.readCollectionSize(buffer, "set");

            Set<Object> values = LinkedHashSet.newLinkedHashSet(size);
            for (int index = 0; index < size; index++) {

                Object value = elementCodec.decode(buffer);

                if (!values.add(value)) {
                    throw new DecoderException("Duplicate value in synced set");
                }
            }

            return values;
        });
    }

    private static List<byte[]> encodeSorted(
        Set<Object> values,
        StreamCodec<ByteBuf, Object> elementCodec
    ) {
        List<byte[]> encoded = new ArrayList<>(values.size());
        ByteBuf elementBuffer = Unpooled.buffer();

        try {
            for (Object value : values) {
                elementBuffer.clear();
                elementCodec.encode(elementBuffer, value);

                byte[] bytes = new byte[elementBuffer.readableBytes()];

                elementBuffer.readBytes(bytes);
                encoded.add(bytes);
            }
        } finally {
            elementBuffer.release();
        }

        encoded.sort(Arrays::compareUnsigned);

        return encoded;
    }
}
