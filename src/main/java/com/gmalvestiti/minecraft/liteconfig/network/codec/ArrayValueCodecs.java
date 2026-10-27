package com.gmalvestiti.minecraft.liteconfig.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.lang.reflect.Array;
import java.util.Map;

final class ArrayValueCodecs {

    private static final StreamCodec<ByteBuf, Object> BOOLEANS = StreamCodec.of(
        (buffer, values) -> {
            boolean[] array = (boolean[]) values;
            writeLength(buffer, array.length);
            for (boolean value : array) {
                buffer.writeBoolean(value);
            }
        }, buffer -> {
            boolean[] array = new boolean[readLength(buffer, Byte.BYTES)];
            for (int index = 0; index < array.length; index++) {
                array[index] = buffer.readBoolean();
            }
            return array;
        });

    private static final StreamCodec<ByteBuf, Object> BYTES = StreamCodec.of(
        (buffer, values) -> {
            byte[] array = (byte[]) values;
            writeLength(buffer, array.length);
            buffer.writeBytes(array);
        }, buffer -> {
            byte[] array = new byte[readLength(buffer, Byte.BYTES)];
            buffer.readBytes(array);
            return array;
        });

    private static final StreamCodec<ByteBuf, Object> SHORTS = StreamCodec.of(
        (buffer, values) -> {
            short[] array = (short[]) values;
            writeLength(buffer, array.length);
            for (short value : array) {
                buffer.writeShort(value);
            }
        }, buffer -> {
            short[] array = new short[readLength(buffer, Short.BYTES)];
            for (int index = 0; index < array.length; index++) {
                array[index] = buffer.readShort();
            }
            return array;
        });

    private static final StreamCodec<ByteBuf, Object> CHARS = StreamCodec.of(
        (buffer, values) -> {
            char[] array = (char[]) values;
            writeLength(buffer, array.length);
            for (char value : array) {
                ByteBufCodecs.VAR_INT.encode(buffer, (int) value);
            }
        }, buffer -> {
            char[] array = new char[readLength(buffer, Byte.BYTES)];
            for (int index = 0; index < array.length; index++) {
                int value = ByteBufCodecs.VAR_INT.decode(buffer);
                if (value < Character.MIN_VALUE || value > Character.MAX_VALUE) {
                    throw new DecoderException("Invalid synced character " + value);
                }
                array[index] = (char) value;
            }
            return array;
        });

    private static final StreamCodec<ByteBuf, Object> INTS = StreamCodec.of(
        (buffer, values) -> {
            int[] array = (int[]) values;
            writeLength(buffer, array.length);
            for (int value : array) {
                buffer.writeInt(value);
            }
        }, buffer -> {
            int[] array = new int[readLength(buffer, Integer.BYTES)];
            for (int index = 0; index < array.length; index++) {
                array[index] = buffer.readInt();
            }
            return array;
        });

    private static final StreamCodec<ByteBuf, Object> LONGS = StreamCodec.of(
        (buffer, values) -> {
            long[] array = (long[]) values;
            writeLength(buffer, array.length);
            for (long value : array) {
                buffer.writeLong(value);
            }
        }, buffer -> {
            long[] array = new long[readLength(buffer, Long.BYTES)];
            for (int index = 0; index < array.length; index++) {
                array[index] = buffer.readLong();
            }
            return array;
        });

    private static final StreamCodec<ByteBuf, Object> FLOATS = StreamCodec.of(
        (buffer, values) -> {
            float[] array = (float[]) values;
            writeLength(buffer, array.length);
            for (float value : array) {
                buffer.writeFloat(value);
            }
        }, buffer -> {
            float[] array = new float[readLength(buffer, Float.BYTES)];
            for (int index = 0; index < array.length; index++) {
                array[index] = buffer.readFloat();
            }
            return array;
        });

    private static final StreamCodec<ByteBuf, Object> DOUBLES = StreamCodec.of(
        (buffer, values) -> {
            double[] array = (double[]) values;
            writeLength(buffer, array.length);
            for (double value : array) {
                buffer.writeDouble(value);
            }
        }, buffer -> {
            double[] array = new double[readLength(buffer, Double.BYTES)];
            for (int index = 0; index < array.length; index++) {
                array[index] = buffer.readDouble();
            }
            return array;
        });

    private static final Map<Class<?>, StreamCodec<ByteBuf, Object>> PRIMITIVES = Map.of(
        boolean.class, BOOLEANS,
        byte.class, BYTES,
        short.class, SHORTS,
        char.class, CHARS,
        int.class, INTS,
        long.class, LONGS,
        float.class, FLOATS,
        double.class, DOUBLES
    );

    private ArrayValueCodecs() {}

    static StreamCodec<ByteBuf, Object> array(
        Class<?> componentType,
        StreamCodec<ByteBuf, Object> elementCodec,
        boolean customElement
    ) {
        StreamCodec<ByteBuf, Object> primitive = customElement ? null : PRIMITIVES.get(componentType);
        if (primitive != null) {
            return primitive;
        }

        int minimumBytes = customElement ? 0 : 1;

        return StreamCodec.of((buffer, values) -> {
            int length = Array.getLength(values);
            writeLength(buffer, length);

            for (int index = 0; index < length; index++) {
                elementCodec.encode(buffer, Array.get(values, index));
            }
        }, buffer -> {
            int length = readLength(buffer, minimumBytes);

            Object values = Array.newInstance(componentType, length);
            for (int index = 0; index < length; index++) {
                Array.set(values, index, elementCodec.decode(buffer));
            }

            return values;
        });
    }

    private static void writeLength(ByteBuf buffer, int length) {
        SyncCodecBounds.requireCollectionSize(length, "array");
        ByteBufCodecs.VAR_INT.encode(buffer, length);
    }

    private static int readLength(ByteBuf buffer, int minimumBytes) {
        int length = SyncCodecBounds.readCollectionSize(buffer, "array");

        if (buffer.readableBytes() < length * minimumBytes) {
            throw new DecoderException("Truncated synced array");
        }

        return length;
    }
}
