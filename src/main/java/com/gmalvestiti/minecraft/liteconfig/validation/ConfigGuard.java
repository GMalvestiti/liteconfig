package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncSchema;
import com.gmalvestiti.minecraft.liteconfig.validation.check.constraint.ConstraintValidator;
import com.gmalvestiti.minecraft.liteconfig.validation.check.ExtensionValidator;
import com.gmalvestiti.minecraft.liteconfig.validation.check.AllowedValueNormalizer;
import com.gmalvestiti.minecraft.liteconfig.validation.check.RestartInspector;

import java.util.List;
import java.util.stream.Collectors;

/** Normalizes, validates and restart-checks config candidates for one registration. */
public final class ConfigGuard<T> {

    private final ConfigScope scope;
    private final String typeName;
    private final ConfigSyncSchema<T> syncSchema;
    private final AllowedValueNormalizer normalizer;
    private final ConstraintValidator constraints;
    private final ExtensionValidator extension;
    private final RestartInspector restart;

    public ConfigGuard(ConfigModel<T> model, ConfigSyncSchema<T> syncSchema) {
        this.syncSchema = syncSchema;
        this.scope = model.scope();
        this.typeName = model.typeName();
        this.normalizer = new AllowedValueNormalizer(model.fieldAccess(), model.fields());
        this.constraints = new ConstraintValidator(model.fieldAccess(), model.metadata());
        this.extension = new ExtensionValidator(model.scope(), model.extensions().label());
        this.restart = new RestartInspector(model.fieldAccess(), model.fields());
    }

    public void normalize(T candidate) {
        normalizer.normalize(candidate);
    }

    public void validate(T candidate) {
        List<Violation> violations = violationsOf(candidate);

        if (!violations.isEmpty()) {
            throw scope.exception(ConfigError.VALIDATION_FAILED, violations, typeName, summary(violations));
        }

        if (syncSchema != null) {
            syncSchema.validate(candidate);
        }
    }

    /** Declared constraints win; the extension hook only runs on a candidate that passes them. */
    List<Violation> violationsOf(T candidate) {
        List<Violation> declared = constraints.validate(candidate);

        return declared.isEmpty() ? extension.validate(candidate) : declared;
    }

    public void rejectRestartChanges(T current, T candidate) {
        List<Violation> violations = restart.violations(current, candidate);

        if (!violations.isEmpty()) {
            throw scope.exception(ConfigError.RESTART_FIELD_CHANGED, violations, typeName, summary(violations));
        }
    }

    public void keepRestartValues(T current, T candidate) {
        restart.keepCurrent(current, candidate);
    }

    public boolean hasRestartChanges(T current, T candidate) {
        return restart.hasChanges(current, candidate);
    }

    private static String summary(List<Violation> violations) {
        return violations.stream().map(Violation::message).collect(Collectors.joining("; "));
    }
}
