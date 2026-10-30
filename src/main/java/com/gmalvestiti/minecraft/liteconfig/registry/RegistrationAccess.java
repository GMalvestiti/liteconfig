package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventNotifier;
import com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigOutcome;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.shared.ConfigOperation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class RegistrationAccess<T> implements ConfigHolder<T> {

    private volatile RegisteredConfig<T> registration;
    private final ConfigState<T> state;
    private final ConfigScope configScope;
    private final String typeName;
    private final boolean readOnly;
    private volatile boolean closed;
    private boolean operationInProgress;
    private int callbackBatchesInProgress;

    RegistrationAccess(RegisteredConfig<T> registration) {
        this.registration = registration;
        this.state = registration.state();
        this.configScope = registration.model().scope();
        this.typeName = registration.model().typeName();
        this.readOnly = registration.model().type().getAnnotation(Config.class).readOnly();
    }

    @Override
    public T data() {
        return state.published();
    }

    @Override
    public ConfigMetadata metadata() {
        return openRegistration().model().metadata();
    }

    @Override
    public T copy() {
        return openState().copyOfCanonical();
    }

    @Override
    public Optional<T> pending() {
        return openState().pending();
    }

    @Override
    public ConfigType type() {
        return openRegistration().pathResolver().type();
    }

    @Override
    public boolean isActive() {
        return openRegistration().isActive();
    }

    @Override
    public Optional<Path> path() {
        return openRegistration().pathResolver().activePath();
    }

    @Override
    public void load() {
        operateWithCallbacks(callbacks -> {
            refusal(ConfigOperation.LOAD).ifPresentOrElse(
                reason -> reject(ConfigOperation.LOAD, reason), () -> loadState(callbacks));
            return null;
        });
    }

    private void loadState(RegisteredConfig.CallbackQueue callbacks) {
        ConfigOutcome<LocalChange<T>> read = registration.loader().read(
            () -> localLoad(registration.engine().load()));

        LocalChange<T> loaded = read.valueOr(() -> localLoad(registration.declaredDefaults()));

        if (read.completed()) {
            publishLocalChange(loaded, ConfigOperation.LOAD, callbacks);
            logCompleted(ConfigOperation.LOAD);
        } else {
            state.replaceLocal(loaded.active(), loaded.pending(),
                registration.pathResolver().restartPath());
        }
    }

    @Override
    public UpdateResult update(Consumer<T> mutator) {
        return applyUpdate(mutator, false);
    }

    @Override
    public UpdateResult updateAndSave(Consumer<T> mutator) {
        return applyUpdate(mutator, true);
    }

    private UpdateResult applyUpdate(Consumer<T> mutator, boolean save) {
        return operateWithCallbacks(callbacks -> {
            Optional<String> reason = refusal(ConfigOperation.UPDATE);
            return reason.map(this::refuseUpdate).orElseGet(() -> updateState(mutator, save, callbacks));
        });
    }

    private UpdateResult updateState(
        Consumer<T> mutator,
        boolean save,
        RegisteredConfig.CallbackQueue callbacks
    ) {
        ConfigType ownership = registration.pathResolver().type();

        ConfigOutcome<LocalChange<T>> candidate = registration.exceptionHandler().onUpdate(() -> {
            Objects.requireNonNull(mutator, "mutator");

            T next = ownership == ConfigType.SERVER ? state.copyOfCanonical()
                : state.pending().orElseGet(state::copyOfCanonical);

            if (ownership == ConfigType.SERVER) {
                Optional<T> pending = state.pending();
                if (pending.isPresent()) {
                    registration.guard().keepRestartValues(pending.orElseThrow(), next);
                }
            }

            mutator.accept(next);

            return localChange(next);
        });

        if (candidate.degraded()) {
            return UpdateResult.rejected(candidate.violations());
        }

        LocalChange<T> next = candidate.value().orElseThrow();
        if (save) {
            ConfigOutcome<Void> write = registration.exceptionHandler().onWrite(
                () -> registration.saveLocal(next.active(), next.pending()));
            if (write.degraded()) {
                return UpdateResult.rejected(write.violations());
            }
        }

        T published = publishLocalChange(next, ConfigOperation.UPDATE, callbacks);
        if (save) {
            notifySaved(published, callbacks);
        }

        return UpdateResult.published();
    }

    private LocalChange<T> localChange(T desired) {
        registration.guard().normalize(desired);
        registration.guard().validate(desired);

        if (!registration.guard().hasRestartChanges(state.canonical(), desired)) {
            return new LocalChange<>(desired, null);
        }

        T active = state.copyOf(desired);
        registration.guard().keepRestartValues(state.canonical(), active);
        registration.guard().validate(active);

        return new LocalChange<>(active, desired);
    }

    private LocalChange<T> localLoad(T desired) {
        if (registration.hasPendingRestartFile()) {
            state.pending().ifPresent(pending -> registration.guard().keepRestartValues(pending, desired));
        }
        return localChange(desired);
    }

    private T publishLocalChange(
        LocalChange<T> next,
        ConfigOperation operation,
        RegisteredConfig.CallbackQueue callbacks
    ) {
        ConfigState.Transition<T> transition = state.replaceLocal(next.active(), next.pending(),
            registration.pathResolver().restartPath());
        queueChangeCallbacks(transition, operation, callbacks);
        return transition.published();
    }

    private record LocalChange<T>(T active, T pending) {}

    @Override
    public void save() {
        operateWithCallbacks(callbacks -> {
            refusal(ConfigOperation.SAVE).ifPresentOrElse(
                reason -> reject(ConfigOperation.SAVE, reason), () -> saveState(callbacks));
            return null;
        });
    }

    private void saveState(RegisteredConfig.CallbackQueue callbacks) {
        T canonical = state.canonical();
        T published = state.published();
        if (registration.exceptionHandler().onWrite(
            () -> registration.saveLocal(canonical)).completed()) {
            notifySaved(published, callbacks);
        }
    }

    private void queueChangeCallbacks(
        ConfigState.Transition<T> transition,
        ConfigOperation operation,
        RegisteredConfig.CallbackQueue callbacks
    ) {
        RegisteredConfig<T> current = registration;
        callbacks.enqueue(current.model().listeners().enqueueChanged(
            transition.before(), transition.after(), false));

        if (operation == ConfigOperation.LOAD) {
            callbacks.enqueue(() -> current.holderEvents().notifyLoaded(transition.published()));
        } else {
            callbacks.enqueue(() -> current.holderEvents().notifyUpdated(transition.published()));
        }
        callbacks.enqueue(current.syncNotifier()::notifyLocalChange);
    }

    private void notifySaved(T published, RegisteredConfig.CallbackQueue callbacks) {
        RegisteredConfig<T> current = registration;
        logCompleted(ConfigOperation.SAVE);
        callbacks.enqueue(() -> current.holderEvents().notifySaved(published));
    }

    private Optional<String> refusal(ConfigOperation operation) {
        if (state.remoteActive()) {
            return Optional.of("remote");
        }

        if (!registration.isLocallyWritable()) {
            return Optional.of("inactive");
        }

        return operation != ConfigOperation.SAVE && readOnly ? Optional.of("read-only") : Optional.empty();
    }

    private UpdateResult refuseUpdate(String reason) {
        reject(ConfigOperation.UPDATE, reason);
        return UpdateResult.rejected(List.of(Violation.of("holder." + reason,
            "A " + reason + " config cannot be updated locally")));
    }

    private void reject(ConfigOperation operation, String reason) {
        registration.exceptionHandler().reject(operation, configScope.exception(
            ConfigError.HOLDER_OPERATION_UNSUPPORTED, reason, operation.displayName()));
    }

    <V> V operate(Supplier<V> operation) {
        ensureOpen();
        return state.writing(() -> runOperation(operation));
    }

    <V> V operateWithCallbacks(Function<RegisteredConfig.CallbackQueue, V> operation) {
        ensureOpen();
        return writeWithCallbacks(operation, false);
    }

    void operateWithCallbacksIfOpen(Consumer<RegisteredConfig.CallbackQueue> operation) {
        writeWithCallbacks(callbacks -> {
            operation.accept(callbacks);
            return null;
        }, true);
    }

    private <V> V writeWithCallbacks(
        Function<RegisteredConfig.CallbackQueue, V> operation,
        boolean skipWhenClosed
    ) {
        List<Runnable> deferredCallbacks = new ArrayList<>();
        boolean[] callbackQueued = {false};
        RegisteredConfig.CallbackQueue callbackQueue = callback -> {
            if (!callbackQueued[0]) {
                callbackBatchesInProgress++;
                callbackQueued[0] = true;
            }
            deferredCallbacks.add(Objects.requireNonNull(callback, "callback"));
        };

        V result;
        try {
            result = state.writing(() -> {
                if (skipWhenClosed && closed) {
                    return null;
                }
                return runOperation(() -> operation.apply(callbackQueue));
            });
        } catch (RuntimeException | Error failure) {
            if (callbackQueued[0]) {
                finishCallbackBatch(failure);
            }
            throw failure;
        }

        Throwable callbackFailure = null;
        try {
            for (Runnable callback : deferredCallbacks) {
                callback.run();
            }
            return result;
        } catch (RuntimeException | Error failure) {
            callbackFailure = failure;
            throw failure;
        } finally {
            if (callbackQueued[0]) {
                finishCallbackBatch(callbackFailure);
            }
        }
    }

    private void finishCallbackBatch(Throwable failure) {
        try {
            synchronized (state.operationLock()) {
                callbackBatchesInProgress--;
                if (closed && !operationInProgress && callbackBatchesInProgress == 0) {
                    release();
                }
            }
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure == null) {
                throw cleanupFailure;
            }
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    <V> V runOperation(Supplier<V> operation) {
        ensureOpen();
        operationInProgress = true;
        Throwable failure = null;

        try {
            return operation.get();
        } catch (RuntimeException | Error raised) {
            failure = raised;
            throw raised;
        } finally {
            operationInProgress = false;
            if (closed && callbackBatchesInProgress == 0) {
                try {
                    release();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure == null) {
                        throw cleanupFailure;
                    }
                    if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
    }

    private <V> V locked(Supplier<V> work) {
        synchronized (state.operationLock()) {
            return work.get();
        }
    }

    @Override
    public ConfigSubscription onUpdate(Consumer<T> listener) {
        return subscribe(listener, notifier -> notifier.addUpdateEvent(listener));
    }

    @Override
    public ConfigSubscription onLoad(Consumer<T> listener) {
        return subscribe(listener, notifier -> notifier.addLoadEvent(listener));
    }

    @Override
    public ConfigSubscription onSave(Consumer<T> listener) {
        return subscribe(listener, notifier -> notifier.addSaveEvent(listener));
    }

    private ConfigSubscription subscribe(
        Consumer<T> listener,
        Function<ConfigEventNotifier<T>, ConfigSubscription> eventRegistration
    ) {
        return locked(() -> closed || listener == null ? ConfigSubscription.NONE :
            eventRegistration.apply(registration.holderEvents()));
    }

    @Override
    public void close() {
        locked(() -> {
            if (!closed) {
                closed = true;
                registration.holderEvents().close();
                if (!operationInProgress && callbackBatchesInProgress == 0) {
                    release();
                }
            }
            return null;
        });
    }

    private void release() {
        try {
            ConfigRegistry.unregister(registration);
        } finally {
            registration = null;
        }
    }

    boolean isClosed() {
        return closed;
    }

    private void logCompleted(ConfigOperation operation) {
        configScope.logInfo("Config %s operation completed successfully".formatted(operation.displayName()));
    }

    private RegisteredConfig<T> openRegistration() {
        RegisteredConfig<T> current = registration;
        ensureOpen();
        return current;
    }

    private ConfigState<T> openState() {
        ensureOpen();
        return state;
    }

    void ensureOpen() {
        if (closed) {
            throw configScope.exception(ConfigError.HOLDER_CLOSED, typeName);
        }
    }
}
