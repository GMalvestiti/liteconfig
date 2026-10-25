package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.gmalvestiti.minecraft.liteconfig.api.RestartSyncPolicy;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigStorage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import java.util.Objects;

final class SyncedConfig<T> {

    private static final HashFunction SHA_256 = Hashing.sha256();

    private final RegisteredConfig<T> config;
    private final RestartSyncPolicy restartSyncPolicy;
    private final ConfigSyncSchema<T> syncSchema;
    private final boolean carriesRestartValues;
    private Snapshot cachedSnapshot;
    private T snapshotState;
    private ConfigBytes broadcastHash;
    private RestartSyncStore restartStore;
    private String restartConnectionId;

    private SyncedConfig(RegisteredConfig<T> config) {
        this.config = config;
        this.syncSchema = Objects.requireNonNull(config.syncSchema(), "syncSchema");
        this.carriesRestartValues = config.model().metadata().synced().stream()
            .anyMatch(ConfigProperty::restart);
        Config declaration = config.model().type().getAnnotation(Config.class);
        this.restartSyncPolicy = declaration == null
            ? RestartSyncPolicy.DEFER : declaration.restartSyncPolicy();
    }

    static <T> SyncedConfig<T> of(RegisteredConfig<T> config) {
        return new SyncedConfig<>(config);
    }

    private void initialize() {
        if (this.cachedSnapshot != null) {
            return;
        }

        this.snapshotState = this.config.state().published();
        this.cachedSnapshot = snapshot(this.snapshotState);
        this.broadcastHash = this.cachedSnapshot.hash();
    }

    String id() {
        return this.config.model().syncId();
    }

    boolean wraps(RegisteredConfig<?> registration) {
        return this.config == registration;
    }

    boolean ready() {
        synchronized (this.config.state().operationLock()) {
            return this.cachedSnapshot != null;
        }
    }

    boolean locallyActive() {
        return config.isLocallyWritable() && !config.state().remoteActive();
    }

    void activate() {
        config.writing(() -> {
            if (cachedSnapshot == null) {
                syncSchema.validate(config.declaredDefaults());
            }
            config.state().pending().ifPresent(syncSchema::validate);
            return cachedSnapshot();
        });
    }

    private Snapshot snapshot(T state) {
        byte[] data = encode(state);
        return new Snapshot(ConfigBytes.trusted(data), ConfigBytes.trusted(hash(data)));
    }

    Snapshot cachedSnapshot() {
        synchronized (this.config.state().operationLock()) {
            initialize();

            T state = this.config.state().published();

            if (this.snapshotState != state) {
                this.cachedSnapshot = snapshot(state);
                this.snapshotState = state;
            }

            return this.cachedSnapshot;
        }
    }

    Snapshot changedSnapshot() {
        synchronized (this.config.state().operationLock()) {
            Snapshot snapshot = cachedSnapshot();

            if (this.broadcastHash.equals(snapshot.hash())) {
                return null;
            }

            this.broadcastHash = snapshot.hash();

            return snapshot;
        }
    }

    ConfigBytes payloadMatching(ConfigBytes expectedHash) {
        Snapshot local = cachedSnapshot();
        if (local.hash().equals(expectedHash)) {
            return local.data();
        }
        ConfigBytes persisted = restartStore().read();
        return compatibleRestartCache(persisted)
            && ConfigBytes.trusted(hash(persisted.bytes())).equals(expectedHash) ? persisted : null;
    }

    private byte[] encode(T state) {
        return syncSchema.encode(state);
    }

    Prepared<T> prepare(ConfigBytes payload) {
        cachedSnapshot();
        ConfigFieldAccess fieldAccess = this.config.model().fieldAccess();

        return this.config.writing(() -> {
            T current = this.config.state().canonical();
            T candidate = this.config.state().copyOfLocal();

            decodeInto(payload, fieldAccess, candidate);

            this.config.guard().normalize(candidate);
            this.config.guard().validate(candidate);

            T active = this.config.state().copyOf(candidate);

            if (this.config.pathResolver().type() != ConfigType.WORLD || this.config.state().remoteActive()) {
                this.config.guard().carryOverRestart(current, active);
            }

            this.config.guard().validate(active);
            boolean restartRequired = changesRestartValues(fieldAccess, active, candidate);

            return new Prepared<>(this, current, candidate, active, restartRequired,
                this.config.state().checkpoint(), restartStore(), payload);
        });
    }

