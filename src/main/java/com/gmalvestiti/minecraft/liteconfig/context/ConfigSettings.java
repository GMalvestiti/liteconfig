package com.gmalvestiti.minecraft.liteconfig.context;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

import java.nio.file.Path;
import java.util.Objects;

public record ConfigSettings<T>(
    Class<T> type,
    ConfigScope scope,
    Path baseDirectory
) {
    public ConfigSettings {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory")
            .toAbsolutePath()
            .normalize();
    }
}
