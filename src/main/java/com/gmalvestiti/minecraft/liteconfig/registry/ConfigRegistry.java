package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigPathResolver;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ConfigRegistry {

    private static final Map<RegistrationKey, RegistrationSlot> REGISTRATIONS = new ConcurrentHashMap<>();
    private static final Map<Thread, Thread> REGISTRATION_WAITS = new ConcurrentHashMap<>();
    private static final ConfigFileOwnership OWNERSHIP = new ConfigFileOwnership();

    private ConfigRegistry() {}

    @SuppressWarnings("unchecked")
    public static <T> RegisteredConfig<T> register(
        ConfigSettings<T> settings,
        Consumer<RegisteredConfig<?>> afterRegistration
    ) {
        RegistrationKey key = RegistrationKey.of(settings);
        RegistrationSlot candidate = new RegistrationSlot(Thread.currentThread());
        RegistrationSlot existing = REGISTRATIONS.putIfAbsent(key, candidate);

        if (existing == null) {
            try {
                RegisteredConfig<T> created = RegisteredConfig.create(settings, OWNERSHIP, afterRegistration);
                candidate.complete(created);
                return created;
            } catch (RuntimeException | Error failure) {
                candidate.fail(failure);
                REGISTRATIONS.remove(key, candidate);
                throw failure;
            }
        }

        if (existing.isOwnedBy(Thread.currentThread())) {
            throw settings.scope().exception(
                ConfigError.REENTRANT_CONFIG_REGISTRATION, settings.type().getName());
        }

        RegisteredConfig<T> current = (RegisteredConfig<T>) await(settings, existing);
        if (!existing.retain()) {
            return register(settings, afterRegistration);
        }

        try {
            rejectScopeMismatch(settings, current);
            afterRegistration.accept(current);
            return current;
        } catch (RuntimeException | Error failure) {
            release(key, existing, current);
            throw failure;
        }
    }

    public static void release(RegisteredConfig<?> registration) {
        if (registration == null) {
            return;
        }

        for (Map.Entry<RegistrationKey, RegistrationSlot> entry : REGISTRATIONS.entrySet()) {
            RegistrationSlot slot = entry.getValue();
            if (slot.value() == registration) {
                release(entry.getKey(), slot, registration);
                return;
            }
        }
    }

    private static void release(
        RegistrationKey key,
        RegistrationSlot slot,
        RegisteredConfig<?> registration
    ) {
        if (!slot.release()) {
            return;
        }

        if (REGISTRATIONS.remove(key, slot)) {
            ConfigSyncRegistry.unregister(registration);
            new ConfigPathResolver(key.baseDirectory(), registration.model().scope(), OWNERSHIP)
                .releaseForConfig(registration.model().type());
        }
    }

    private static RegisteredConfig<?> await(
        ConfigSettings<?> settings,
        RegistrationSlot existing
    ) {
        if (existing.isDone()) {
            return existing.await();
        }

        Thread current = Thread.currentThread();
        REGISTRATION_WAITS.put(current, existing.owner());
        try {
            if (hasWaitCycle(current)) {
                throw settings.scope().exception(
                    ConfigError.REENTRANT_CONFIG_REGISTRATION, settings.type().getName());
            }
            return existing.await();
        } finally {
            REGISTRATION_WAITS.remove(current);
        }
    }

    private static boolean hasWaitCycle(Thread origin) {
        Set<Thread> visited = new HashSet<>();
        Thread waitingOn = REGISTRATION_WAITS.get(origin);
        while (waitingOn != null && visited.add(waitingOn)) {
            if (waitingOn == origin) {
                return true;
            }
            waitingOn = REGISTRATION_WAITS.get(waitingOn);
        }
        return false;
    }

    private static void rejectScopeMismatch(
        ConfigSettings<?> settings,
        RegisteredConfig<?> current
    ) {
        String requestedModId = settings.scope().modId();
        String registeredModId = current.model().scope().modId();

        if (!registeredModId.equals(requestedModId)) {
            throw settings.scope().exception(
                ConfigError.CONFLICTING_CONFIG_SCOPE,
                settings.type().getName(),
                settings.baseDirectory(),
                registeredModId,
                requestedModId
            );
        }
    }

    private record RegistrationKey(Class<?> type, Path baseDirectory) {

        private RegistrationKey {
            baseDirectory = baseDirectory.normalize();
        }

        private static RegistrationKey of(ConfigSettings<?> settings) {
            return new RegistrationKey(settings.type(), settings.baseDirectory());
        }
    }

    private static final class RegistrationSlot {

        private final Thread owner;
        private final CompletableFuture<RegisteredConfig<?>> result = new CompletableFuture<>();
        private int references = 1;
        private boolean released;

        private RegistrationSlot(Thread owner) {
            this.owner = owner;
        }

        private boolean isOwnedBy(Thread thread) {
            return owner == thread && !result.isDone();
        }

        private boolean isDone() {
            return result.isDone();
        }

        private Thread owner() {
            return owner;
        }

        private void complete(RegisteredConfig<?> registration) {
            result.complete(registration);
        }

        private void fail(Throwable failure) {
            result.completeExceptionally(failure);
        }

        private synchronized boolean retain() {
            if (released) {
                return false;
            }
            references++;
            return true;
        }

        private synchronized boolean release() {
            if (released || --references > 0) {
                return false;
            }
            released = true;
            return true;
        }

        private RegisteredConfig<?> value() {
            return result.getNow(null);
        }

        private RegisteredConfig<?> await() {
            try {
                return result.join();
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause();

                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }

                if (cause instanceof Error error) {
                    throw error;
                }

                throw failure;
            }
        }
    }
}