    private RestartSyncStore restartStore() {
        String connectionId = ClientConfigSync.connectionIdentity();

        if (restartStore == null || !connectionId.equals(restartConnectionId)) {
            restartStore = new RestartSyncStore(
                config.pathResolver().baseDirectory(), config.model().scope(), id(), connectionId);
            restartConnectionId = connectionId;
        }

        return restartStore;
    }

    private boolean compatibleRestartCache(ConfigBytes bytes) {
        if (bytes == null) {
            return false;
        }

        ByteBuf buffer = bytes.readBuffer();
        try {
            if (buffer.readableBytes() >= Long.BYTES && buffer.readLong() == syncSchema.fingerprint()) {
                return true;
            }
            config.model().scope().logInfo("Ignoring restart-sync cache with an obsolete schema for " + id());
            return false;
        } finally {
            buffer.release();
        }
    }

    private boolean rollbackCommitted(Prepared<T> prepared) {
        return this.config.writing(() -> {
            if (this.config.state().canonical() != prepared.active) {
                return false;
            }

            this.config.state().restore(prepared.beforeSnapshot);
            this.broadcastHash = this.cachedSnapshot().hash();
            prepared.applied = null;

            return true;
        });
    }

    private boolean commit(Prepared<T> prepared, RestartSyncStore.Transaction caches) {
        return this.config.writing(() -> {
            if (this.config.state().canonical() != prepared.before) {
                return false;
            }

            if (!this.config.exceptionHandler().onWrite(() -> {
                caches.stage(prepared.store, prepared.payload);

                if (this.config.pathResolver().type() == ConfigType.SERVER && carriesRestartValues
                    && (prepared.restartRequired || this.config.state().pending().isPresent())) {

                    T local = this.config.engine().restartFileState(this.config.declaredDefaults());
                    this.config.guard().normalize(local);

                    if (!changesRestartValues(this.config.model().fieldAccess(), local, prepared.candidate)) {
                        return;
                    }

                    copySyncedRestart(prepared.candidate, local);
                    this.config.guard().normalize(local);
                    this.config.guard().validate(local);

                    ConfigStorage.PreparedWrite file = this.config.engine().prepareRestartWrite(local, saved -> {
                        copySyncedRestart(prepared.candidate, saved);

                        this.config.guard().normalize(saved);
                        this.config.guard().validate(saved);

                        if (changesRestartValues(this.config.model().fieldAccess(), saved, prepared.candidate)) {
                            throw this.config.model().scope().exception(
                                ConfigError.SYNC_APPLY_FAILED, id(), "normalization changed persisted restart selections");
                        }
                    });
                    file.stage(caches);
                }
            }).completed()) {
                return false;
            }

            try {
                prepared.applied = publish(prepared);
                return true;
            } catch (RuntimeException | Error failure) {
                restorePublished(prepared);
                throw failure;
            }
        });
    }

    private void copySyncedRestart(T source, T target) {
        T isolated = this.config.state().copyOf(source);
        ConfigFieldAccess access = this.config.model().fieldAccess();

        for (SyncedValue value : syncSchema.values()) {
            if (value.restart()) {
                value.write(access, target, value.read(access, isolated));
            }
        }
    }

    private ConfigState.Transition<T> publish(Prepared<T> prepared) {
        ConfigBytes beforeHash = cachedSnapshot().hash();
        ConfigState.Transition<T> transition = this.config.state().replaceRemote(
            prepared.active, prepared.restartRequired ? prepared.candidate : null,
            this.config.pathResolver().restartPathForConfig(this.config.model().type()));

        Snapshot updated = cachedSnapshot();
        prepared.publishedChanged = !beforeHash.equals(updated.hash());
        this.broadcastHash = updated.hash();

        return transition;
    }

    private void restorePublished(Prepared<T> prepared) {
        if (this.config.state().canonical() != prepared.active) {
            return;
        }

        this.config.state().restore(prepared.beforeSnapshot);

        this.broadcastHash = cachedSnapshot().hash();
    }

    private void dispatch(ConfigState.Transition<T> applied) {
        this.config.writingWithCallbacks(callbacks -> callbacks.enqueue(
            this.config.model().listeners().enqueueSynced(
                applied.before(),
                applied.after(),
                () -> this.config.syncNotifier().notifyClientSync(applied.published()))));
    }

