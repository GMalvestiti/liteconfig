package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigFormat;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.migration.ConfigMigrations;
import com.gmalvestiti.minecraft.liteconfig.network.RestartSyncStore;
import com.gmalvestiti.minecraft.liteconfig.storage.format.ConfigTextFormat;
import com.google.gson.JsonObject;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class ConfigStorage<T> {

    private static final String TEMP_SUFFIX = ".tmp";
    private static final String BACKUP_SUFFIX = ".corrupt-";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int REPLACE_ATTEMPTS = 5;
    private static final int MAX_BACKUPS = 3;

    private final Class<T> configType;
    private final ConfigPathResolver pathResolver;
    private final ConfigTextFormat format;
    private final ConfigScope scope;
    private volatile CachedStore cachedStore;
    private volatile ReadSnapshot lastRead;
    private volatile boolean crashLeftoversCleaned;

    public ConfigStorage(Class<T> configType, ConfigPathResolver pathResolver, ConfigScope scope) {
        this.configType = Objects.requireNonNull(configType, "configType");
        this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver");
        pathResolver.resolve();
        this.format = ConfigTextFormat.of(ConfigFormat.of(configType));
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    private RestartSyncStore store(Path path) {
        CachedStore cached = this.cachedStore;
        Path base = pathResolver.baseDirectory();

        if (cached == null || !cached.path().equals(path) || !cached.base().equals(base)) {
            cached = new CachedStore(base, path, RestartSyncStore.forFile(base, path, scope));
            this.cachedStore = cached;
        }

        return cached.store();
    }

    private record CachedStore(Path base, Path path, RestartSyncStore store) {}

    private record ReadSnapshot(Path path, String text) {}

    public T read() {
        Path path = pathResolver.currentPath();

        if (path == null) {
            if (pathResolver.type() != ConfigType.WORLD) {
                return null;
            }
            path = pathResolver.restartPath();
        }

        lastRead = null;
        if (!crashLeftoversCleaned) {
            crashLeftoversCleaned = true;
            deleteMatching(path, TEMP_SUFFIX + "-", 0);
        }

        byte[] bytes = store(path).readFile();
        if (bytes == null) {
            return null;
        }

        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw scope.exception(ConfigError.IO_LOAD_FAILURE, failure, path);
        }

        lastRead = new ReadSnapshot(path, text);
        Path source = path;
        JsonObject tree = decode(source, () -> format.readTree(text));
        return decode(source, () -> ConfigSerialization.fromTree(ConfigMigrations.of(configType).apply(tree, scope), configType));
    }

    /**
     * Runs one step of turning file text into config data. Anything the step reports about the
     * file itself is kept; anything else, including an empty result, means the text was simply
     * not readable as this config.
     */
    private <R> R decode(Path path, Supplier<R> step) {
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
        write(data, false);
    }

    public void write(T data, boolean skipUnchanged) {
        Objects.requireNonNull(data, "data");

        Path path = pathResolver.currentPath();
        if (path == null) {
            return;
        }

        String rendered;
        try {
            rendered = render(data);
        } catch (RuntimeException failure) {
            throw scope.exception(ConfigError.IO_SAVE_FAILURE, failure, path);
        }

        ReadSnapshot snapshot = lastRead;
        lastRead = null;
        if (skipUnchanged && snapshot != null && snapshot.path().equals(path) && snapshot.text().equals(rendered)) {
            return;
        }

        RestartSyncStore journal = store(path);
        if (journal.journaled()) {
            try {
                journal.writeFile(rendered.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException failure) {
                throw scope.exception(ConfigError.IO_SAVE_FAILURE, failure, path);
            }
            return;
        }

        Path temp = temporarySibling(path);
        try {
            Files.createDirectories(path.getParent());
            writeDurably(temp, rendered.getBytes(StandardCharsets.UTF_8));
            replace(temp, path);
        } catch (IOException | RuntimeException ex) {
            throw scope.exception(ConfigError.IO_SAVE_FAILURE, ex, path);
        } finally {
            deleteQuietly(temp);
        }
    }

    private String render(T data) {
        return format.writeTree(ConfigSerialization.toTree(data).getAsJsonObject(), configType);
    }

    public PreparedWrite prepareRestartWrite(T data) {
        Path path = pathResolver.restartPath();
        try {
            return new PreparedWrite(store(path),
                render(data).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            throw scope.exception(ConfigError.IO_SAVE_FAILURE, failure, path);
        }
    }

    public static final class PreparedWrite {

        private final RestartSyncStore file;
        private final byte[] bytes;

        private PreparedWrite(RestartSyncStore file, byte[] bytes) {
            this.file = file;
            this.bytes = bytes;
        }

        public void stage(RestartSyncStore.Transaction transaction) {
            transaction.stageFile(file, bytes);
        }
    }

    public void backupCorrupted() {
        Path path = pathResolver.currentPath();

        if (path == null && pathResolver.type() == ConfigType.WORLD) {
            path = pathResolver.restartPath();
        }

        if (path == null) {
            return;
        }

        String timestamp = BACKUP_TIMESTAMP.format(Instant.now());
        String unique = Long.toUnsignedString(System.nanoTime());
        Path backup = path.resolveSibling(path.getFileName() + BACKUP_SUFFIX + timestamp + "-" + unique);

        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException ignored) {
            return;
        } catch (IOException ex) {
            throw scope.exception(ConfigError.IO_SAVE_FAILURE, ex, path);
        }

        deleteMatching(path, BACKUP_SUFFIX, MAX_BACKUPS);
    }

    private void writeDurably(Path file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    private void deleteMatching(Path path, String infix, int keep) {
        String prefix = path.getFileName() + infix;
        List<Path> matches;

        try (Stream<Path> siblings = Files.list(path.getParent())) {
            matches = siblings
                .filter(sibling -> sibling.getFileName().toString().startsWith(prefix))
                .sorted(Comparator.comparing((Path sibling) -> sibling.getFileName().toString()).reversed())
                .skip(keep)
                .toList();
        } catch (IOException | RuntimeException ignored) {
            return;
        }

        matches.forEach(this::deleteQuietly);
    }

    private Path temporarySibling(Path path) {
        return path.resolveSibling(path.getFileName() + TEMP_SUFFIX + "-" + Long.toUnsignedString(System.nanoTime()));
    }

    private void replace(Path source, Path target) throws IOException {
        for (int attempt = 0; attempt < REPLACE_ATTEMPTS; attempt++) {
            try {
                move(source, target);
                return;
            } catch (AccessDeniedException contended) {
                if (attempt == REPLACE_ATTEMPTS - 1) {
                    throw contended;
                }
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

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
