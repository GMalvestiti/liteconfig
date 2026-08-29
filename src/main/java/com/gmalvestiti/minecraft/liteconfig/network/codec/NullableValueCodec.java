package com.gmalvestiti.minecraft.liteconfig.network.codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

final class NullableValueCodec {

    private NullableValueCodec() {}

    @SuppressWarnings({"unchecked", "ConstantValue", "DataFlowIssue"})
    static StreamCodec<ByteBuf, Object> wrap(
        Class<?> declaredType,
        StreamCodec<ByteBuf, ?> codec
    ) {
        StreamCodec<ByteBuf, Object> typed = (StreamCodec<ByteBuf, Object>) codec;

        if (declaredType.isPrimitive()) {
            return typed;
        }

        return StreamCodec.of((buffer, value) -> {
            boolean present = value != null;
            ByteBufCodecs.BOOL.encode(buffer, present);

            if (present) {
                typed.encode(buffer, value);
            }
        }, buffer -> {
            if (!ByteBufCodecs.BOOL.decode(buffer)) {
                return null;
            }

            return typed.decode(buffer);
        });
    }
}
