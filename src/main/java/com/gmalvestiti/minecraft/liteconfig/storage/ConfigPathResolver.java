package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.platform.Platform;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ConfigPathResolver {

    private final ConfigScope scope;
    private final Path baseDirectory;
    private final ConfigFileOwnership ownership;
    private final Map<Class<?>, ConfigPath> paths = new ConcurrentHashMap<>();
    private final Map<Class<?>, FileClaim> claims = new ConcurrentHashMap<>();
    private volatile Path worldDirectory;
    private volatile boolean closed;
    private volatile Class<?> primaryConfigType;

    public ConfigPathResolver(Path baseDirectory, ConfigScope scope, ConfigFileOwnership ownership) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.scope = scope;
        this.ownership = ownership;
    }

    public ConfigPathResolver(
        Class<?> configType,
        Path baseDirectory,
        ConfigScope scope,
        ConfigFileOwnership ownership
    ) {
        this(baseDirectory, scope, ownership);
        primaryConfigType = Objects.requireNonNull(configType, "configType");
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public ConfigType type() {
        Class<?> current = primaryConfigType;
        return current == null ? ConfigType.SERVER : pathOf(current).type();
    }

    public Optional<Path> activePath() {
        Class<?> current = primaryConfigType;
        return current == null ? Optional.empty() : Optional.ofNullable(currentPathForConfig(current));
    }

    public Path restartPathForConfig(Class<?> configType) {
        Path current = currentPathForConfig(configType);
        return current != null ? current : baseDirectory.resolve("liteconfig").resolve("servers")
            .resolve("world").resolve(pathOf(configType).relativePath());
    }

    public Path resolveForConfig(Class<?> configType) {
        Path file = currentPathForConfig(configType);
        if (file == null) {
            return null;
        }

        claims.compute(configType, (type, current) -> {
            ownership.claim(type, file, scope);
            return new FileClaim(file, current == null ? 1 : current.references() + 1);
        });

        return file;
    }

    public boolean isLocallyWritable(Class<?> configType) {
        return isLocallyWritable(pathOf(configType).type());
    }

    private boolean isLocallyWritable(ConfigType type) {
        if (closed) {
            return false;
        }

        return switch (type) {
            case CLIENT -> Platform.isClient();
            case WORLD -> worldDirectory != null;
            case SERVER -> true;
        };
    }

    public Path currentPathForConfig(Class<?> configType) {
        ConfigPath configPath = pathOf(configType);
        if (primaryConfigType == null) {
            primaryConfigType = configType;
        }

        if (closed) {
            return null;
        }

        if (configPath.type() == ConfigType.WORLD) {
            Path directory = worldDirectory;
            return directory == null ? null : directory.resolve(configPath.relativePath());
        }

        return isLocallyWritable(configPath.type()) ? configPath.installationPath() : null;
    }

    public boolean bindWorld(Class<?> configType, Path worldRoot) {
        if (closed || pathOf(configType).type() != ConfigType.WORLD) {
            return false;
        }

        Path next = worldRoot == null ? null : worldRoot.toAbsolutePath().normalize().resolve("liteconfig");
        if (java.util.Objects.equals(worldDirectory, next)) {
            return false;
        }

        FileClaim previous = claims.remove(configType);
        if (previous != null) {
            for (int reference = 0; reference < previous.references(); reference++) {
                ownership.release(configType, previous.file());
            }
        }

        worldDirectory = next;
        try {
            resolveForConfig(configType);
        } catch (RuntimeException | Error failure) {
            worldDirectory = null;
            throw failure;
        }

        return true;
    }

    public void closeForConfig(Class<?> configType) {
        closed = true;

        FileClaim claim = claims.remove(configType);
        if (claim != null) {
            for (int reference = 0; reference < claim.references(); reference++) {
                ownership.release(configType, claim.file());
            }
        }

        worldDirectory = null;
    }

    private Config declarationOf(Class<?> configType) {
        Config config = configType.getAnnotation(Config.class);
        if (config == null) {
            throw scope.exception(ConfigError.MISSING_CONFIG_MARKER, configType.getName());
        }
        return config;
    }

    private ConfigPath pathOf(Class<?> configType) {
        return paths.computeIfAbsent(configType, type -> {
            Config declaration = declarationOf(type);
            Path installationPath = pathForConfig(type, declaration, baseDirectory);
            return new ConfigPath(declaration.type(), baseDirectory.relativize(installationPath), installationPath);
        });
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

    private record ConfigPath(ConfigType type, Path relativePath, Path installationPath) {}

    private record FileClaim(Path file, int references) {}
}
