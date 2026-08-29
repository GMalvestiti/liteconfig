package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigExtensionLookup;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigBinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfigGuard<T> {

    private final ConfigScope scope;
    private final String typeName;
    private final ConfigFieldAccess fieldAccess;
    private final ConfigFieldPlan fields;
    private final ConfigExtensionLookup extensions;
    private final ConfigConstraintValidator constraintValidator;

    public ConfigGuard(ConfigModel<T> model) {
        this.scope = model.scope();
        this.typeName = model.typeName();
        this.fieldAccess = model.fieldAccess();
        this.fields = model.fields();
        this.extensions = model.extensions();
        this.constraintValidator = new ConfigConstraintValidator(
            model.fieldAccess(), model.fields(), model.metadata());
    }

    public void validate(T candidate) {
        List<Violation> violations = violationsOf(candidate);

        if (!violations.isEmpty()) {
            throw scope.exception(ConfigError.VALIDATION_FAILED, violations, typeName, summaryOf(violations));
        }
    }

    public List<Violation> violationsOf(T candidate) {
        List<Violation> violations = new ArrayList<>(constraintValidator.run(candidate));

        ConfigExtension extension = extensions.resolve(candidate);
        if (extension != null) {
            append(invoke(extension), violations);
        }

        return violations.isEmpty() ? List.of() : List.copyOf(violations);
    }

    public void enforceRestart(T current, T candidate) {
        List<Violation> violations = restartFieldsOf(current, candidate).stream()
            .filter(this::changed)
            .map(ConfigGuard::restartViolation)
            .toList();

        if (!violations.isEmpty()) {
            throw scope.exception(ConfigError.RESTART_FIELD_CHANGED, violations, typeName, summaryOf(violations));
        }
    }

    public void carryOverRestart(T current, T candidate) {
        for (RestartField found : restartFieldsOf(current, candidate)) {
            fieldAccess.write(found.field, found.candidate, fieldAccess.read(found.field, found.current));
        }
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

    private List<RestartField> restartFieldsOf(Object current, Object candidate) {
        List<RestartField> found = new ArrayList<>();

        collectRestartFields(
            current,
            candidate,
            Collections.newSetFromMap(new IdentityHashMap<>()),
            found);

        return found;
    }

    private void collectRestartFields(
        Object current,
        Object candidate,
        Set<Object> ancestors,
        List<RestartField> restartFields
    ) {
        if (current == null
            || candidate == null
            || current.getClass() != candidate.getClass()
            || !ancestors.add(current)) {
            return;
        }

        try {
            for (DeclaredConfig.Property property : fields.properties(current.getClass())) {
                Field field = property.field();

                if (property.restart()) {
                    restartFields.add(new RestartField(field, current, candidate));
                } else if (fields.descendable(property)) {
                    collectRestartFields(
                        fieldAccess.read(field, current),
                        fieldAccess.read(field, candidate),
                        ancestors,
                        restartFields);
                }
            }
        } finally {
            ancestors.remove(current);
        }
    }

    private boolean changed(RestartField found) {
        return !ConfigBinder.toTree(fieldAccess.read(found.field, found.current))
            .equals(ConfigBinder.toTree(fieldAccess.read(found.field, found.candidate)));
    }

    private static String summaryOf(List<Violation> violations) {
        return violations.stream().map(Violation::message).collect(Collectors.joining("; "));
    }

    private static Violation restartViolation(RestartField found) {
        return Violation.of(
            "restart." + found.field().getName(),
            "%s in %s only applies at startup; edit the config file and restart instead"
                .formatted(found.field().getName(), found.field().getDeclaringClass().getSimpleName()));
    }

    private record RestartField(Field field, Object current, Object candidate) {}
}
