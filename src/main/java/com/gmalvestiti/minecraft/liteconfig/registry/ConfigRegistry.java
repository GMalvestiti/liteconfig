package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConfigRegistry {

    private static final Map<RegistrationKey, RegistrationSlot> REGISTRATIONS = new HashMap<>();
    private static final ConfigFileOwnership OWNERSHIP = new ConfigFileOwnership();

    private static final Object REGISTRY_LOCK = new Object();
    private static volatile Path activeWorldRoot;
    private static long worldVersion;

    private ConfigRegistry() {}

    @SuppressWarnings("unchecked")
    public static <T> RegisteredConfig<T> register(ConfigSettings<T> settings) {
        RegistrationKey key = RegistrationKey.of(settings);
        initializeConfigClass(settings, key);

        RegistrationSlot slot;
        boolean creating;

        synchronized (REGISTRY_LOCK) {
            slot = REGISTRATIONS.get(key);
            creating = slot == null;

            if (creating) {
                slot = new RegistrationSlot(Thread.currentThread());
                REGISTRATIONS.put(key, slot);
            } else if (slot.creatingThread() != null) {
                rejectBlockingNestedCreation(settings);
            }
        }

        if (!creating) {
            RegisteredConfig<T> current = (RegisteredConfig<T>) slot.awaitCreation();

            current.ensureOpen();
            rejectScopeMismatch(settings, current);
            return current;
        }

        return create(settings, key, slot);
    }

    private static void initializeConfigClass(ConfigSettings<?> settings, RegistrationKey key) {
        Class<?> type = settings.type();
        if (type.getAnnotation(Config.class) == null) {
            throw settings.scope().exception(ConfigError.MISSING_CONFIG_MARKER, type.getName());
        }

        synchronized (REGISTRY_LOCK) {
            RegistrationSlot existing = REGISTRATIONS.get(key);
            if (existing != null && existing.creatingThread() != null) {
                rejectBlockingNestedCreation(settings);
            }
        }

        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw settings.scope().exception(ConfigError.INITIALIZATION_FAILED, failure, type.getName());
        }
    }

    private static <T> RegisteredConfig<T> create(
        ConfigSettings<T> settings,
        RegistrationKey key,
        RegistrationSlot slot
    ) {
        RegisteredConfig<T> created = null;
        try {
            created = RegisteredConfig.create(settings, OWNERSHIP,
                registration -> applyWorldContext(registration, false));

            ConfigSyncRegistry.register(created);

            Path worldRoot;
            do {
                worldRoot = activeWorldRoot;
                created.worldContext(worldRoot, false);
            } while (!completeRegistration(created, slot, worldRoot));

            return created;
        } catch (RuntimeException | Error failure) {
            synchronized (REGISTRY_LOCK) {
                REGISTRATIONS.remove(key, slot);
                slot.fail(failure);
            }

            if (created != null) {
                try {
                    created.access().close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }

            throw failure;
        }
    }

    private static boolean completeRegistration(
        RegisteredConfig<?> registration,
        RegistrationSlot slot,
        Path worldRoot
    ) {
        synchronized (REGISTRY_LOCK) {
            registration.ensureOpen();
            if (!Objects.equals(worldRoot, activeWorldRoot)) {
                return false;
            }
            slot.complete(registration);
            return true;
        }
    }

    public static void activateWorld(Path worldRoot) {
        switchWorld(Objects.requireNonNull(worldRoot, "worldRoot").toAbsolutePath().normalize());
    }

    public static void deactivateWorld() {
        switchWorld(null);
    }

    private static void switchWorld(Path worldRoot) {
        List<RegisteredConfig<?>> registrations;
        long version;

        synchronized (REGISTRY_LOCK) {
            if (Objects.equals(activeWorldRoot, worldRoot)) {
                return;
            }

            activeWorldRoot = worldRoot;
            version = ++worldVersion;
            registrations = registrations();
        }

        try {
            for (RegisteredConfig<?> registration : registrations) {
                applyWorldContext(registration, true);
            }
        } catch (RuntimeException | Error failure) {
            synchronized (REGISTRY_LOCK) {
                if (worldVersion == version) {
                    activeWorldRoot = null;
                    worldVersion++;
                }

                Set<RegisteredConfig<?>> known = Collections.newSetFromMap(new IdentityHashMap<>());
                known.addAll(registrations);
                for (RegisteredConfig<?> registration : registrations()) {
                    if (known.add(registration)) {
                        registrations.add(registration);
                    }
                }
            }

            for (RegisteredConfig<?> registration : registrations) {
                try {
                    applyWorldContext(registration, true);
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }

            throw failure;
        }
    }

    private static List<RegisteredConfig<?>> registrations() {
        List<RegisteredConfig<?>> registrations = new ArrayList<>();
        for (RegistrationSlot slot : REGISTRATIONS.values()) {
            if (slot.value() != null) {
                registrations.add(slot.value());
            }
        }
        return registrations;
    }

    private static void applyWorldContext(RegisteredConfig<?> registration, boolean notify) {
        Path worldRoot;

        do {
            worldRoot = activeWorldRoot;
            registration.worldContext(worldRoot, notify);
        } while (!Objects.equals(worldRoot, activeWorldRoot));
    }

    public static void release(RegisteredConfig<?> registration) {
        if (registration != null) {
            registration.access().close();
        }
    }

    static void unregister(RegisteredConfig<?> registration) {
        RegistrationKey key = RegistrationKey.of(
            registration.model().type(), registration.pathResolver().baseDirectory(),
            registration.model().scope().modId());

        synchronized (REGISTRY_LOCK) {
            RegistrationSlot slot = REGISTRATIONS.get(key);
            try {
                if (slot != null && slot.value() == registration) {
                    REGISTRATIONS.remove(key, slot);
                }
            } finally {
                try {
                    registration.model().listeners().close();
                } finally {
                    registration.pathResolver().closeForConfig(registration.model().type());
                }
            }
        }

        ConfigSyncRegistry.unregister(registration);
    }

    private static void rejectBlockingNestedCreation(ConfigSettings<?> settings) {
        Thread caller = Thread.currentThread();
        for (RegistrationSlot registration : REGISTRATIONS.values()) {
            if (registration.creatingThread() == caller) {
                throw settings.scope().exception(
                    ConfigError.REENTRANT_CONFIG_REGISTRATION, settings.type().getName());
            }
        }
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

    private record RegistrationKey(Class<?> type, Path baseDirectory, String namespace) {

        private RegistrationKey {
            if (baseDirectory != null) {
                baseDirectory = baseDirectory.normalize();
            }
        }

        private static RegistrationKey of(ConfigSettings<?> settings) {
            return of(settings.type(), settings.baseDirectory(), settings.scope().modId());
        }

        private static RegistrationKey of(Class<?> type, Path baseDirectory, String modId) {
            Config declaration = type.getAnnotation(Config.class);

            boolean world = declaration != null && declaration.type() == ConfigType.WORLD;
            boolean scoped = declaration != null && declaration.modIdDirectory();

            return new RegistrationKey(type, world ? null : baseDirectory, scoped ? modId : null);
        }
    }
}
