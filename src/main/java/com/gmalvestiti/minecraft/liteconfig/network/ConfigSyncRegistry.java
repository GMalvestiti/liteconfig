package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.lang.ref.WeakReference;

public final class ConfigSyncRegistry {

    private static final Map<String, SyncedConfig<?>> SYNCED = new ConcurrentHashMap<>();

    private ConfigSyncRegistry() {}

    static SyncedConfig<?> get(String id) {
        return SYNCED.get(id);
    }

    static Collection<SyncedConfig<?>> values() {
        return SYNCED.values();
    }

    public static void register(RegisteredConfig<?> config) {
        if (config.pathResolver().type() == ConfigType.CLIENT
            || config.model().metadata().synced().isEmpty()) {
            return;
        }

        SyncedConfig<?> synced = config.writing(() -> {
            SyncedConfig<?> candidate = SyncedConfig.of(config);
            SyncedConfig<?> existing = SYNCED.putIfAbsent(candidate.id(), candidate);
            if (existing != null) {
                if (!existing.wraps(config)) {
                    throw config.model().scope().exception(ConfigError.CONFLICTING_SYNC_ROOT, candidate.id());
                }
                return existing;
            }
            try {
                candidate.registerLocalChangeBroadcast();
            } catch (RuntimeException | Error failure) {
                SYNCED.remove(candidate.id(), candidate);
                throw failure;
            }
            return candidate;
        });

        try {
            WeakReference<SyncedConfig<?>> reference = new WeakReference<>(synced);
            runOnMainThread(() -> {
                SyncedConfig<?> current = reference.get();
                if (current != null) {
                    activate(current);
                    ServerConfigSync.refreshManifest();
                }
            });
        } catch (RuntimeException | Error failure) {
            if (SYNCED.remove(synced.id(), synced)) {
                synced.close();
            }
            throw failure;
        }
    }

    public static void unregister(RegisteredConfig<?> config) {
        SyncedConfig<?> synced = SYNCED.get(config.model().syncId());

        if (synced == null || !synced.wraps(config) || !SYNCED.remove(synced.id(), synced)) {
            return;
        }

        synced.close();
        ServerConfigSync.refreshManifest();
    }

    public static void activate() {
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
            ClientConfigSync.requestIfAdvertised(synced);
        } catch (RuntimeException | Error failure) {
            if (SYNCED.remove(synced.id(), synced)) {
                synced.close();
            }

            LiteConfigCommon.error("Failed to initialize config sync", failure);
            throw failure;
        }
    }

    private static void runOnMainThread(Runnable task) {
        Executor server = ServerConfigSync.serverMainThreadExecutor();

        if (server != null) {
            server.execute(() -> {
                if (ServerConfigSync.serverMainThreadExecutor() == server) {
                    task.run();
                }
            });
            return;
        }

        Executor client = ClientConfigSync.availableMainThreadExecutor();
        if (client != null) {
            client.execute(() -> {
                if (ClientConfigSync.availableMainThreadExecutor() == client) {
                    task.run();
                }
            });
        }
    }
}
