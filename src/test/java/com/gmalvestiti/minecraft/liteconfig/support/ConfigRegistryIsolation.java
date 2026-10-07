package com.gmalvestiti.minecraft.liteconfig.support;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventThreads;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.ServerConfigSync;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
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

    public static void beforeGameStartup() throws ReflectiveOperationException {
        setStaticField(ClientConfigSync.class, "CLIENT_MAIN_THREAD_EXECUTOR", null);
        setServerMainThreadExecutor(null);
        clearThreadBinding("CLIENT_MAIN_THREAD");
        clearThreadBinding("SERVER_MAIN_THREAD");
    }

    private static void resetRegistries() throws ReflectiveOperationException {
        Map<?, ?> registrations = (Map<?, ?>) staticField(ConfigRegistry.class, "REGISTRATIONS").get(null);
        for (Object slot : registrations.values()) {
            RegisteredConfig<?> registration =
                (RegisteredConfig<?>) staticField(slot.getClass(), "value").get(slot);
            if (registration != null) {
                registration.notifier().close();
                registration.model().callbacks().close();
            }
        }
        clearStaticMap(ConfigRegistry.class, "REGISTRATIONS");
        clearStaticMap(ConfigRegistry.class, "REGISTRATION_WAITS");
        Object ownership = staticField(ConfigRegistry.class, "OWNERSHIP").get(null);
        clearMap(staticField(ownership.getClass(), "owners").get(ownership));
        Consumer<Object> noCallback = ignored -> {};
        setStaticField(ConfigRegistry.class, "registrationCallback", noCallback);
        setStaticField(ConfigRegistry.class, "releaseCallback", noCallback);

        clearStaticMap(ConfigSyncRegistry.class, "SYNCED");
        setStaticField(ClientConfigSync.class, "CLIENT_MAIN_THREAD_EXECUTOR",
            (java.util.concurrent.Executor) Runnable::run);
        setServerMainThreadExecutor(Runnable::run);
        ConfigEventThreads.setClientMainThread(Runnable::run);
        ConfigEventThreads.setServerMainThread(Runnable::run);
        ClientConfigSync.resetClientConnection();
        setStaticField(ServerConfigSync.class, "BROADCAST_SCHEDULER", (Consumer<Object>) ignored -> {});
        setStaticField(ClientConfigSync.class, "REQUEST_SCHEDULER", (Consumer<Object>) ignored -> {});
        setStaticField(ClientConfigSync.class, "DISCONNECT_SCHEDULER", (Consumer<Object>) ignored -> {});
        setStaticField(ServerConfigSync.class, "MANIFEST_SCHEDULER", (Runnable) () -> {});
        setStaticField(ClientConfigSync.class, "REMOTE_CONNECTION_CHECK",
            (BooleanSupplier) () -> false);
        setStaticField(ConfigSyncRegistry.class, "INITIALIZED", false);
        clearCollection(staticField(LiteConfig.codecs().getClass(), "plannedTypes")
            .get(LiteConfig.codecs()));
    }

    private static void clearThreadBinding(String name) throws ReflectiveOperationException {
        ((AtomicReference<?>) staticField(ConfigEventThreads.class, name).get(null)).set(null);
    }

    @SuppressWarnings("unchecked")
    private static void setServerMainThreadExecutor(Executor executor) throws ReflectiveOperationException {
        AtomicReference<Executor> reference = (AtomicReference<Executor>)
            staticField(ServerConfigSync.class, "SERVER_MAIN_THREAD_EXECUTOR").get(null);
        reference.set(executor);
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
        Field field = staticField(owner, name);
        if (AtomicReference.class.isAssignableFrom(field.getType())) {
            @SuppressWarnings("unchecked")
            AtomicReference<Object> reference = (AtomicReference<Object>) field.get(null);
            reference.set(value);
        } else {
            field.set(null, value);
        }
    }

    private static Field staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
