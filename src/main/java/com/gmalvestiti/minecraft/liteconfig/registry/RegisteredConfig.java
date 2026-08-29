package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigExecutors;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigTaskQueue;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEngine;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventNotifier;
import com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigExceptionHandler;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigPathResolver;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigStorage;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigObjectFactory;
import com.gmalvestiti.minecraft.liteconfig.validation.ConfigGuard;
import com.gmalvestiti.minecraft.liteconfig.validation.ConfigModelValidator;

import java.util.Objects;
import java.util.function.Consumer;

public record RegisteredConfig<T>(
    ConfigModel<T> model,
    ConfigEngine<T> engine,
    ConfigGuard<T> guard,
    ConfigEventNotifier<T> notifier,
    ConfigExceptionHandler exceptionHandler,
    ConfigState<T> state,
    ConfigTaskQueue tasks
) {

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
            settings.baseDirectory(),
            model.scope(),
            ownership);

        try {
            ConfigStorage<T> storage = new ConfigStorage<>(model.type(), pathResolver, model.scope());

            ConfigExceptionHandler exceptionHandler = new ConfigExceptionHandler(
                model.scope(),
                declaration.readFailurePolicy(),
                declaration.writeFailurePolicy(),
                declaration.updateFailurePolicy(),
                ignored -> storage.backupCorrupted());

            ConfigModelValidator.validate(model);

            ConfigEngine<T> engine = new ConfigEngine<>(model, storage, stateCloner);

            ConfigGuard<T> guard = new ConfigGuard<>(model);

            RegisteredConfig<T> created = new RegisteredConfig<>(
                model,
                engine,
                guard,
                new ConfigEventNotifier<>(model.scope()),
                exceptionHandler,
                new ConfigState<>(stateCloner, model.scope(), initialStateOf(model, engine, exceptionHandler, guard)),
                ConfigExecutors.newSerialQueue());

            afterCreation.accept(created);

            return created;
        } catch (RuntimeException | Error failure) {
            pathResolver.releaseForConfig(model.type());
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
        ConfigGuard<T> guard
    ) {
        T defaults = engine.initialize();
        guard.validate(defaults);

        T initial = loaded(model, engine, exceptionHandler, guard, defaults);
        exceptionHandler.onWrite(() -> engine.save(initial));

        return initial;
    }

    private static <T> T loaded(
        ConfigModel<T> model,
        ConfigEngine<T> engine,
        ConfigExceptionHandler exceptionHandler,
        ConfigGuard<T> guard,
        T defaults
    ) {
        try {
            return exceptionHandler.onRead(model.type(), () -> {
                T candidate = engine.load(defaults);
                guard.validate(candidate);

                return candidate;
            }).valueOr(() -> defaults);
        } catch (LiteConfigException failure) {
            if (!failure.defect()) {
                model.scope().logError(failure.rawMessage(), failure);
            }

            throw failure;
        }
    }
}
