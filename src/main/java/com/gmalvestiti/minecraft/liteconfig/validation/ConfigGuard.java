package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncSchema;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigExtensionLookup;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigSerialization;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfigGuard<T> {

    private final ConfigScope scope;
    private final String typeName;
    private final ConfigFieldAccess fieldAccess;
    private final ConfigFieldPlan fields;
    private final ConfigExtensionLookup extensions;
    private final ConfigConstraintValidator constraintValidator;
    private final ConfigSyncSchema<T> syncSchema;

    public ConfigGuard(ConfigModel<T> model, ConfigSyncSchema<T> syncSchema) {
        this.syncSchema = syncSchema;
        this.scope = model.scope();
        this.typeName = model.typeName();
        this.fieldAccess = model.fieldAccess();
        this.fields = model.fields();
        this.extensions = model.extensions();
        this.constraintValidator = new ConfigConstraintValidator(
            model.fieldAccess(), model.fields(), model.metadata());
    }

    public void normalize(T candidate) {
        normalize(candidate, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private void normalize(Object candidate, Set<Object> ancestors) {
        if (candidate == null || !ancestors.add(candidate)) {
            return;
        }

        try {
            for (DeclaredConfig.Property property : fields.properties(candidate.getClass())) {

                Object value = fieldAccess.read(property.field(), candidate);
                ConfigConstraints constraints = property.constraints();

                if (value instanceof String text && !constraints.allowedValues().isEmpty()) {
                    String normalized = constraints.normalization().apply(text, constraints.trim());
                    if (!normalized.equals(text)) {
                        fieldAccess.write(property.field(), candidate, normalized);
                    }
                } else if (fields.descendable(property)) {
                    normalize(value, ancestors);
                }
            }
        } finally {
            ancestors.remove(candidate);
        }
    }

    public void validate(T candidate) {
        List<Violation> violations = violationsOf(candidate);

        if (!violations.isEmpty()) {
            throw scope.exception(ConfigError.VALIDATION_FAILED, violations, typeName, summaryOf(violations));
        }

        if (syncSchema != null) {
            syncSchema.validate(candidate);
        }
    }

    public List<Violation> violationsOf(T candidate) {
        List<Violation> declaredViolations = constraintValidator.run(candidate);
        if (!declaredViolations.isEmpty()) {
            return declaredViolations;
        }

        List<Violation> violations = new ArrayList<>();
        ConfigExtension extension = extensions.resolve(candidate);
        if (extension != null) {
            append(invoke(extension), violations);
        }

        return violations.isEmpty() ? List.of() : List.copyOf(violations);
    }

    public void enforceRestart(T current, T candidate) {
        List<Violation> violations = new ArrayList<>();
        scanRestart(current, candidate, null, RestartMode.COLLECT, violations);

        if (!violations.isEmpty()) {
            throw scope.exception(ConfigError.RESTART_FIELD_CHANGED, violations, typeName, summaryOf(violations));
        }
    }

    public void carryOverRestart(T current, T candidate) {
        scanRestart(current, candidate, null, RestartMode.CARRY_OVER, null);
    }

    public boolean changesRestart(T current, T candidate) {
        return scanRestart(current, candidate, null, RestartMode.DETECT, null);
    }

    private List<Violation> invoke(ConfigExtension extension) {
        List<Violation> produced = new ArrayList<>();

        try {
            extension.validate(produced);
        } catch (LiteConfigException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw scope.exception(ConfigError.VALIDATOR_FAILED, ex, extensions.label(), ex.getMessage());
        }

        return produced;
    }

    private void append(List<Violation> produced, List<Violation> violations) {
        for (Violation violation : produced) {
            if (violation == null) {
                throw scope.exception(ConfigError.VALIDATOR_PRODUCED_NULL_VIOLATION, extensions.label());
            }

            if (violation.id() == null || violation.id().isBlank()) {
                throw scope.exception(ConfigError.VALIDATOR_PRODUCED_BLANK_ID, extensions.label());
            }

            violations.add(violation);
        }
    }


    private boolean scanRestart(
        Object current,
        Object candidate,
        Set<Object> ancestors,
        RestartMode mode,
        List<Violation> violations
    ) {
        if (current == null
            || candidate == null
            || current.getClass() != candidate.getClass()
            || (ancestors != null && ancestors.contains(current))) {
            return false;
        }

        boolean found = false;
        boolean tracked = false;

        try {
            for (DeclaredConfig.Property property : fields.properties(current.getClass())) {
                Field field = property.field();

                if (property.restart()) {
                    Object before = fieldAccess.read(field, current);

                    if (mode == RestartMode.CARRY_OVER) {
                        fieldAccess.write(field, candidate, before);
                    } else if (changed(before, fieldAccess.read(field, candidate))) {
                        if (mode == RestartMode.DETECT) {
                            return true;
                        }

                        violations.add(restartViolation(field));
                        found = true;
                    }
                } else if (fields.descendable(property)) {
                    if (!tracked) {
                        if (ancestors == null) {
                            ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
                        }

                        ancestors.add(current);
                        tracked = true;
                    }

                    if (scanRestart(
                        fieldAccess.read(field, current),
                        fieldAccess.read(field, candidate),
                        ancestors,
                        mode,
                        violations)) {
                        if (mode == RestartMode.DETECT) {
                            return true;
                        }

                        found = true;
                    }
                }
            }
        } finally {
            if (tracked) {
                ancestors.remove(current);
            }
        }

        return found;
    }

    private static boolean changed(Object before, Object after) {
        return !Objects.deepEquals(before, after)
            && !ConfigSerialization.toTree(before).equals(ConfigSerialization.toTree(after));
    }

    private static String summaryOf(List<Violation> violations) {
        return violations.stream().map(Violation::message).collect(Collectors.joining("; "));
    }

    private static Violation restartViolation(Field field) {
        return Violation.of(
            "restart." + field.getName(),
            "%s in %s only applies at startup; edit the config file and restart instead"
                .formatted(field.getName(), field.getDeclaringClass().getSimpleName()));
    }

    private enum RestartMode { DETECT, COLLECT, CARRY_OVER }
}
