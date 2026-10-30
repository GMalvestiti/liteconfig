package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigFileOwnership {

    private static final boolean FOLD_CASE = foldsCase(System.getProperty("os.name", ""));

    private final Map<String, OwnershipClaim> owners = new ConcurrentHashMap<>();
    private final boolean foldCase;

    public ConfigFileOwnership() {
        this(FOLD_CASE);
    }

    ConfigFileOwnership(boolean foldCase) {
        this.foldCase = foldCase;
    }

    private static boolean foldsCase(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        return name.contains("win") || name.contains("mac") || name.contains("darwin");
    }

    public void claim(Class<?> configType, Path file, ConfigScope scope) {
        OwnershipClaim claim = owners.compute(keyOf(file), (key, current) -> {
            if (current == null) {
                return new OwnershipClaim(configType, 1);
            }

            return current.owner() == configType
                ? new OwnershipClaim(configType, current.references() + 1)
                : current;
        });

        if (claim.owner() != configType) {
            throw scope.exception(ConfigError.CONFLICTING_CONFIG_PATH, configType.getName(), file, claim.owner().getName());
        }
    }

    public void release(Class<?> configType, Path file) {
        owners.computeIfPresent(keyOf(file), (key, current) -> {
            if (current.owner() != configType) {
                return current;
            }

            return current.references() == 1
                ? null
                : new OwnershipClaim(configType, current.references() - 1);
        });
    }

    private String keyOf(Path file) {
        String key = file.toAbsolutePath().normalize().toString();
        return foldCase ? key.toLowerCase(Locale.ROOT) : key;
    }

    private record OwnershipClaim(Class<?> owner, int references) {}
}
