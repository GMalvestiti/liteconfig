package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.network.codec.SyncValueCodecs;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.shared.PropertyPath;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class SyncedConfig<T> {

    private static final HashFunction SHA_256 = Hashing.sha256();

    private final RegisteredConfig<T> config;
    private final List<SyncedValue> values;
    private final boolean carriesRestartValues;
    private final long schema;
    private Snapshot cachedSnapshot;
    private List<ConfigSubscription> broadcastSubscriptions = List.of();

    private SyncedConfig(RegisteredConfig<T> config, List<SyncedValue> values) {
        this.config = config;
        this.values = values;
        this.carriesRestartValues = values.stream().anyMatch(SyncedValue::restart);
        this.schema = schemaOf(config, values);
        this.cachedSnapshot = snapshot();
    }

    static <T> SyncedConfig<T> of(RegisteredConfig<T> config) {
        Class<T> rootType = config.model().type();
        ConfigScope scope = config.model().scope();

        List<SyncedValue> values = new ArrayList<>();
        for (ConfigProperty property : config.model().metadata().synced()) {

            Field[] chain = chainOf(rootType, property.path());
            Type type = chain[chain.length - 1].getGenericType();

            SyncValueCodecs.Resolved resolved = SyncValueCodecs.resolve(type, property, scope);

            values.add(new SyncedValue(
                property.path(),
                chain,
                type,
                resolved.codec(),
                resolved.schema(),
                property.restart()));
        }

        return new SyncedConfig<>(config, List.copyOf(values));
    }

    String id() {
        return config.model().syncId();
    }

    ConfigScope scope() {
        return config.model().scope();
    }

    boolean wraps(RegisteredConfig<?> registration) {
        return config == registration;
    }

    Snapshot snapshot() {
        byte[] data = encode();
        return new Snapshot(ConfigBytes.trusted(data), ConfigBytes.trusted(hash(data)));
    }

    synchronized boolean differsFrom(ConfigBytes hash) {
        return !cachedSnapshot.hash().equals(hash);
    }

    synchronized Snapshot cachedSnapshot() {
        return cachedSnapshot;
    }

    synchronized Snapshot changedSnapshot() {
        Snapshot snapshot = snapshot();

        if (cachedSnapshot.hash().equals(snapshot.hash())) {
            return null;
        }

        cachedSnapshot = snapshot;

        return snapshot;
    }

    private byte[] encode() {
        ConfigFieldAccess fieldAccess = config.model().fieldAccess();
        T state = config.state().published();

        ByteBuf buffer = Unpooled.buffer();
        ByteBuf valueBuffer = Unpooled.buffer();

        try {
            buffer.writeLong(schema);

            for (SyncedValue value : values) {

                valueBuffer.clear();
                value.codec().encode(valueBuffer, value.read(fieldAccess, state));

                int length = valueBuffer.readableBytes();
                if (length > ConfigSyncProtocol.MAX_VALUE_BYTES) {
                    throw config.model().scope().exception(
                        ConfigError.SYNC_APPLY_FAILED,
                        id(),
                        "encoded value " + value.path() + " exceeds "
                            + ConfigSyncProtocol.MAX_VALUE_BYTES + " bytes");
                }

                ByteBufCodecs.VAR_INT.encode(buffer, length);
                buffer.writeBytes(valueBuffer, valueBuffer.readerIndex(), length);
            }

            if (buffer.readableBytes() > ConfigSyncProtocol.MAX_CONFIG_BYTES) {
                throw config.model().scope().exception(
                    ConfigError.SYNC_APPLY_FAILED,
                    id(),
                    "encoded config exceeds " + ConfigSyncProtocol.MAX_CONFIG_BYTES + " bytes");
            }

            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);

            return encoded;
        } finally {
            valueBuffer.release();
            buffer.release();
        }
    }

    boolean apply(ConfigBytes payload) {

        Prepared<T> prepared = prepare(payload);
        if (!prepared.commit()) {
            return false;
        }

        return prepared.restartRequired();
    }

    Prepared<T> prepare(ConfigBytes payload) {
        return await(config.tasks().submit(() -> prepareState(payload)));
    }

    private Prepared<T> prepareState(ConfigBytes payload) {
        ConfigFieldAccess fieldAccess = config.model().fieldAccess();

        return config.state().writing(() -> {
            T current = config.state().canonical();
            T candidate = config.state().copyOfCanonical();

            decodeInto(payload, fieldAccess, candidate);

            config.guard().validate(candidate);
            boolean restartRequired = changesRestartValues(fieldAccess, current, candidate);

            return new Prepared<>(this, current, candidate, restartRequired);
        });
    }

    private boolean rollbackCommitted(Prepared<T> prepared) {
        return await(config.tasks().submit(() -> config.state().writing(() -> {
            if (config.state().canonical() != prepared.candidate) {
                return false;
            }

            if (!config.exceptionHandler().onWrite(
                () -> config.engine().save(prepared.before)).completed()) {
                return false;
            }

            config.state().replace(prepared.before);
            synchronized (this) {
                cachedSnapshot = snapshot();
            }

            prepared.applied = null;
            return true;
        })));
    }

    private boolean commit(Prepared<T> prepared, boolean dispatchImmediately) {
        Applied<T> applied = await(config.tasks().submit(() -> commitState(prepared)));

        if (applied == null) {
            return false;
        }

        prepared.applied = applied;
        if (dispatchImmediately) {
            dispatch(applied);
        }

        return true;
    }

    private Applied<T> commitState(Prepared<T> prepared) {
        return config.state().writing(() -> {
            if (config.state().canonical() != prepared.before
                || !config.exceptionHandler()
                    .onWrite(() -> config.engine().save(prepared.candidate))
                    .completed()) {

                return null;
            }

            try {
                return publish(prepared);
            } catch (RuntimeException | Error failure) {
                restorePublished(prepared);
                config.exceptionHandler().onWrite(() -> config.engine().save(prepared.before));
                throw failure;
            }
        });
    }

    private Applied<T> publish(Prepared<T> prepared) {
        if (config.state().canonical() != prepared.before) {
            throw config.model().scope().exception(ConfigError.SYNC_APPLY_FAILED, id(), "config changed during sync");
        }

        if (prepared.restartRequired) {
            config.guard().carryOverRestart(prepared.before, prepared.candidate);
        }

        ConfigState.Transition<T> transition = config.state().replace(prepared.candidate);

        boolean publishedChanged;
        synchronized (this) {
            Snapshot updated = snapshot();
            publishedChanged = !cachedSnapshot.hash().equals(updated.hash());
            cachedSnapshot = updated;
        }

        return new Applied<>(
            transition.before(),
            transition.after(),
            transition.published(),
            publishedChanged);
    }

    private void restorePublished(Prepared<T> prepared) {
        if (config.state().canonical() != prepared.candidate) {
            return;
        }

        config.state().replace(prepared.before);

        synchronized (this) {
            cachedSnapshot = snapshot();
        }
    }

    private void dispatch(Applied<T> applied) {
        if (applied.publishedChanged()) {
            config.model().callbacks().enqueueChanged(
                applied.beforeState(), applied.afterState(), true).run();
            config.notifier().notifyUpdated(applied.publishedState());
        }
    }

    private static <V> V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException wrapper) {
            Throwable failure = wrapper.getCause();

            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (failure instanceof Error error) {
                throw error;
            }

            throw wrapper;
        }
    }

    private void decodeInto(ConfigBytes payload, ConfigFieldAccess fieldAccess, T candidate) {
        ByteBuf buffer = payload.readBuffer();

        try {
            ConfigSyncProtocol.requireReadable(buffer, Long.BYTES, "config schema");

            long receivedSchema = buffer.readLong();
            if (receivedSchema != schema) {
                throw new DecoderException("schema mismatch: received " + receivedSchema + ", expected " + schema);
            }

            for (SyncedValue value : values) {
                int length = ConfigSyncProtocol.readSize(buffer, ConfigSyncProtocol.MAX_VALUE_BYTES, "synced value");

                ConfigSyncProtocol.requireReadable(buffer, length, "synced value");
                ByteBuf valueBuffer = buffer.readSlice(length);
                Object decoded = value.codec().decode(valueBuffer);

                if (valueBuffer.isReadable()) {
                    throw new DecoderException("codec for " + value.path() + " left " + valueBuffer.readableBytes() + " trailing bytes");
                }

                value.write(fieldAccess, candidate, decoded);
            }

            if (buffer.isReadable()) {
                throw new DecoderException("config payload has " + buffer.readableBytes() + " trailing bytes");
            }
        } catch (LiteConfigException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw config.model().scope().exception(
                ConfigError.SYNC_APPLY_FAILED,
                failure,
                id(),
                failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage());
        } finally {
            buffer.release();
        }
    }

    private boolean changedOnWire(SyncedValue value, Object first, Object second) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            value.codec().encode(buffer, first);
            int firstLength = buffer.writerIndex();

            value.codec().encode(buffer, second);
            int secondLength = buffer.writerIndex() - firstLength;

            if (firstLength != secondLength) {
                return true;
            }

            for (int index = 0; index < firstLength; index++) {
                if (buffer.getByte(index) != buffer.getByte(firstLength + index)) {
                    return true;
                }
            }

            return false;
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unused")
    void addBroadcastListener(Runnable broadcast) {
        close();
        broadcastSubscriptions = List.of(
            config.notifier().addUpdateListener(state -> broadcast.run(), Runnable::run),
            config.notifier().addLoadListener(state -> broadcast.run(), Runnable::run));
    }

    void close() {
        broadcastSubscriptions.forEach(ConfigSubscription::close);
        broadcastSubscriptions = List.of();
    }

    private boolean changesRestartValues(ConfigFieldAccess fieldAccess, T before, T after) {
        if (!carriesRestartValues) {
            return false;
        }

        for (SyncedValue value : values) {
            if (value.restart()
                && changedOnWire(value, value.read(fieldAccess, before), value.read(fieldAccess, after))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Fingerprints what this build puts on the wire: which values, in which order, as which type.
     * Anything that would change how a payload is read changes this too.
     */
    private static long schemaOf(RegisteredConfig<?> config, List<SyncedValue> values) {
        StringBuilder shape = new StringBuilder()
            .append(ConfigSyncProtocol.VERSION)
            .append('|')
            .append(config.model().typeName());

        for (SyncedValue value : values) {
            shape.append('|')
                .append(value.path())
                .append(':')
                .append(value.type().getTypeName())
                .append(':')
                .append(value.schema());
        }

        return SHA_256.hashString(shape, StandardCharsets.UTF_8).asLong();
    }

    private static byte[] hash(byte[] data) {
        return SHA_256.hashBytes(data).asBytes();
    }

    private static Field[] chainOf(Class<?> rootType, String path) {
        String[] segments = PropertyPath.split(path);
        Field[] chain = new Field[segments.length];

        Class<?> owner = rootType;
        for (int i = 0; i < segments.length; i++) {
            Field field = DeclaredConfig.of(owner).property(segments[i]).field();
            chain[i] = field;
            owner = field.getType();
        }

        return chain;
    }

    /**
     * One synced leaf: the fields that reach it from the root, its declared type, and how it
     * travels.
     */
    private record SyncedValue(
        String path,
        Field[] chain,
        Type type,
        StreamCodec<ByteBuf, Object> codec,
        String schema,
        boolean restart
    ) {

        Object read(ConfigFieldAccess fieldAccess, Object root) {
            Object value = root;
            for (Field field : chain) {
                value = fieldAccess.read(field, value);
            }
            return value;
        }

        void write(ConfigFieldAccess fieldAccess, Object root, Object leaf) {
            Object owner = root;
            for (int i = 0; i < chain.length - 1; i++) {
                owner = fieldAccess.read(chain[i], owner);
            }
            fieldAccess.write(chain[chain.length - 1], owner, leaf);
        }
    }

    record Snapshot(ConfigBytes data, ConfigBytes hash) {}

    static final class Prepared<T> {

        private final SyncedConfig<T> owner;
        private final T before;
        private final T candidate;
        private final boolean restartRequired;
        private Applied<T> applied;

        private Prepared(
            SyncedConfig<T> owner,
            T before,
            T candidate,
            boolean restartRequired
        ) {
            this.owner = owner;
            this.before = before;
            this.candidate = candidate;
            this.restartRequired = restartRequired;
        }

        boolean commit() {
            return owner.commit(this, true);
        }

        boolean commitDeferred() {
            return owner.commit(this, false);
        }

        void dispatch() {
            Applied<T> committed = applied;
            if (committed != null) {
                owner.dispatch(committed);
            }
        }

        boolean rollback() {
            return owner.rollbackCommitted(this);
        }

        boolean restartRequired() {
            return restartRequired;
        }
    }

    private record Applied<T>(
        T beforeState,
        T afterState,
        T publishedState,
        boolean publishedChanged
    ) {}
}
