package com.gmalvestiti.minecraft.liteconfig.support;

import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;

public final class RegisteredConfigs {

    private RegisteredConfigs() {}

    public static <T> RegisteredConfig<T> create(ConfigSettings<T> settings) {
        return create(settings, new ConfigFileOwnership());
    }

    public static <T> RegisteredConfig<T> create(
        ConfigSettings<T> settings,
        ConfigFileOwnership ownership
    ) {
        return RegisteredConfig.create(settings, ownership, ignored -> {});
    }
}
