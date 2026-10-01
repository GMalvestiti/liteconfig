package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class ConfigSyncRegistry {

    private static final Map<String, SyncedConfig<?>> SYNCED = new ConcurrentHashMap<>();
    private static boolean INITIALIZED;

    private ConfigSyncRegistry() {}

    public static synchronized void initialize() {
        if (INITIALIZED) {
            return;
        }
        ConfigRegistry.setLifecycleCallbacks(ConfigSyncRegistry::register, ConfigSyncRegistry::unregister);
        INITIALIZED = true;
    }

    static SyncedConfig<?> get(String id) {
        return SYNCED.get(id);
    }

    static Collection<SyncedConfig<?>> values() {
        return SYNCED.values();
    }

    private static void register(RegisteredConfig<?> config) {
        if (config.model().metadata().synced().isEmpty()) {
            return;
        }

        SyncedConfig<?> synced = SyncedConfig.of(config);
        SyncedConfig<?> existing = SYNCED.putIfAbsent(synced.id(), synced);

        if (existing != null) {
            if (!existing.wraps(config)) {
                throw config.model().scope().exception(ConfigError.CONFLICTING_SYNC_ROOT, synced.id());
            }
            return;
        }

        try {
            synced.addBroadcastListener(() -> ServerConfigSync.broadcast(synced));

            runOnMainThread(() -> {
                activate(synced);
                ServerConfigSync.refreshManifest();
            });
        } catch (RuntimeException | Error failure) {
            if (SYNCED.remove(synced.id(), synced)) {
                synced.close();
            }
            throw failure;
        }
    }

    private static void unregister(RegisteredConfig<?> config) {
        SyncedConfig<?> synced = SYNCED.get(config.model().syncId());

        if (synced == null || !synced.wraps(config) || !SYNCED.remove(synced.id(), synced)) {
            return;
        }

        synced.close();
        ServerConfigSync.refreshManifest();
    }

    static void activate() {
        if (SYNCED.isEmpty()) {
            return;
        }

        runOnMainThread(() -> {
            SYNCED.values().forEach(ConfigSyncRegistry::activate);
            ServerConfigSync.refreshManifest();
        });
    }

    private static void activate(SyncedConfig<?> synced) {
        if (SYNCED.get(synced.id()) != synced) {
            return;
        }

        try {
            synced.cachedSnapshot();
            ClientConfigSync.requestIfChanged(synced);
        } catch (RuntimeException | Error failure) {
            if (SYNCED.remove(synced.id(), synced)) {
                synced.close();
            }

            LiteConfigCommon.error("Failed to initialize config sync", failure);
            throw failure;
        }
    }

    private static void runOnMainThread(Runnable task) {
        Executor executor = ServerConfigSync.serverMainThreadExecutor();

        if (executor == null) {
            executor = ClientConfigSync.availableMainThreadExecutor();
        }

        if (executor != null) {
            executor.execute(task);
        }
    }
}
