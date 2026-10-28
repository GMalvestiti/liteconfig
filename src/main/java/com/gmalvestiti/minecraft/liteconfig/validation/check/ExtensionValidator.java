package com.gmalvestiti.minecraft.liteconfig.validation.check;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.util.ArrayList;
import java.util.List;

/** Runs the validation hook of a config that implements {@link ConfigExtension}. */
public final class ExtensionValidator {

    private final ConfigScope scope;
    private final String label;

    public ExtensionValidator(ConfigScope scope, String label) {
        this.scope = scope;
        this.label = label;
    }

    public List<Violation> validate(Object candidate) {
        if (!(candidate instanceof ConfigExtension extension)) {
            return List.of();
        }

        List<Violation> produced = new ArrayList<>();

        try {
            extension.validate(produced);
        } catch (LiteConfigException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw scope.exception(ConfigError.VALIDATOR_FAILED, ex, label, ex.getMessage());
        }

        for (Violation violation : produced) {
            if (violation == null) {
                throw scope.exception(ConfigError.VALIDATOR_PRODUCED_NULL_VIOLATION, label);
            }

            if (violation.id() == null || violation.id().isBlank()) {
                throw scope.exception(ConfigError.VALIDATOR_PRODUCED_BLANK_ID, label);
            }
        }

        return produced;
    }
}
