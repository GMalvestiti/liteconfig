package com.gmalvestiti.minecraft.liteconfig.support;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Collection;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Gives each test its own registries, since a config class is registered once per JVM.
 */
public final class ConfigRegistryIsolation implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        resetRegistries();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        resetRegistries();
    }

    private static void resetRegistries() throws ReflectiveOperationException {
        clearStaticMap(ConfigRegistry.class, "REGISTRATIONS");
        Object ownership = staticField(ConfigRegistry.class, "OWNERSHIP").get(null);
        clearMap(staticField(ownership.getClass(), "owners").get(ownership));
        Consumer<Object> noCallback = ignored -> {};
        setStaticField(ConfigRegistry.class, "registrationCallback", noCallback);
        setStaticField(ConfigRegistry.class, "releaseCallback", noCallback);

        clearStaticMap(ConfigSyncRegistry.class, "SYNCED");
        clearStaticMap(ConfigSyncRegistry.class, "SERVER_HASHES");
        clearStaticMap(ConfigSyncRegistry.class, "PENDING_TRANSACTIONS");
        setStaticField(ConfigSyncRegistry.class, "broadcastScheduler", (Consumer<Object>) ignored -> {});
        setStaticField(ConfigSyncRegistry.class, "requestScheduler", (Consumer<Object>) ignored -> {});
        setStaticField(ConfigSyncRegistry.class, "disconnectScheduler", (Consumer<Object>) ignored -> {});
        setStaticField(ConfigSyncRegistry.class, "manifestScheduler", (Runnable) () -> {});
        setStaticField(ConfigSyncRegistry.class, "clientMainThreadExecutor",
            (java.util.concurrent.Executor) Runnable::run);
        setStaticField(ConfigSyncRegistry.class, "remoteConnectionCheck",
            (BooleanSupplier) () -> false);
        setStaticField(ConfigSyncRegistry.class, "totalPendingBytes", 0);
        setStaticField(ConfigSyncRegistry.class, "totalPendingEntries", 0);
        setStaticField(ConfigSyncRegistry.class, "initialized", false);
        clearCollection(staticField(LiteConfig.codecs().getClass(), "plannedTypes")
            .get(LiteConfig.codecs()));
    }

    private static void clearStaticMap(Class<?> owner, String name) throws ReflectiveOperationException {
        clearMap(staticField(owner, name).get(null));
    }

    private static void clearMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Expected a map-backed registry");
        }
        map.clear();
    }

    private static void clearCollection(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalStateException("Expected a collection-backed registry");
        }
        collection.clear();
    }

    private static void setStaticField(
        Class<?> owner,
        String name,
        Object value
    ) throws ReflectiveOperationException {
        staticField(owner, name).set(null, value);
    }

    private static Field staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
