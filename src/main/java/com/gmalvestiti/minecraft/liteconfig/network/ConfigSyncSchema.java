package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.Hashing;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.SyncEncodingException;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.network.codec.SyncValueCodecs;
import com.gmalvestiti.minecraft.liteconfig.shared.PropertyPath;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.VarInt;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ConfigSyncSchema<T> {

    private final ConfigModel<T> model;
    private volatile List<SyncedValue> values;
    private long fingerprint;

    private ConfigSyncSchema(ConfigModel<T> model) {
        this.model = model;
    }

    private void initialize() {
        if (values != null) {
            return;
        }

        if (!ConfigSyncRegistry.runtimeAvailable()) {
            throw new IllegalStateException("Config sync codecs are unavailable before game initialization");
        }

        List<SyncedValue> values = new ArrayList<>();
        for (ConfigProperty property : model.metadata().synced()) {
            Field[] chain = resolveFields(model.type(), property.path());
            Type type = chain[chain.length - 1].getGenericType();

            SyncValueCodecs.Resolved resolved = SyncValueCodecs.resolve(type, property, model.scope());

            values.add(new SyncedValue(property.path(), chain, type, resolved.codec(), resolved.schema(), property.restart()));
        }

        Config declaration = model.type().getAnnotation(Config.class);
        StringBuilder shape = new StringBuilder()
            .append(model.typeName()).append('|')
            .append(declaration.type()).append('|')
            .append(declaration.restartSyncPolicy());

        if (declaration.type() == ConfigType.WORLD) {
            shape.append("|world-session-restart");
        }

        for (SyncedValue value : values) {
            shape.append('|').append(value.path())
                .append(':').append(value.type().getTypeName())
                .append(':').append(value.schema())
                .append(':').append(value.restart());
        }

        this.fingerprint = Hashing.sha256().hashString(shape, StandardCharsets.UTF_8).asLong();
        this.values = List.copyOf(values);
    }

    public static <T> ConfigSyncSchema<T> create(ConfigModel<T> model) {
        Config declaration = model.type().getAnnotation(Config.class);
        return declaration == null || declaration.type() == ConfigType.CLIENT
            || model.metadata().synced().isEmpty() ? null : new ConfigSyncSchema<>(model);
    }

    public void validate(T state) {
        if (ConfigSyncRegistry.runtimeAvailable()) {
            initialize();
            write(state, null);
        }
    }

    byte[] encode(T state) {
        initialize();
        ByteBuf buffer = Unpooled.buffer(256, ConfigSyncProtocol.MAX_CONFIG_BYTES);
        try {
            write(state, buffer);

            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);

            return encoded;
        } finally {
            buffer.release();
        }
    }

    private void write(T state, ByteBuf buffer) {
        ByteBuf valueBuffer = ConfigSyncBuffers.VALUES.buffer();
        try {
            int total = Long.BYTES;

            if (buffer != null) {
                buffer.writeLong(fingerprint);
            }

            for (SyncedValue value : values) {
                valueBuffer.clear();

                try {
                    value.codec().encode(valueBuffer, value.read(model.fieldAccess(), state));
                } catch (SyncEncodingException failure) {
                    String message = "%s for %s: %s".formatted(value.path(), model.syncId(), failure.getMessage());
                    reject(failure.rule(), message);
                }

                int length = valueBuffer.readableBytes();
                total += VarInt.getByteSize(length) + length;

                if (total > ConfigSyncProtocol.MAX_CONFIG_BYTES) {
                    reject("sync.config-size", "encoded config for %s is %d bytes; maximum is %d bytes"
                        .formatted(model.syncId(), total, ConfigSyncProtocol.MAX_CONFIG_BYTES));
                }

                if (buffer != null) {
                    ByteBufCodecs.VAR_INT.encode(buffer, length);
                    buffer.writeBytes(valueBuffer, valueBuffer.readerIndex(), length);
                }
            }
        } finally {
            valueBuffer.release();
        }
    }

    private void reject(String rule, String message) {
        throw model.scope().exception(ConfigError.VALIDATION_FAILED,
            List.of(Violation.of(rule, message)), model.typeName(), message);
    }

    long fingerprint() {
        initialize();
        return fingerprint;
    }

    List<SyncedValue> values() {
        initialize();
        return values;
    }

    private static Field[] resolveFields(Class<?> rootType, String path) {
        String[] segments = PropertyPath.split(path);
        Field[] chain = new Field[segments.length];

        Class<?> owner = rootType;
        for (int index = 0; index < segments.length; index++) {
            Field field = DeclaredConfig.of(owner).property(segments[index]).field();
            chain[index] = field;
            owner = field.getType();
        }

        return chain;
    }
}
