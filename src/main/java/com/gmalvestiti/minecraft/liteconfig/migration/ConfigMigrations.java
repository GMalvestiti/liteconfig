package com.gmalvestiti.minecraft.liteconfig.migration;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Migration;
import com.gmalvestiti.minecraft.liteconfig.api.migration.ConfigData;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConfigMigrations {

    public static final String VERSION_KEY = "configVersion";
    private static final int INITIAL_VERSION = 1;

    private static final ClassValue<ConfigMigrations> CACHE = new ClassValue<>() {
        @Override
        protected ConfigMigrations computeValue(Class<?> type) {
            return new ConfigMigrations(type);
        }
    };

    private final Class<?> configType;
    private final int declaredVersion;
    private final Method[] steps;
    private final List<String> problems;

    private ConfigMigrations(Class<?> configType) {
        List<String> found = new ArrayList<>();
        this.configType = configType;
        this.declaredVersion = DeclaredConfig.of(configType).version();
        this.steps = stepsOf(configType, declaredVersion, found);
        this.problems = List.copyOf(found);
    }

    public static ConfigMigrations of(Class<?> configType) {
        return CACHE.get(Objects.requireNonNull(configType, "configType"));
    }

    public List<String> problems() {
        return problems;
    }

    public JsonObject apply(JsonObject tree, ConfigScope scope) {
        if (declaredVersion <= 0) {
            return tree;
        }

        int found = versionOf(tree, scope);
        if (found > declaredVersion) {
            throw scope.exception(ConfigError.CONFIG_VERSION_TOO_NEW, configType.getName(), found, declaredVersion);
        }

        ConfigData data = new JsonConfigData(tree);
        for (int from = found; from < declaredVersion; from++) {
            runStep(data, from, scope);
        }

        return tree;
    }

    /**
     * Writes the declared version into {@code tree}, ahead of the config's own keys.
     */
    public JsonObject stamp(JsonObject tree) {
        if (declaredVersion <= 0) {
            return tree;
        }

        JsonObject stamped = new JsonObject();
        stamped.addProperty(VERSION_KEY, declaredVersion);

        for (Map.Entry<String, JsonElement> property : tree.entrySet()) {
            if (!VERSION_KEY.equals(property.getKey())) {
                stamped.add(property.getKey(), property.getValue());
            }
        }

        return stamped;
    }

    private void runStep(ConfigData data, int from, ConfigScope scope) {
        Method step = steps[from];
        if (step == null) {
            throw scope.exception(
                ConfigError.MISSING_CONFIG_MIGRATION, configType.getName(), from, from + 1);
        }

        try {
            step.invoke(null, data);
        } catch (InvocationTargetException invocation) {
            throw failure(invocation.getCause(), from, scope);
        } catch (IllegalAccessException | RuntimeException thrown) {
            throw failure(thrown, from, scope);
        }
    }

    private LiteConfigException failure(Throwable cause, int from, ConfigScope scope) {
        Throwable rootCause = rootCause(cause);
        return scope.exception(
            ConfigError.MIGRATION_FAILED,
            rootCause,
            configType.getName(),
            from,
            from + 1,
            rootCause.getMessage());
    }

    private Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(cause);

        Throwable next;
        while ((next = cause.getCause()) != null && visited.add(next)) {
            cause = next;
        }

        return cause;
    }

    private Method[] stepsOf(
        Class<?> configType,
        int declaredVersion,
        List<String> problems
    ) {
        Method[] steps = new Method[Math.max(declaredVersion, 1)];
        List<Method> declared = new ArrayList<>(List.of(configType.getDeclaredMethods()));
        declared.sort(Comparator.comparing(Method::getName));

        for (Method method : declared) {
            Migration migration = method.getAnnotation(Migration.class);
            if (migration == null || !isWellFormed(method, migration, problems)) {
                continue;
            }

            int from = migration.from();
            if (from >= declaredVersion) {
                problems.add(method.getName() + "() declares from = " + from + ", but the config version is " + declaredVersion);
                continue;
            }

            if (!makeAccessible(method)) {
                problems.add(method.getName() + "() must be accessible");
                continue;
            }

            if (steps[from] != null) {
                problems.add("more than one migration declares from = " + migration.from());
                continue;
            }

            steps[from] = method;
        }

        for (int from = 1; from < declaredVersion; from++) {
            if (steps[from] == null) {
                problems.add("missing migration from version " + from + " to " + (from + 1));
            }
        }

        return steps;
    }

    private boolean makeAccessible(Method method) {
        try {
            return method.trySetAccessible();
        } catch (SecurityException denied) {
            return false;
        }
    }

    private boolean isWellFormed(Method method, Migration migration, List<String> problems) {
        List<String> found = new ArrayList<>();

        if (!Modifier.isStatic(method.getModifiers())) {
            found.add("must be static");
        }

        if (method.getReturnType() != void.class) {
            found.add("must return void");
        }

        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != ConfigData.class) {
            found.add("must take a single " + ConfigData.class.getSimpleName() + " parameter");
        }

        if (migration.from() < 1) {
            found.add("must declare from >= 1");
        }

        found.forEach(problem -> problems.add(method.getName() + "() " + problem));
        return found.isEmpty();
    }

    private int versionOf(JsonObject tree, ConfigScope scope) {
        JsonElement found = tree.get(VERSION_KEY);

        if (found == null) {
            return INITIAL_VERSION;
        }

        if (!found.isJsonPrimitive() || !found.getAsJsonPrimitive().isNumber()) {
            throw malformedVersion(scope, null);
        }

        try {
            int version = new BigDecimal(found.getAsString()).intValueExact();

            if (version <= 0) {
                throw malformedVersion(scope, null);
            }

            return version;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw malformedVersion(scope, ex);
        }
    }

    private LiteConfigException malformedVersion(ConfigScope scope, Throwable cause) {
        return scope.exception(ConfigError.MALFORMED_CONFIG_DATA, cause, configType.getName());
    }
}
