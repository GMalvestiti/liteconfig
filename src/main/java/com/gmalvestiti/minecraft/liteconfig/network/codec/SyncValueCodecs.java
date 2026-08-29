package com.gmalvestiti.minecraft.liteconfig.network.codec;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SyncValueCodecs {

    private SyncValueCodecs() {}

    public static Resolved resolve(
        Type declaredType,
        ConfigProperty property,
        ConfigScope scope
    ) {
        return strategy(declaredType)
            .orElseThrow(() -> scope.exception(
                ConfigError.UNSUPPORTED_SYNC_TYPE, property.fieldName(), declaredType.getTypeName()));
    }

    private static Optional<Resolved> strategy(Type declaredType) {
        Class<?> rawType = rawTypeOf(declaredType);
        StreamCodec<ByteBuf, ?> registered = LiteConfig.codecs()
            .findStream(declaredType)
            .orElse(null);

        if (registered != null) {
            String version = LiteConfig.codecs()
                .findStreamSchema(declaredType)
                .orElse("1");

            return Optional.of(new Resolved(
                NullableValueCodec.wrap(rawType, registered),
                "registered:" + declaredType.getTypeName() + '@' + version));
        }

        StreamCodec<ByteBuf, ?> builtIn = BuiltInValueCodecs.find(rawType);
        if (builtIn != null) {
            return Optional.of(new Resolved(
                NullableValueCodec.wrap(rawType, builtIn),
                "builtin:" + rawType.getTypeName()));
        }

        if (!(declaredType instanceof ParameterizedType parameterized)) {
            return Optional.empty();
        }

        Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length == 1
            && List.class.isAssignableFrom(rawType)
            && rawType.isAssignableFrom(ArrayList.class)) {

            return strategy(arguments[0]).map(element -> new Resolved(
                NullableValueCodec.wrap(
                    rawType, CollectionValueCodecs.list(element.codec())),
                "list<" + element.schema() + '>'));
        }

        if (arguments.length == 1
            && Set.class.isAssignableFrom(rawType)
            && rawType.isAssignableFrom(LinkedHashSet.class)) {

            return strategy(arguments[0]).map(element -> new Resolved(
                NullableValueCodec.wrap(
                    rawType, CollectionValueCodecs.set(element.codec())),
                "set<" + element.schema() + '>'));
        }

        if (arguments.length == 2
            && Map.class.isAssignableFrom(rawType)
            && rawType.isAssignableFrom(LinkedHashMap.class)) {

            Optional<Resolved> key = strategy(arguments[0]);
            Optional<Resolved> value = strategy(arguments[1]);

            if (key.isPresent() && value.isPresent()) {
                return Optional.of(new Resolved(
                    NullableValueCodec.wrap(
                        rawType,
                        CollectionValueCodecs.map(
                            key.get().codec(), value.get().codec())),
                    "map<" + key.get().schema() + ',' + value.get().schema() + '>'));
            }
        }

        return Optional.empty();
    }

    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> rawType) {
            return rawType;
        }

        if (type instanceof ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> rawType) {
            return rawType;
        }

        return Object.class;
    }

    public record Resolved(StreamCodec<ByteBuf, Object> codec, String schema) {}
}
