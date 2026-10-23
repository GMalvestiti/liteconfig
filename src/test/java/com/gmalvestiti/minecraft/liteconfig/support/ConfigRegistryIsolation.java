package com.gmalvestiti.minecraft.liteconfig.support;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

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
        setStaticField(ClientConfigSync.class, "PLATFORM", null);
        setStaticField(CommonServerPlatform.class, "CURRENT", null);
    }

    private static void resetRegistries() throws ReflectiveOperationException {
        Map<?, ?> registrations = (Map<?, ?>) staticField(ConfigRegistry.class, "REGISTRATIONS").get(null);
        for (Object slot : registrations.values()) {
            RegisteredConfig<?> registration =
                (RegisteredConfig<?>) staticField(slot.getClass(), "value").get(slot);
            if (registration != null) {
                registration.holderEvents().close();
                registration.model().listeners().close();
            }
        }
        clearStaticMap(ConfigRegistry.class, "REGISTRATIONS");
        Object ownership = staticField(ConfigRegistry.class, "OWNERSHIP").get(null);
        clearMap(staticField(ownership.getClass(), "owners").get(ownership));
        setStaticField(ConfigRegistry.class, "activeWorldRoot", null);
        setStaticField(ConfigRegistry.class, "worldVersion", 0L);

        clearStaticMap(ConfigSyncRegistry.class, "SYNCED");
        TestSyncPlatform.reset();
        ClientConfigSync.resetClientConnection();
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
