package com.gmalvestiti.minecraft.liteconfig.validation.check;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class AllowedValueNormalizer {

    private final ConfigFieldAccess fieldAccess;
    private final ConfigFieldPlan fields;

    public AllowedValueNormalizer(ConfigFieldAccess fieldAccess, ConfigFieldPlan fields) {
        this.fieldAccess = fieldAccess;
        this.fields = fields;
    }

    public void normalize(Object candidate) {
        if (candidate != null) {
            normalize(candidate, null);
        }
    }

    private void normalize(Object owner, Set<Object> ancestors) {
        boolean tracked = false;

        try {
            for (DeclaredConfig.Property property : fields.properties(owner.getClass())) {
                ConfigConstraints constraints = property.constraints();
                boolean restricted = !constraints.allowedValues().isEmpty();
                boolean descendable = !restricted && fields.descendable(property);

                if (!restricted && !descendable) {
                    continue;
                }

                Object value = fieldAccess.read(property.field(), owner);

                if (restricted) {
                    if (value instanceof String text) {
                        String normalized = constraints.normalization().apply(text, constraints.trim());
                        if (!normalized.equals(text)) {
                            fieldAccess.write(property.field(), owner, normalized);
                        }
                    }
                    continue;
                }

                if (value == null) {
                    continue;
                }

                if (!tracked) {
                    if (ancestors == null) {
                        ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
                    }

                    ancestors.add(owner);
                    tracked = true;
                }

                if (!ancestors.contains(value)) {
                    normalize(value, ancestors);
                }
            }
        } finally {
            if (tracked) {
                ancestors.remove(owner);
            }
        }
    }
}