package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.Hashing;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RestartSyncStore {

    private static final int TRANSACTION_VERSION = 1;
    private static final int MAX_PATH_BYTES = 32768;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    private final Path path;
    private final Path stagedPath;
    private final ConfigScope scope;
    private final boolean normalFile;

    RestartSyncStore(Path baseDirectory, ConfigScope scope, String configId, String connectionId) {
        String key = Hashing.sha256().hashString(
            configId + "\n" + connectionId, StandardCharsets.UTF_8).toString();
        this.path = directory(baseDirectory).resolve(key + ".bin");
        this.stagedPath = path.resolveSibling(path.getFileName() + ".txn");
        this.scope = scope;
        this.normalFile = false;
    }

    private RestartSyncStore(Path baseDirectory, Path file, ConfigScope scope) {
        this.path = file.toAbsolutePath().normalize();
        String key = Hashing.sha256().hashString(this.path.toString(), StandardCharsets.UTF_8).toString();
        this.stagedPath = directory(baseDirectory).resolve(key + ".file.txn");
        this.scope = scope;
        this.normalFile = true;
    }

    private static Path directory(Path baseDirectory) {
        return baseDirectory.resolve("liteconfig").resolve("servers");
    }

    public static RestartSyncStore forFile(Path baseDirectory, Path file, ConfigScope scope) {
        return new RestartSyncStore(baseDirectory, file, scope);
    }

    public byte[] readFile() {
        ConfigBytes bytes = read();
        return bytes == null ? null : bytes.bytes();
    }

    public boolean journaled() {
        return Files.exists(stagedPath);
    }

    public void writeFile(byte[] bytes) {
        write(ConfigBytes.of(bytes));
    }

    ConfigBytes read() {
        try {
            PendingSyncTransaction envelope = readPendingTransaction(stagedPath);

            if (envelope != null) {
                boolean selected = committed(envelope.marker());
                if (normalFile && selected) {
                    materialize(envelope.marker());
                }
                return selected ? envelope.candidate() : envelope.previous();
            }

            long length = Files.size(path);
            if (!normalFile && length > ConfigSyncProtocol.MAX_CONFIG_BYTES) {
                throw scope.exception(ConfigError.SYNC_APPLY_FAILED, path, "restart cache exceeds size limit");
            }

            return ConfigBytes.of(Files.readAllBytes(path));
        } catch (NoSuchFileException | NotDirectoryException absent) {
            return null;
        } catch (IOException failure) {
            throw scope.exception(ConfigError.IO_LOAD_FAILURE, failure, path);
        }
    }

    void write(ConfigBytes bytes) {
        try {
            if (readPendingTransaction(stagedPath) != null) {
                Transaction transaction = new Transaction();
                transaction.stage(this, bytes);
                transaction.commit();
                return;
            }

            if (bytes == null) {
                Files.deleteIfExists(path);
                return;
            }

            requireSize(bytes);
            if (!normalFile && bytes.size() > ConfigSyncProtocol.MAX_CONFIG_BYTES) {
                throw new IOException("Server payload cache exceeds size limit");
            }

            atomicWrite(path, bytes.bytes());
        } catch (IOException failure) {
            throw scope.exception(ConfigError.IO_SAVE_FAILURE, failure, path);
        }
    }

    private static void requireSize(ConfigBytes bytes) throws IOException {
        if (bytes != null && bytes.size() > MAX_FILE_BYTES) {
            throw new IOException("Restart cache exceeds size limit");
        }
    }

    private static PendingSyncTransaction readPendingTransaction(Path file) throws IOException {

        int maximum = file.getFileName().toString().endsWith(".file.txn")
            ? MAX_FILE_BYTES : ConfigSyncProtocol.MAX_CONFIG_BYTES;

        byte[] bytes;
        try {
            if (Files.size(file) > 2L * maximum + MAX_PATH_BYTES + 32) {
                throw new IOException("Restart cache transaction exceeds size limit");
            }
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException | NotDirectoryException absent) {
            return null;
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != TRANSACTION_VERSION) {
                throw new IOException("Unsupported restart cache transaction version");
            }

            Path marker = Path.of(input.readUTF());
            ConfigBytes previous = readBytes(input, maximum);
            ConfigBytes candidate = readBytes(input, maximum);

            if (input.available() != 0) {
                throw new IOException("Trailing restart cache transaction data");
            }

            return new PendingSyncTransaction(marker, previous, candidate);
        }
    }

    private static ConfigBytes readBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();

        if (length == -1) {
            return null;
        }

        if (length < 0 || length > maximum || length > input.available()) {
            throw new IOException("Invalid restart cache payload length");
        }

        return ConfigBytes.of(input.readNBytes(length));
    }

    private static void writeBytes(DataOutputStream output, ConfigBytes bytes) throws IOException {
        requireSize(bytes);
        output.writeInt(bytes == null ? -1 : bytes.size());
        if (bytes != null) {
            output.write(bytes.bytes());
        }
    }

    private static boolean committed(Path marker) throws IOException {
        return readParticipants(marker) != null;
    }

    private static List<Path> readParticipants(Path marker) throws IOException {
        byte[] bytes;
        try {
            if (Files.size(marker) > 2L * ConfigSyncProtocol.MAX_TRANSACTION_ENTRIES * MAX_PATH_BYTES + 8) {
                throw new IOException("Restart cache commit marker exceeds size limit");
            }
            bytes = Files.readAllBytes(marker);
        } catch (NoSuchFileException | NotDirectoryException absent) {
            return null;
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {

            int version = input.readInt();
            int count = input.readInt();
            if (version != TRANSACTION_VERSION || count < 1 || count > 2 * ConfigSyncProtocol.MAX_TRANSACTION_ENTRIES) {
                throw new IOException("Invalid restart cache commit marker");
            }

            List<Path> participants = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                participants.add(Path.of(input.readUTF()));
            }

            if (input.available() != 0) {
                throw new IOException("Trailing restart cache commit marker data");
            }

            return participants;
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");

        try {
            try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);

                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) {
                        throw new IOException("No progress writing restart cache file: " + target);
                    }
                }

                channel.force(true);
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void cleanMarker(Path marker) {
        try {
            List<Path> participants = readParticipants(marker);
            if (participants == null) {
                return;
            }

            for (Path participant : participants) {
                PendingSyncTransaction envelope = readPendingTransaction(participant);
                if (envelope != null && envelope.marker().equals(marker)) {
                    return;
                }
            }

            Files.deleteIfExists(marker);
        } catch (IOException failure) {
            scope.logError("Failed to remove unused restart-sync commit marker " + marker, failure);
        }
    }

    private void materialize(Path marker) {
        try {
            PendingSyncTransaction envelope = readPendingTransaction(stagedPath);
            if (envelope == null || !envelope.marker().equals(marker)) {
                return;
            }

            if (envelope.candidate() == null) {
                Files.deleteIfExists(path);
            } else {
                atomicWrite(path, envelope.candidate().bytes());
            }

            if (normalFile) {
                Files.deleteIfExists(stagedPath);
                cleanMarker(marker);
            }
        } catch (IOException | RuntimeException failure) {
            scope.logError("Failed to materialize committed restart-sync payload " + path, failure);
        }
    }

    private record PendingSyncTransaction(Path marker, ConfigBytes previous, ConfigBytes candidate) {}

    public static final class Transaction {

        private final Map<Path, RestartSyncStore> participants = new LinkedHashMap<>();
        private Path marker;
        private ConfigScope scope;
        private boolean committed;

        void stage(RestartSyncStore store, ConfigBytes candidate) {
            if (committed) {
                throw new IllegalStateException("Restart cache transaction already committed");
            }

            try {
                requireSize(candidate);
                if (!store.normalFile && candidate != null && candidate.size() > ConfigSyncProtocol.MAX_CONFIG_BYTES) {
                    throw new IOException("Server payload cache exceeds size limit");
                }

                ConfigBytes previous = store.read();
                if (Objects.equals(previous, candidate)) {
                    return;
                }

                if (marker == null) {
                    marker = store.stagedPath.getParent().resolve(".transactions")
                        .resolve(UUID.randomUUID() + ".commit").toAbsolutePath().normalize();
                    scope = store.scope;
                }

                if (store.normalFile) {
                    if (Files.isDirectory(store.path)
                        || (Files.exists(store.path) && !Files.isWritable(store.path))) {
                        throw new IOException("Restart selection target is not writable: " + store.path);
                    }

                    Files.createDirectories(store.path.getParent());
                    Path probe = store.path.resolveSibling(store.path.getFileName() + "." + UUID.randomUUID() + ".probe");

                    try {
                        atomicWrite(probe, candidate.bytes());
                    } finally {
                        Files.deleteIfExists(probe);
                    }
                }

                PendingSyncTransaction old = readPendingTransaction(store.stagedPath);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    output.writeInt(TRANSACTION_VERSION);
                    output.writeUTF(marker.toString());
                    writeBytes(output, previous);
                    writeBytes(output, candidate);
                }

                atomicWrite(store.stagedPath, bytes.toByteArray());
                participants.put(store.stagedPath.toAbsolutePath().normalize(), store);

                if (old != null && !old.marker().equals(marker)) {
                    store.cleanMarker(old.marker());
                }
            } catch (IOException failure) {
                throw store.scope.exception(ConfigError.IO_SAVE_FAILURE, failure, store.stagedPath);
            }
        }

        public void stageFile(RestartSyncStore store, byte[] bytes) {
            stage(store, ConfigBytes.of(bytes));
        }

        void abort() {
            if (committed) {
                return;
            }

            for (RestartSyncStore store : participants.values()) {
                if (!store.normalFile) {
                    continue;
                }

                try {
                    PendingSyncTransaction envelope = readPendingTransaction(store.stagedPath);
                    if (envelope != null && envelope.marker().equals(marker)) {
                        byte[] actual = Files.exists(store.path) ? Files.readAllBytes(store.path) : null;
                        byte[] previous = envelope.previous() == null ? null : envelope.previous().bytes();
                        if (Arrays.equals(actual, previous)) {
                            Files.deleteIfExists(store.stagedPath);
                        }
                    }
                } catch (IOException failure) {
                    store.scope.logError("Failed to clean aborted restart-selection journal " + store.stagedPath, failure);
                }
            }
        }

        void commit() {
            if (committed || participants.isEmpty()) {
                return;
            }

            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    output.writeInt(TRANSACTION_VERSION);
                    output.writeInt(participants.size());
                    for (Path participant : participants.keySet()) {
                        output.writeUTF(participant.toString());
                    }
                }

                atomicWrite(marker, bytes.toByteArray());
                committed = true;
                participants.values().forEach(store -> store.materialize(marker));
            } catch (IOException failure) {
                throw scope.exception(ConfigError.IO_SAVE_FAILURE, failure, marker);
            }
        }
    }
}
