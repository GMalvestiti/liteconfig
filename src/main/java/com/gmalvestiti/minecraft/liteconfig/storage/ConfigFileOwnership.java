package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigFileOwnership {

    private final Map<String, OwnershipClaim> owners = new ConcurrentHashMap<>();

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
        return file.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private record OwnershipClaim(Class<?> owner, int references) {}
}
