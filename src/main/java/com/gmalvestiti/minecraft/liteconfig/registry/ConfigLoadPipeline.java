package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEngine;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigExceptionHandler;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigOutcome;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.validation.ConfigGuard;

import java.util.function.Supplier;

final class ConfigLoadPipeline<T> {

    private final ConfigModel<T> model;
    private final ConfigEngine<T> engine;
    private final ConfigExceptionHandler exceptionHandler;
    private final ConfigGuard<T> guard;

    ConfigLoadPipeline(
        ConfigModel<T> model,
        ConfigEngine<T> engine,
        ConfigExceptionHandler exceptionHandler,
        ConfigGuard<T> guard
    ) {
        this.model = model;
        this.engine = engine;
        this.exceptionHandler = exceptionHandler;
        this.guard = guard;
    }

    <R> ConfigOutcome<R> read(Supplier<R> load) {
        try {
            return exceptionHandler.onRead(model.type(), load);
        } catch (LiteConfigException failure) {
            if (!failure.defect()) {
                model.scope().logError(failure.rawMessage(), failure);
            }
            throw failure;
        }
    }

    ConfigOutcome<T> readValidated(Supplier<T> defaults) {
        return read(() -> {
            T candidate = engine.loadOr(defaults);
            guard.normalize(candidate);
            guard.validate(candidate);
            return candidate;
        });
    }

    void persist(ConfigOutcome<?> read, T value) {
        if (!read.ioLoadFailed()) {
            exceptionHandler.onWrite(() -> engine.saveLoaded(value));
        }
    }
}
