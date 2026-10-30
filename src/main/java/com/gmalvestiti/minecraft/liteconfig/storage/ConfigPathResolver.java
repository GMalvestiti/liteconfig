package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.platform.Platform;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ConfigPathResolver {

    private final Class<?> configType;
    private final ConfigScope scope;
    private final Path baseDirectory;
    private final ConfigFileOwnership ownership;
    private final ConfigType type;
    private final Path relativePath;
    private final Path installationPath;
    private final Path pendingRestartPath;
    private final AtomicReference<Path> claimed = new AtomicReference<>();
    private volatile Path worldDirectory;
    private volatile Path worldPath;
    private volatile boolean closed;

    public ConfigPathResolver(
        Class<?> configType,
        Path baseDirectory,
        ConfigScope scope,
        ConfigFileOwnership ownership
    ) {
        this.configType = Objects.requireNonNull(configType, "configType");
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.scope = scope;
        this.ownership = ownership;

        Config declaration = configType.getAnnotation(Config.class);
        if (declaration == null) {
            throw scope.exception(ConfigError.MISSING_CONFIG_MARKER, configType.getName());
        }

        this.type = declaration.type();
        this.installationPath = pathForConfig(configType, declaration, this.baseDirectory);
        this.relativePath = this.baseDirectory.relativize(installationPath);
        this.pendingRestartPath = this.baseDirectory.resolve("liteconfig").resolve("servers")
            .resolve("world").resolve(relativePath);
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public ConfigType type() {
        return type;
    }

    public Optional<Path> activePath() {
        return Optional.ofNullable(currentPath());
    }

    public Path restartPath() {
        Path current = currentPath();
        return current != null ? current : pendingRestartPath;
    }

    public Path resolve() {
        Path file = currentPath();
        if (file != null && !file.equals(claimed.get())) {
            ownership.claim(configType, file, scope);
            releaseClaim(claimed.getAndSet(file));
        }

        return file;
    }

    public boolean isLocallyWritable() {
        if (closed) {
            return false;
        }

        return switch (type) {
            case CLIENT -> Platform.isClient();
            case WORLD -> worldPath != null;
            case SERVER -> true;
        };
    }

    public Path currentPath() {
        if (closed) {
            return null;
        }

        if (type == ConfigType.WORLD) {
            return worldPath;
        }

        return isLocallyWritable() ? installationPath : null;
    }

    public boolean bindWorld(Path worldRoot) {
        if (closed || type != ConfigType.WORLD) {
            return false;
        }

        Path next = worldRoot == null ? null : worldRoot.toAbsolutePath().normalize().resolve("liteconfig");
        if (Objects.equals(worldDirectory, next)) {
            return false;
        }

        releaseClaim(claimed.getAndSet(null));

        worldDirectory = next;
        worldPath = next == null ? null : next.resolve(relativePath);

        try {
            resolve();
        } catch (RuntimeException | Error failure) {
            worldDirectory = null;
            worldPath = null;
            throw failure;
        }

        return true;
    }

    public void close() {
        closed = true;
        releaseClaim(claimed.getAndSet(null));
        worldDirectory = null;
        worldPath = null;
    }

    private void releaseClaim(Path file) {
        if (file != null) {
            ownership.release(configType, file);
        }
    }

    private Path pathForConfig(Class<?> configType, Config config, Path base) {
        Path root = config.modIdDirectory() ? scopedDirectory(configType, base) : base;
        String extension = config.format().extension();
        Path directory = resolveDirectory(configType, config.path(), root);
        Path file = directory.resolve(normalizeFileName(configType, config.name(), extension)).normalize();

        if (!file.startsWith(root)) {
            throw invalidPath(configType, config.path());
        }

        return file;
    }

    private Path scopedDirectory(Class<?> configType, Path base) {
        String modId = scope.modId();
        Path relative = toPath(modId, () -> invalidPath(configType, modId));

        if (unsafeDirectoryComponent(modId) || relative.isAbsolute() || relative.getRoot() != null
            || relative.getNameCount() != 1 || modId.equals(".") || modId.equals("..")) {
            throw invalidPath(configType, modId);
        }

        Path directory = base.resolve(relative).normalize();
        if (!base.equals(directory.getParent())) {
            throw invalidPath(configType, modId);
        }

        return directory;
    }

    private boolean unsafeDirectoryComponent(String value) {
        if (value.isBlank() || containsPathSeparator(value) || value.endsWith(".") || value.endsWith(" ")) {
            return true;
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || "<>:\"|?*".indexOf(character) >= 0) {
                return true;
            }
        }

        String stem = value.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return switch (stem) {
            case "CON", "PRN", "AUX", "NUL" -> true;
            default -> stem.matches("(?:COM|LPT)[1-9¹²³]");
        };
    }

    private Path resolveDirectory(Class<?> configType, String declaredPath, Path root) {
        if (declaredPath.isBlank()) {
            return root;
        }

        Path relative = toPath(declaredPath, () -> invalidPath(configType, declaredPath));
        if (relative.isAbsolute()) {
            throw invalidPath(configType, declaredPath);
        }

        return root.resolve(relative).normalize();
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
