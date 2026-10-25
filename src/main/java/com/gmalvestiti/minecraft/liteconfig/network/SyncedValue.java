package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

record SyncedValue(String path, Field[] chain, Type type, StreamCodec<ByteBuf, Object> codec,
                   String schema, boolean restart) {

    Object read(ConfigFieldAccess access, Object root) {
        Object value = root;

        for (Field field : chain) {
            value = access.read(field, value);
        }

        return value;
    }

    void write(ConfigFieldAccess access, Object root, Object leaf) {
        Object owner = root;

        for (int index = 0; index < chain.length - 1; index++) {
            owner = access.read(chain[index], owner);
        }

        access.write(chain[chain.length - 1], owner, leaf);
    }
}
