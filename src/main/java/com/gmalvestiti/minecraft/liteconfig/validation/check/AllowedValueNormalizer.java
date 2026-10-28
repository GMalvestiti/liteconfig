package com.gmalvestiti.minecraft.liteconfig.validation.check;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Rewrites restricted string fields into their canonical case and trimming. */
public final class AllowedValueNormalizer {

    private final ConfigFieldAccess fieldAccess;
    private final ConfigFieldPlan fields;

    public AllowedValueNormalizer(ConfigFieldAccess fieldAccess, ConfigFieldPlan fields) {
        this.fieldAccess = fieldAccess;
        this.fields = fields;
    }

    public void normalize(Object candidate) {
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
}
