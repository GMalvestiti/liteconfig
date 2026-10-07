package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSide;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventThreads;
import com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigSubscriptions;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigExceptionHandler;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigOutcome;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.shared.ConfigOperation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ConfigHolderImplementation<T> implements ConfigHolder<T> {

    private static final String READ_ONLY = "read-only";
    private static final String READ_ONLY_REFUSAL = "holder.read-only";
    private static final List<Violation> READ_ONLY_VIOLATIONS = List.of(Violation.of(READ_ONLY_REFUSAL,
        "A read-only holder cannot update values; rebuild with a mutable holder to change them"));

    private RegisteredConfig<T> registration;
    private volatile ConfigState<T> state;
    private final ConfigScope configScope;
    private final String typeName;
    private final boolean readOnly;
    private final List<ConfigSubscription> ownedSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean operationInProgress;

    public ConfigHolderImplementation(RegisteredConfig<T> registration, boolean readOnly) {
        this.registration = Objects.requireNonNull(registration, "registration");
        this.state = registration.state();
        this.configScope = registration.model().scope();
        this.typeName = registration.model().typeName();
        this.readOnly = readOnly;
    }

    private ConfigScope scope() {
        return configScope;
    }

    private ConfigExceptionHandler exceptionHandler() {
        return registration.exceptionHandler();
    }

    @Override
    public T data() {
        return state.published();
    }

    @Override
    public synchronized ConfigMetadata metadata() {
        ensureOpen();
        return registration.model().metadata();
    }

    @Override
    public synchronized T copy() {
        ensureOpen();
        return state.copyOfCanonical();
    }

    @Override
    public void load() {
        operate(() -> {
            if (readOnly) {
                exceptionHandler().reject(ConfigOperation.LOAD, unsupported(ConfigOperation.LOAD));
            } else {
                loadState();
            }
            return null;
        });
    }

    private void loadState() {
        try {
            ConfigOutcome<T> read = exceptionHandler().onRead(registration.model().type(), () -> {
                T candidate = registration.engine().load();
                registration.guard().validate(candidate);
                return candidate;
            });

            T loaded = read.valueOr(registration.engine()::initialize);
            ConfigState.Transition<T> transition = state.replace(loaded);

            if (read.completed()) {
                registration.model().callbacks().enqueueChanged(
                    transition.before(), transition.after(), false).run();
                registration.notifier().notifyLoaded(transition.published());

                logCompleted(ConfigOperation.LOAD);
            }
        } catch (LiteConfigException failure) {
            if (!failure.defect()) {
                scope().logError(failure.rawMessage(), failure);
            }
            throw failure;
        }
    }

    @Override
    public UpdateResult update(Consumer<T> mutator) {
        return operate(() -> readOnly ? refuseUpdate() : updateState(mutator, false));
    }

    @Override
    public UpdateResult updateAndSave(Consumer<T> mutator) {
        return operate(() -> readOnly ? refuseUpdate() : updateState(mutator, true));
    }

    private UpdateResult updateState(Consumer<T> mutator, boolean save) {
        ConfigOutcome<T> candidate = exceptionHandler().onUpdate(() -> {
            Objects.requireNonNull(mutator, "mutator");

            T next = state.copyOfCanonical();
            mutator.accept(next);

            registration.guard().enforceRestart(state.canonical(), next);
            registration.guard().validate(next);

            return next;
        });

        if (candidate.degraded()) {
            return UpdateResult.rejected(candidate.violations());
        }

        T next = candidate.value().orElseThrow();
        if (save) {
            ConfigOutcome<Void> write = exceptionHandler().onWrite(
                () -> registration.engine().save(next));
            if (write.degraded()) {
                return UpdateResult.rejected(write.violations());
            }
        }

        ConfigState.Transition<T> transition = state.replace(next);
        registration.model().callbacks().enqueueChanged(
            transition.before(), transition.after(), false).run();
        registration.notifier().notifyUpdated(transition.published());

        if (save) {
            logCompleted(ConfigOperation.SAVE);
            registration.notifier().notifySaved(transition.published());
        }

        return UpdateResult.published();
    }

    @Override
    public void save() {
        operate(() -> {
            saveState();
            return null;
        });
    }

    private void saveState() {
        ConfigState.View<T> saved = state.current();

        if (exceptionHandler().onWrite(
            () -> registration.engine().save(saved.canonical())
        ).completed()) {
            logCompleted(ConfigOperation.SAVE);
            registration.notifier().notifySaved(saved.published());
        }
    }

    private UpdateResult refuseUpdate() {
        exceptionHandler().reject(ConfigOperation.UPDATE, unsupported(ConfigOperation.UPDATE));
        return UpdateResult.rejected(READ_ONLY_VIOLATIONS);
    }

    private LiteConfigException unsupported(ConfigOperation operation) {
        return scope().exception(
            ConfigError.HOLDER_OPERATION_UNSUPPORTED, READ_ONLY, operation.displayName());
    }

    private synchronized <V> V operate(Supplier<V> operation) {
        ensureOpen();

        if (operationInProgress) {
            throw scope().exception(ConfigError.NESTED_CONFIG_OPERATION);
        }

        operationInProgress = true;
        try {
            return state.writing(operation);
        } finally {
            operationInProgress = false;
            if (closed.get()) {
                release();
            }
        }
    }

    @Override
    public synchronized ConfigSubscription onUpdate(ConfigSide side, Consumer<T> listener) {
        if (closed.get()) {
            Objects.requireNonNull(side, "side");
            return ConfigSubscription.NONE;
        }
        return subscribe(side, listener, registration.notifier()::addUpdateListener);
    }

    @Override
    public synchronized ConfigSubscription onLoad(ConfigSide side, Consumer<T> listener) {
        if (closed.get()) {
            Objects.requireNonNull(side, "side");
            return ConfigSubscription.NONE;
        }
        return subscribe(side, listener, registration.notifier()::addLoadListener);
    }

    @Override
    public synchronized ConfigSubscription onSave(ConfigSide side, Consumer<T> listener) {
        if (closed.get()) {
            Objects.requireNonNull(side, "side");
            return ConfigSubscription.NONE;
        }
        return subscribe(side, listener, registration.notifier()::addSaveListener);
    }

    private ConfigSubscription subscribe(
        ConfigSide side,
        Consumer<T> listener,
        BiFunction<Consumer<T>, Executor, ConfigSubscription> subscribe
    ) {
        Objects.requireNonNull(side, "side");
        if (listener == null) {
            return ConfigSubscription.NONE;
        }

        if (side == ConfigSide.BOTH) {
            ConfigSubscription client = subscribe(
                listener, subscribe, ConfigEventThreads.logicalThread(ConfigSide.CLIENT));
            ConfigSubscription server = subscribe(
                listener, subscribe, ConfigEventThreads.logicalThread(ConfigSide.SERVER));

            return ConfigSubscriptions.managed(() -> {
                client.close();
                server.close();
            });
        }

        return subscribe(listener, subscribe, ConfigEventThreads.logicalThread(side));
    }

    private synchronized ConfigSubscription subscribe(
        Consumer<T> listener,
        BiFunction<Consumer<T>, Executor, ConfigSubscription> subscribe,
        Executor executor
    ) {
        if (listener == null) {
            return ConfigSubscription.NONE;
        }

        ConfigSubscription subscription = subscribe.apply(listener, executor);
        if (closed.get()) {
            subscription.close();
            return ConfigSubscription.NONE;
        }

        ConfigSubscription owned = ConfigSubscriptions.owned(subscription, ownedSubscriptions);
        ownedSubscriptions.add(owned);

        return owned;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        List.copyOf(ownedSubscriptions).forEach(ConfigSubscription::close);
        if (!operationInProgress) {
            release();
        }
    }

    private void release() {
        try {
            ConfigRegistry.release(registration);
        } finally {
            registration = null;
            state = null;
        }
    }

    private void logCompleted(ConfigOperation operation) {
        scope().logInfo("Config %s operation completed successfully".formatted(operation.displayName()));
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw closedFailure();
        }
    }

    private LiteConfigException closedFailure() {
        return scope().exception(ConfigError.HOLDER_CLOSED, typeName);
    }
}
