package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.engine.state.StateCopies;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigObjectFactory;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigStorage;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ConfigEngine<T> {

    private static final String AFTER_LOAD = "afterLoad";
    private static final String BEFORE_SAVE = "beforeSave";

    private final ConfigModel<T> model;
    private final ConfigStorage<T> storage;
    private final StateCloner<T> cloner;

    public ConfigEngine(
        ConfigModel<T> model,
        ConfigStorage<T> storage,
        StateCloner<T> cloner
    ) {
        this.model = model;
        this.storage = storage;
        this.cloner = cloner;
    }

    public T initialize() {
        return ConfigObjectFactory.newInstance(model.type(), model.scope());
    }

    public T load() {
        return load(this::initialize);
    }

    public T load(T defaults) {
        Objects.requireNonNull(defaults, "defaults");
        return load(() -> defaults);
    }

    private T load(Supplier<T> defaults) {
        T loaded = storage.read();

        if (loaded == null) {
            loaded = defaults.get();
        }

        invoke(AFTER_LOAD, loaded, ConfigExtension::afterLoad);

        return loaded;
    }

    public void save(T data) {
        if (!model.extensions().hasBeforeSave()) {
            storage.write(data);
            return;
        }

        T candidate = StateCopies.isolated(cloner, data, model.scope());
        invoke(BEFORE_SAVE, candidate, ConfigExtension::beforeSave);
        storage.write(candidate);
    }

    private void invoke(String hook, T root, Consumer<ConfigExtension> call) {
        ConfigExtension extension = model.extensions().resolve(root);
        if (extension == null) {
            return;
        }

        try {
            call.accept(extension);
        } catch (LiteConfigException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw model.scope().exception(ConfigError.EXTENSION_HOOK_FAILED, ex,
                model.extensions().label() + "." + hook,
                ex
            );
        }
    }
}
