package com.gmalvestiti.minecraft.liteconfig.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

            List<Object> values = new ArrayList<>(Math.min(size, buffer.readableBytes()));
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

            Map<Object, Object> values = LinkedHashMap.newLinkedHashMap(Math.min(size, buffer.readableBytes()));
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
        StreamCodec<ByteBuf, Object> elementCodec,
        boolean naturalOrder
    ) {
        return StreamCodec.of((buffer, values) -> {
            int size = values.size();
            SyncCodecBounds.requireCollectionSize(size, "set");

            ByteBufCodecs.VAR_INT.encode(buffer, size);

            if (size <= 1) {
                values.forEach(value -> elementCodec.encode(buffer, value));
            } else if (naturalOrder) {
                writeNatural(buffer, values, elementCodec);
            } else {
                writeByEncoding(buffer, values, elementCodec);
            }
        }, buffer -> {
            int size = SyncCodecBounds.readCollectionSize(buffer, "set");

            Set<Object> values = LinkedHashSet.newLinkedHashSet(Math.min(size, buffer.readableBytes()));
            for (int index = 0; index < size; index++) {

                Object value = elementCodec.decode(buffer);

                if (!values.add(value)) {
                    throw new DecoderException("Duplicate value in synced set");
                }
            }

            return values;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void writeNatural(
        ByteBuf buffer,
        Set<Object> values,
        StreamCodec<ByteBuf, Object> elementCodec
    ) {
        Object[] sorted = values.toArray();
        Arrays.sort(sorted, (Comparator) Comparator.naturalOrder());

        for (Object value : sorted) {
            elementCodec.encode(buffer, value);
        }
    }

    private static void writeByEncoding(
        ByteBuf buffer,
        Set<Object> values,
        StreamCodec<ByteBuf, Object> elementCodec
    ) {
        ByteBuf scratch = Unpooled.buffer();

        try {
            int[] ends = new int[values.size()];
            Integer[] order = new Integer[ends.length];
            int count = 0;

            for (Object value : values) {
                elementCodec.encode(scratch, value);
                order[count] = count;
                ends[count++] = scratch.writerIndex();
            }

            byte[] bytes = scratch.array();
            Arrays.sort(order, (left, right) -> Arrays.compareUnsigned(
                bytes, start(ends, left), ends[left], bytes, start(ends, right), ends[right]));

            for (int index : order) {
                buffer.writeBytes(bytes, start(ends, index), ends[index] - start(ends, index));
            }
        } finally {
            scratch.release();
        }
    }

    private static int start(int[] ends, int index) {
        return index == 0 ? 0 : ends[index - 1];
    }
}