    private void decodeInto(ConfigBytes payload, ConfigFieldAccess fieldAccess, T candidate) {
        ByteBuf buffer = payload.readBuffer();

        try {
            ConfigSyncProtocol.requireReadable(buffer, Long.BYTES, "config schema");

            long receivedSchema = buffer.readLong();
            if (receivedSchema != syncSchema.fingerprint()) {
                throw new DecoderException("schema mismatch: received " + receivedSchema + ", expected " + syncSchema.fingerprint());
            }

            for (SyncedValue value : syncSchema.values()) {
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
            throw this.config.model().scope().exception(
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

    private boolean encodedValueChanged(SyncedValue value, Object first, Object second) {
        if (first == second) {
            return false;
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            value.codec().encode(buffer, first);
            int firstLength = buffer.writerIndex();

            value.codec().encode(buffer, second);
            int secondLength = buffer.writerIndex() - firstLength;

            if (firstLength != secondLength) {
                return true;
            }

            return !ByteBufUtil.equals(buffer, 0, buffer, firstLength, firstLength);
        } finally {
            buffer.release();
        }
    }

    void registerLocalChangeBroadcast() {
        this.config.syncNotifier().setLocalChangeListener(
            this, () -> ServerConfigSync.scheduleBroadcast(this));
    }

    void close() {
        this.config.syncNotifier().clearLocalChangeListener(this);
    }

    void clearRemote() {
        this.config.writingWithCallbacks(callbacks -> {
            if (this.config.state().remoteActive()) {
                ConfigBytes before = cachedSnapshot().hash();

                ConfigState.Transition<T> transition = this.config.pathResolver().type() == ConfigType.WORLD
                    ? this.config.state().replaceLocal(this.config.state().copyOfLocal(), null, null)
                    : this.config.state().clearRemote();

                Snapshot restored = cachedSnapshot();
                this.broadcastHash = restored.hash();

                if (!before.equals(restored.hash())) {
                    callbacks.enqueue(this.config.model().listeners().enqueueChanged(
                        transition.before(), transition.after(), true));
                    callbacks.enqueue(() -> this.config.syncNotifier().notifyClientSync(transition.published()));
                }
            }
        });
    }

    private boolean changesRestartValues(ConfigFieldAccess fieldAccess, T before, T after) {
        if (!this.carriesRestartValues) {
            return false;
        }

        for (SyncedValue value : syncSchema.values()) {
            if (value.restart()
                && encodedValueChanged(value, value.read(fieldAccess, before), value.read(fieldAccess, after))) {
                return true;
            }
        }

        return false;
    }

    private static byte[] hash(byte[] data) {
        return SHA_256.hashBytes(data).asBytes();
    }

    record Snapshot(ConfigBytes data, ConfigBytes hash) {}

    static final class Prepared<T> {

        private final SyncedConfig<T> owner;
        private final T before;
        private final T candidate;
        private final T active;
        private final boolean restartRequired;
        private final ConfigState.Saved<T> beforeSnapshot;
        private final RestartSyncStore store;
        private final ConfigBytes payload;
        private ConfigState.Transition<T> applied;
        private boolean publishedChanged;

        private Prepared(
            SyncedConfig<T> owner,
            T before,
            T candidate,
            T active,
            boolean restartRequired,
            ConfigState.Saved<T> beforeSnapshot,
            RestartSyncStore store,
            ConfigBytes payload
        ) {
            this.owner = owner;
            this.before = before;
            this.candidate = candidate;
            this.active = active;
            this.restartRequired = restartRequired;
            this.beforeSnapshot = beforeSnapshot;
            this.store = store;
            this.payload = payload;
        }

        boolean commitDeferred(RestartSyncStore.Transaction caches) {
            return owner.commit(this, caches);
        }

        void dispatch() {
            if (publishedChanged) {
                owner.dispatch(applied);
            }
        }

        boolean rollback() {
            return owner.rollbackCommitted(this);
        }

        boolean restartRequired() {
            return restartRequired;
        }

        TranslationKey disconnectReason(boolean joining) {
            if (!joining || !restartRequired || owner.restartSyncPolicy != RestartSyncPolicy.DISCONNECT) {
                return null;
            }
            return owner.config.pathResolver().type() == ConfigType.WORLD
                ? TranslationKey.RESTART_REQUIRED_WORLD : TranslationKey.RESTART_REQUIRED_SERVER;
        }
    }
}
