package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigFormat;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.migration.ConfigMigrations;
import com.gmalvestiti.minecraft.liteconfig.storage.format.ConfigTextFormat;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

public final class ConfigStorage<T> {

    private static final String TEMP_SUFFIX = ".tmp";
    private static final String BACKUP_SUFFIX = ".corrupt-";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int REPLACE_ATTEMPTS = 5;
    private static final long REPLACE_BACKOFF_MILLIS = 5L;

    private final Class<T> configType;
    private final Path path;
    private final ConfigTextFormat format;
    private final ConfigScope scope;

    public ConfigStorage(Class<T> configType, ConfigPathResolver pathResolver, ConfigScope scope) {
        this.configType = Objects.requireNonNull(configType, "configType");
        this.path = Objects.requireNonNull(pathResolver, "pathResolver").resolveForConfig(configType);
        this.format = ConfigTextFormat.of(ConfigFormat.of(configType));
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public T read() {
        String text;

        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException ex) {
            throw scope.exception(ConfigError.IO_LOAD_FAILURE, ex, path);
        }

        JsonObject tree = decode(() -> format.readTree(text));
        return decode(() -> ConfigBinder.fromTree(ConfigMigrations.of(configType).apply(tree, scope), configType));
    }

    /**
     * Runs one step of turning file text into config data. Anything the step reports about the
     * file itself is kept; anything else, including an empty result, means the text was simply
     * not readable as this config.
     */
    private <R> R decode(Supplier<R> step) {
        R decoded;

        try {
            decoded = step.get();
        } catch (LiteConfigException failure) {
            throw failure;
        } catch (RuntimeException ex) {
            throw scope.exception(ConfigError.MALFORMED_CONFIG_DATA, ex, path);
        }

        if (decoded == null) {
            throw scope.exception(ConfigError.MALFORMED_CONFIG_DATA, path);
        }

        return decoded;
    }

    public void write(T data) {
        Objects.requireNonNull(data, "data");
        Path temp = temporarySibling(path);

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temp, render(data), StandardCharsets.UTF_8);
            replace(temp, path);
        } catch (IOException | RuntimeException ex) {
            throw scope.exception(ConfigError.IO_SAVE_FAILURE, ex, path);
        } finally {
            deleteQuietly(temp);
        }
    }

    private String render(T data) {
        JsonObject tree = ConfigMigrations.of(configType).stamp(ConfigBinder.toTree(data).getAsJsonObject());
        return format.writeTree(tree, configType);
    }

    public void backupCorrupted() {
        if (!Files.exists(path)) {
            return;
        }

        String timestamp = BACKUP_TIMESTAMP.format(Instant.now());
        String unique = Long.toUnsignedString(System.nanoTime());
        Path backup = path.resolveSibling(path.getFileName() + BACKUP_SUFFIX + timestamp + "-" + unique);

        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException ignored) {
        } catch (IOException ex) {
            throw scope.exception(ConfigError.IO_SAVE_FAILURE, ex, path);
        }
    }

    private Path temporarySibling(Path path) {
        return path.resolveSibling(path.getFileName() + TEMP_SUFFIX + "-" + Long.toUnsignedString(System.nanoTime()));
    }

    private void replace(Path source, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                move(source, target);
                return;
            } catch (AccessDeniedException contended) {
                if (attempt == REPLACE_ATTEMPTS - 1) {
                    throw contended;
                }

                backOff(attempt);
            }
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backOff(int attempt) throws IOException {
        try {
            Thread.sleep(REPLACE_BACKOFF_MILLIS << attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Config write retry interrupted");
            interrupted.initCause(ex);
            throw interrupted;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
