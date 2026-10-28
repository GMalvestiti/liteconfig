package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEngine;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventNotifier;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigSyncNotifier;
import com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState;
import com.gmalvestiti.minecraft.liteconfig.engine.state.StateCopies;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncSchema;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigExceptionHandler;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigOutcome;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigPathResolver;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigStorage;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigObjectFactory;
import com.gmalvestiti.minecraft.liteconfig.validation.ConfigGuard;
import com.gmalvestiti.minecraft.liteconfig.validation.ConfigModelValidator;

import java.util.Objects;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RegisteredConfig<T> {

    @FunctionalInterface
    public interface CallbackQueue {
        void enqueue(Runnable callback);
    }

    private final ConfigModel<T> model;
    private final ConfigEngine<T> engine;
    private final ConfigGuard<T> guard;
    private final ConfigSyncSchema<T> syncSchema;
    private final ConfigEventNotifier<T> holderEvents;
    private final ConfigSyncNotifier<T> syncNotifier;
    private final ConfigExceptionHandler exceptionHandler;
    private final ConfigState<T> state;
    private final ConfigPathResolver pathResolver;
    private final T declaredDefaults;
    private final RegistrationAccess<T> access;

    public RegisteredConfig(
        ConfigModel<T> model,
        ConfigEngine<T> engine,
        ConfigGuard<T> guard,
        ConfigSyncSchema<T> syncSchema,
        ConfigEventNotifier<T> holderEvents,
        ConfigSyncNotifier<T> syncNotifier,
        ConfigExceptionHandler exceptionHandler,
        ConfigState<T> state,
        ConfigPathResolver pathResolver,
        T declaredDefaults
    ) {
        this.model = model;
        this.engine = engine;
        this.guard = guard;
        this.syncSchema = syncSchema;
        this.holderEvents = holderEvents;
        this.syncNotifier = syncNotifier;
        this.exceptionHandler = exceptionHandler;
        this.state = state;
        this.pathResolver = pathResolver;
        this.declaredDefaults = declaredDefaults;
        this.access = new RegistrationAccess<>(this);
    }

    public ConfigModel<T> model() {
        return model;
    }

    public ConfigEngine<T> engine() {
        return engine;
    }

    public ConfigGuard<T> guard() {
        return guard;
    }

    public ConfigSyncSchema<T> syncSchema() {
        return syncSchema;
    }

    public ConfigEventNotifier<T> holderEvents() {
        return holderEvents;
    }

    public ConfigSyncNotifier<T> syncNotifier() {
        return syncNotifier;
    }

    public ConfigExceptionHandler exceptionHandler() {
        return exceptionHandler;
    }

    public ConfigState<T> state() {
        return state;
    }

    public ConfigPathResolver pathResolver() {
        return pathResolver;
    }

    public ConfigHolder<T> access() {
        return access;
    }

    public <R> R writing(Supplier<R> work) {
        return access.operate(work);
    }

    public void writingWithCallbacks(Consumer<CallbackQueue> work) {
        access.operateWithCallbacks(callbacks -> {
            work.accept(callbacks);
            return null;
        });
    }

    public boolean isClosed() {
        return access.isClosed();
    }

    void ensureOpen() {
        access.ensureOpen();
    }

    public boolean isActive() {
        return isLocallyWritable() || state.remoteActive();
    }

    public boolean isLocallyWritable() {
        return pathResolver.isLocallyWritable(model.type());
    }

    public T declaredDefaults() {
        return state.copyOf(declaredDefaults);
    }

    public void saveLocal(T data) {
        T pending = hasPendingRestartFile() ? state.pending().orElse(null) : null;
        saveLocal(data, pending);
    }

    void saveLocal(T data, T pending) {
        if (pending == null) {
            engine.save(data);
        } else {
            engine.save(data, candidate -> {
                guard.keepRestartValues(pending, candidate);
                guard.normalize(candidate);
                guard.validate(candidate);
            });
        }
    }

    public boolean hasPendingRestartFile() {
        return state.pendingAt(pathResolver.restartPathForConfig(model.type()));
    }

    void worldContext(Path worldRoot, boolean notify) {
        if (model.type().getAnnotation(Config.class).type() != ConfigType.WORLD) {
            return;
        }

        access.operateWithCallbacksIfOpen(callbacks -> {
            T next;
            boolean loadedSuccessfully = false;

            try {
                if (!pathResolver.bindWorld(model.type(), worldRoot)) {
                    return;
                }

                ConfigSyncRegistry.refreshLocalContext(this);

                if (isLocallyWritable()) {
                    ConfigOutcome<T> read = exceptionHandler.onRead(model.type(), () -> {
                        T candidate = engine.load(declaredDefaults());
                        guard.normalize(candidate);

                        guard.validate(candidate);
                        return candidate;
                    });

                    next = read.valueOr(this::declaredDefaults);
                    loadedSuccessfully = read.completed();
                    T loaded = next;

                    if (!read.ioLoadFailed()) {
                        exceptionHandler.onWrite(() -> engine.saveLoaded(loaded));
                    }
                } else {
                    next = declaredDefaults();
                }
            } catch (RuntimeException | Error failure) {
                pathResolver.bindWorld(model.type(), null);
                ConfigSyncRegistry.refreshLocalContext(this);
                state.replaceLocal(declaredDefaults(), null, null);
                throw failure;
            }

            ConfigState.Transition<T> transition = state.replaceLocal(next, null, null);
            if (notify) {
                callbacks.enqueue(model.listeners().enqueueChanged(
                    transition.before(), transition.after(), false));

                if (loadedSuccessfully) {
                    callbacks.enqueue(() -> holderEvents.notifyLoaded(transition.published()));
                } else if (!isLocallyWritable()) {
                    callbacks.enqueue(() -> holderEvents.notifyUpdated(transition.published()));
                }

                callbacks.enqueue(syncNotifier::notifyLocalChange);
            }
        });
    }

    public static <T> RegisteredConfig<T> create(
        ConfigSettings<T> settings,
        ConfigFileOwnership ownership,
        Consumer<? super RegisteredConfig<T>> afterCreation
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(afterCreation, "afterCreation");

        Config declaration = settings.type().getAnnotation(Config.class);
        if (declaration == null) {
            throw settings.scope().exception(ConfigError.MISSING_CONFIG_MARKER, settings.type().getName());
        }

        ConfigModel<T> model = ConfigModel.of(settings.type(), settings.scope());
        StateCloner<T> stateCloner = stateClonerOf(declaration, model.scope());

        ConfigPathResolver pathResolver = new ConfigPathResolver(
            model.type(),
            settings.baseDirectory(),
            model.scope(),
            ownership);

        RegisteredConfig<T> created = null;
        try {
            ConfigStorage<T> storage = new ConfigStorage<>(model.type(), pathResolver, model.scope());

            ConfigExceptionHandler exceptionHandler = new ConfigExceptionHandler(
                model.scope(),
                declaration.readFailurePolicy(),
                declaration.writeFailurePolicy(),
                declaration.updateFailurePolicy(),
                ignored -> storage.backupCorrupted());

            ConfigModelValidator.validate(model);

            ConfigSyncSchema<T> syncSchema = ConfigSyncSchema.create(model);
            ConfigGuard<T> guard = new ConfigGuard<>(model, syncSchema);

            ConfigEngine<T> engine = new ConfigEngine<>(model, storage, stateCloner, guard::normalize);
            T defaults = engine.initialize();
            guard.normalize(defaults);
            guard.validate(defaults);

            T declaredDefaults = StateCopies.isolated(stateCloner, defaults, model.scope());

            ConfigState<T> state = new ConfigState<>(stateCloner, model.scope(),
                initialStateOf(model, engine, exceptionHandler, guard,
                    () -> StateCopies.isolated(stateCloner, declaredDefaults, model.scope()),
                    pathResolver.isLocallyWritable(model.type())));

            ConfigEventNotifier<T> holderEvents = new ConfigEventNotifier<>(model.scope(), state.operationLock());

            ConfigSyncNotifier<T> syncNotifier = new ConfigSyncNotifier<>(
                model.scope(), holderEvents::notifyUpdated);

            created = new RegisteredConfig<>(
                model,
                engine,
                guard,
                syncSchema,
                holderEvents,
                syncNotifier,
                exceptionHandler,
                state,
                pathResolver,
                declaredDefaults);

            afterCreation.accept(created);
            created.access.ensureOpen();

            return created;
        } catch (RuntimeException | Error failure) {
            try {
                if (created == null) {
                    pathResolver.closeForConfig(model.type());
                } else {
                    created.access.close();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> StateCloner<T> stateClonerOf(Config declaration, ConfigScope scope) {
        return (StateCloner<T>) ConfigObjectFactory.newInstance(declaration.stateCloner(), scope);
    }

    private static <T> T initialStateOf(
        ConfigModel<T> model,
        ConfigEngine<T> engine,
        ConfigExceptionHandler exceptionHandler,
        ConfigGuard<T> guard,
        Supplier<T> defaults,
        boolean locallyWritable
    ) {
        if (!locallyWritable) {
            return defaults.get();
        }

        T initial;
        boolean ioLoadFailed;
        try {
            ConfigOutcome<T> read = exceptionHandler.onRead(model.type(), () -> {
                T candidate = engine.load(defaults.get());

                guard.normalize(candidate);
                guard.validate(candidate);

                return candidate;
            });
            initial = read.valueOr(defaults);
            ioLoadFailed = read.ioLoadFailed();
        } catch (LiteConfigException failure) {
            if (!failure.defect()) {
                model.scope().logError(failure.rawMessage(), failure);
            }

            throw failure;
        }

        if (!ioLoadFailed) {
            exceptionHandler.onWrite(() -> engine.save(initial));
        }

        return initial;
    }
}
