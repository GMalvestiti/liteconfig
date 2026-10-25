package com.gmalvestiti.minecraft.liteconfig.network.codec;

import com.gmalvestiti.minecraft.liteconfig.exception.SyncEncodingException;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

final class NullableValueCodec {

    private NullableValueCodec() {}

    @SuppressWarnings({"unchecked", "ConstantValue", "DataFlowIssue"})
    static StreamCodec<ByteBuf, Object> wrap(
        Class<?> declaredType,
        StreamCodec<ByteBuf, ?> codec,
        boolean builtIn
    ) {
        StreamCodec<ByteBuf, Object> typed = (StreamCodec<ByteBuf, Object>) codec;

        if (declaredType.isPrimitive()) {
            return typed;
        }

        return StreamCodec.of((buffer, value) -> {
            boolean present = value != null;
            ByteBufCodecs.BOOL.encode(buffer, present);

            if (present) {
                try {
                    typed.encode(buffer, value);
                } catch (EncoderException failure) {
                    if (builtIn) {
                        throw new SyncEncodingException("sync.string-size", failure.getMessage());
                    }
                    throw failure;
                }
            }
        }, buffer -> {
            if (!ByteBufCodecs.BOOL.decode(buffer)) {
                return null;
            }

            return typed.decode(buffer);
        });
    }
}
