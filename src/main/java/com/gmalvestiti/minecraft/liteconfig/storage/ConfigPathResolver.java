package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Supplier;

public final class ConfigPathResolver {

    private final ConfigScope scope;
    private final Path baseDirectory;
    private final ConfigFileOwnership ownership;

    public ConfigPathResolver(Path baseDirectory, ConfigScope scope, ConfigFileOwnership ownership) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.scope = scope;
        this.ownership = ownership;
    }

    public Path resolveForConfig(Class<?> configType) {
        Path file = pathForConfig(configType);
        ownership.claim(configType, file, scope);
        return file;
    }

    public void releaseForConfig(Class<?> configType) {
        ownership.release(configType, pathForConfig(configType));
    }

    private Path pathForConfig(Class<?> configType) {
        Config config = configType.getAnnotation(Config.class);
        if (config == null) {
            throw scope.exception(ConfigError.MISSING_CONFIG_MARKER, configType.getName());
        }

        String extension = config.format().extension();
        Path directory = resolveDirectory(configType, config.path());
        Path file = directory.resolve(normalizeFileName(configType, config.name(), extension)).normalize();

        if (!file.startsWith(baseDirectory)) {
            throw invalidPath(configType, config.path());
        }

        return file;
    }

    private Path resolveDirectory(Class<?> configType, String declaredPath) {
        if (declaredPath.isBlank()) {
            return baseDirectory;
        }

        Path relative = toPath(declaredPath, () -> invalidPath(configType, declaredPath));
        if (relative.isAbsolute()) {
            throw invalidPath(configType, declaredPath);
        }

        return baseDirectory.resolve(relative).normalize();
    }

    private String normalizeFileName(Class<?> configType, String name, String extension) {
        if (name.isBlank() || containsPathSeparator(name) || name.equalsIgnoreCase(extension)) {
            throw invalidName(configType, name);
        }

        Path candidate = toPath(name, () -> invalidName(configType, name));
        if (candidate.isAbsolute() || candidate.getNameCount() != 1) {
            throw invalidName(configType, name);
        }

        return endsWith(name, extension) ? name : name + extension;
    }

    private Path toPath(String value, Supplier<LiteConfigException> onFailure) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ex) {
            throw onFailure.get();
        }
    }

    private boolean endsWith(String name, String extension) {
        int offset = name.length() - extension.length();
        return offset > 0 && name.regionMatches(true, offset, extension, 0, extension.length());
    }

    private boolean containsPathSeparator(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }

    private LiteConfigException invalidName(Class<?> configType, String name) {
        return scope.exception(ConfigError.INVALID_CONFIG_NAME, configType.getName(), name);
    }

    private LiteConfigException invalidPath(Class<?> configType, String path) {
        return scope.exception(ConfigError.INVALID_CONFIG_PATH, configType.getName(), path);
    }
}
