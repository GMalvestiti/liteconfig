package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.lang.ref.WeakReference;

public final class ConfigSyncRegistry {

    private static final Map<String, SyncedConfig<?>> SYNCED = new ConcurrentHashMap<>();
    private static final Map<String, SyncedConfig<?>> LOCAL = new HashMap<>();

    private ConfigSyncRegistry() {}

    static boolean runtimeAvailable() {
        return ServerConfigSync.serverMainThreadExecutor() != null
            || ClientConfigSync.availableMainThreadExecutor() != null;
    }

    static SyncedConfig<?> get(String id) {
        return SYNCED.get(id);
    }

    static Collection<SyncedConfig<?>> values() {
        return SYNCED.values();
    }

    static List<SyncedConfig<?>> localValues() {
        synchronized (SYNCED) {
            return List.copyOf(LOCAL.values());
        }
    }

    public static void refreshLocalContext(RegisteredConfig<?> config) {
        synchronized (SYNCED) {
            SyncedConfig<?> synced = SYNCED.get(config.model().syncId());
            if (synced != null && synced.wraps(config)) {
                updateLocalAdmission(config, synced);
            }
        }
    }

    private static void updateLocalAdmission(RegisteredConfig<?> config, SyncedConfig<?> synced) {
        if (!config.isLocallyWritable()) {
            LOCAL.remove(synced.id(), synced);
        } else if (!LOCAL.containsKey(synced.id())) {
            if (LOCAL.size() >= ConfigSyncProtocol.MAX_MANIFEST_ENTRIES) {
                throw config.model().scope().exception(ConfigError.SYNC_MANIFEST_FULL,
                    synced.id(), LOCAL.size() + 1, ConfigSyncProtocol.MAX_MANIFEST_ENTRIES);
            }
            LOCAL.put(synced.id(), synced);
        }
    }

    private static boolean remove(SyncedConfig<?> synced) {
        synchronized (SYNCED) {
            if (!SYNCED.remove(synced.id(), synced)) {
                return false;
            }
            LOCAL.remove(synced.id(), synced);
            return true;
        }
    }

    public static void register(RegisteredConfig<?> config) {
        if (config.pathResolver().type() == ConfigType.CLIENT
            || config.model().metadata().synced().isEmpty()) {
            return;
        }

        SyncedConfig<?> synced = config.writing(() -> {
            SyncedConfig<?> candidate = SyncedConfig.of(config);

            synchronized (SYNCED) {
                SyncedConfig<?> existing = SYNCED.get(candidate.id());
                if (existing != null) {
                    if (!existing.wraps(config)) {
                        throw config.model().scope().exception(ConfigError.CONFLICTING_SYNC_ROOT, candidate.id());
                    }
                    return existing;
                }
                updateLocalAdmission(config, candidate);
                SYNCED.put(candidate.id(), candidate);
            }

            try {
                candidate.registerLocalChangeBroadcast();
            } catch (RuntimeException | Error failure) {
                remove(candidate);
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
                    ServerConfigSync.announce(current);
                }
            });
        } catch (RuntimeException | Error failure) {
            if (remove(synced)) {
                synced.close();
            }
            throw failure;
        }
    }

    public static void unregister(RegisteredConfig<?> config) {
        SyncedConfig<?> synced = SYNCED.get(config.model().syncId());

        if (synced == null || !synced.wraps(config) || !remove(synced)) {
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
            synced.activate();
            ClientConfigSync.requestIfAdvertised(synced);
        } catch (RuntimeException | Error failure) {
            if (remove(synced)) {
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
