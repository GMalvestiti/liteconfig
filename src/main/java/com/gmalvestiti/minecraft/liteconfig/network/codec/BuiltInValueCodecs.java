package com.gmalvestiti.minecraft.liteconfig.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Map;

final class BuiltInValueCodecs {

    private static final StreamCodec<ByteBuf, Character> CHARACTER =
        ByteBufCodecs.VAR_INT.map(value -> {
            if (value < Character.MIN_VALUE || value > Character.MAX_VALUE) {
                throw new DecoderException("Invalid synced character " + value);
            }
            return (char) value.intValue();
        }, value -> (int) value);

    private static final StreamCodec<ByteBuf, Long> LONG =
        StreamCodec.of(ByteBuf::writeLong, ByteBuf::readLong);

    private static final Map<Class<?>, StreamCodec<ByteBuf, ?>> CODECS = Map.ofEntries(
        Map.entry(boolean.class, ByteBufCodecs.BOOL),
        Map.entry(Boolean.class, ByteBufCodecs.BOOL),
        Map.entry(byte.class, ByteBufCodecs.BYTE),
        Map.entry(Byte.class, ByteBufCodecs.BYTE),
        Map.entry(short.class, ByteBufCodecs.SHORT),
        Map.entry(Short.class, ByteBufCodecs.SHORT),
        Map.entry(char.class, CHARACTER),
        Map.entry(Character.class, CHARACTER),
        Map.entry(int.class, ByteBufCodecs.INT),
        Map.entry(Integer.class, ByteBufCodecs.INT),
        Map.entry(long.class, LONG),
        Map.entry(Long.class, LONG),
        Map.entry(float.class, ByteBufCodecs.FLOAT),
        Map.entry(Float.class, ByteBufCodecs.FLOAT),
        Map.entry(double.class, ByteBufCodecs.DOUBLE),
        Map.entry(Double.class, ByteBufCodecs.DOUBLE),
        Map.entry(String.class, ByteBufCodecs.STRING_UTF8)
    );

    private BuiltInValueCodecs() {}

    static StreamCodec<ByteBuf, ?> find(Class<?> type) {
        if (BuiltInTypes.isTextValue(type)) {
            return ByteBufCodecs.STRING_UTF8.map(text -> {
                try {
                    return BuiltInTypes.parseText(type, text);
                } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
                    throw new DecoderException("Invalid synced " + type.getTypeName(), invalid);
                }
            }, Object::toString);
        }

        return type.isEnum() ? enumCodec(type) : CODECS.get(type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static StreamCodec<ByteBuf, ?> enumCodec(Class<?> enumType) {
        return ByteBufCodecs.STRING_UTF8.map(
            name -> Enum.valueOf((Class<? extends Enum>) enumType, name),
            Enum::name);
    }

}
