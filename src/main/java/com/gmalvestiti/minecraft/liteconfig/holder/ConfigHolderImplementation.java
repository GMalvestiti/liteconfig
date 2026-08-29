package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSide;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigExecutors;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigEventExecutors;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ConfigHolderImplementation<T> implements ConfigHolder<T> {

    private static final String READ_ONLY = "read-only";
    private static final String READ_ONLY_REFUSAL = "holder.read-only";
    private static final List<Violation> READ_ONLY_VIOLATIONS = List.of(Violation.of(READ_ONLY_REFUSAL,
        "A read-only holder cannot update values; rebuild with a mutable holder to change them"));

    private final RegisteredConfig<T> registration;
    private final ConfigState<T> state;
    private final boolean readOnly;
    private final List<ConfigSubscription> ownedSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ConfigHolderImplementation(RegisteredConfig<T> registration, boolean readOnly) {
        this.registration = Objects.requireNonNull(registration, "registration");
        this.state = registration.state();
        this.readOnly = readOnly;
    }

    private ConfigScope scope() {
        return registration.model().scope();
    }

    private ConfigExceptionHandler exceptionHandler() {
        return registration.exceptionHandler();
    }

    @Override
    public T data() {
        return state.published();
    }

    @Override
    public ConfigMetadata metadata() {
        return registration.model().metadata();
    }

    @Override
    public T copy() {
        ensureOpen();
        return state.copyOfCanonical();
    }

    @Override
    public void load() {
        ensureOpen();
        if (readOnly) {
            exceptionHandler().reject(ConfigOperation.LOAD, unsupported(ConfigOperation.LOAD));
            return;
        }
        await(submit(this::loadState));
    }

    private void loadState() {
        try {
            ConfigOutcome<T> read = exceptionHandler().onRead(registration.model().type(), () -> {
                T candidate = registration.engine().load();
                registration.guard().validate(candidate);
                return candidate;
            });

            T loaded = read.valueOr(registration.engine()::initialize);
            ConfigState.Transition<T> transition = state.writing(() -> state.replace(loaded));

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
        ensureOpen();
        if (readOnly) {
            return refuseUpdate();
        }
        return await(submit(() -> updateState(mutator, false)));
    }

    @Override
    public UpdateResult updateAndSave(Consumer<T> mutator) {
        ensureOpen();
        if (readOnly) {
            return refuseUpdate();
        }
        return await(submit(() -> updateState(mutator, true)));
    }

    private UpdateResult updateState(Consumer<T> mutator, boolean save) {
        UpdateAttempt<T> attempt = state.writing(() -> {
            ConfigOutcome<T> candidate = exceptionHandler().onUpdate(() -> {
                Objects.requireNonNull(mutator, "mutator");

                T next = state.copyOfCanonical();
                mutator.accept(next);

                registration.guard().enforceRestart(state.canonical(), next);
                registration.guard().validate(next);

                return next;
            });

            if (candidate.degraded()) {
                return UpdateAttempt.rejected(candidate.violations());
            }

            T next = candidate.value().orElseThrow();
            if (save) {
                ConfigOutcome<Void> write = exceptionHandler().onWrite(
                    () -> registration.engine().save(next));
                if (write.degraded()) {
                    return UpdateAttempt.rejected(write.violations());
                }
            }

            return UpdateAttempt.published(state.replace(next));
        });

        if (attempt.rejected()) {
            return UpdateResult.rejected(attempt.violations());
        }

        ConfigState.Transition<T> transition = attempt.transition();
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
        ensureOpen();
        await(submit(this::saveState));
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

    @Override
    public CompletableFuture<Void> loadAsync() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }

        if (readOnly) {
            try {
                exceptionHandler().reject(ConfigOperation.LOAD, unsupported(ConfigOperation.LOAD));
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        return submit(this::loadState);
    }

    @Override
    public CompletableFuture<UpdateResult> updateAsync(Consumer<T> mutator) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }

        if (readOnly) {
            try {
                return CompletableFuture.completedFuture(refuseUpdate());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        return submit(() -> updateState(mutator, false));
    }

    @Override
    public CompletableFuture<UpdateResult> updateAndSaveAsync(Consumer<T> mutator) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }

        if (readOnly) {
            try {
                return CompletableFuture.completedFuture(refuseUpdate());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        return submit(() -> updateState(mutator, true));
    }

    @Override
    public CompletableFuture<Void> saveAsync() {
        return submit(this::saveState);
    }

    private UpdateResult refuseUpdate() {
        exceptionHandler().reject(ConfigOperation.UPDATE, unsupported(ConfigOperation.UPDATE));
        return UpdateResult.rejected(READ_ONLY_VIOLATIONS);
    }

    private LiteConfigException unsupported(ConfigOperation operation) {
        return scope().exception(
            ConfigError.HOLDER_OPERATION_UNSUPPORTED, READ_ONLY, operation.displayName());
    }

    private synchronized <V> CompletableFuture<V> submit(Supplier<V> task) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }

        if (ConfigExecutors.isWorkerThread()) {
            return CompletableFuture.failedFuture(scope().exception(ConfigError.NESTED_CONFIG_OPERATION));
        }

        CompletableFuture<V> submitted = registration.tasks().submit(task);

        return submitted.handle((value, failure) -> {
            if (failure instanceof RejectedExecutionException
                || failure instanceof CompletionException completion
                    && completion.getCause() instanceof RejectedExecutionException) {
                throw scope().exception(ConfigError.CONFIG_WORKER_STOPPED, failure);
            }

            if (failure != null) {
                throw new CompletionException(failure);
            }

            return value;
        });
    }

    private CompletableFuture<Void> submit(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
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

    @Override
    public ConfigSubscription onUpdate(ConfigSide side, Consumer<T> listener) {
        return subscribe(side, listener, registration.notifier()::addUpdateListener);
    }

    @Override
    public ConfigSubscription onLoad(ConfigSide side, Consumer<T> listener) {
        return subscribe(side, listener, registration.notifier()::addLoadListener);
    }

    @Override
    public ConfigSubscription onSave(ConfigSide side, Consumer<T> listener) {
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
                listener, subscribe, ConfigEventExecutors.logicalThread(ConfigSide.CLIENT));
            ConfigSubscription server = subscribe(
                listener, subscribe, ConfigEventExecutors.logicalThread(ConfigSide.SERVER));

            return ConfigSubscriptions.managed(() -> {
                client.close();
                server.close();
            });
        }

        return subscribe(listener, subscribe, ConfigEventExecutors.logicalThread(side));
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
        CompletableFuture<Void> release = registration.tasks().submitTerminal(
            () -> ConfigRegistry.release(registration));

        if (!ConfigExecutors.isWorkerThread()) {
            await(release);
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
        return scope().exception(ConfigError.HOLDER_CLOSED, registration.model().typeName());
    }

    private record UpdateAttempt<T>(
        ConfigState.Transition<T> transition,
        List<Violation> violations
    ) {
        private static <T> UpdateAttempt<T> published(ConfigState.Transition<T> transition) {
            return new UpdateAttempt<>(transition, List.of());
        }

        private static <T> UpdateAttempt<T> rejected(List<Violation> violations) {
            return new UpdateAttempt<>(null, List.copyOf(violations));
        }

        private boolean rejected() {
            return transition == null;
        }
    }
}
