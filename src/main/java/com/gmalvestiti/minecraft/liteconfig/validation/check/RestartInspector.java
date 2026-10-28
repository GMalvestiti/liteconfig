package com.gmalvestiti.minecraft.liteconfig.validation.check;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigSerialization;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RestartInspector {

    private enum Mode { DETECT, COLLECT, CARRY_OVER }

    private final ConfigFieldAccess fieldAccess;
    private final ConfigFieldPlan fields;

    public RestartInspector(ConfigFieldAccess fieldAccess, ConfigFieldPlan fields) {
        this.fieldAccess = fieldAccess;
        this.fields = fields;
    }

    public boolean hasChanges(Object current, Object candidate) {
        return inspect(current, candidate, null, Mode.DETECT, null);
    }

    public List<Violation> violations(Object current, Object candidate) {
        List<Violation> violations = new ArrayList<>();
        inspect(current, candidate, null, Mode.COLLECT, violations);

        return violations;
    }

    public void keepCurrent(Object current, Object candidate) {
        inspect(current, candidate, null, Mode.CARRY_OVER, null);
    }

    private boolean inspect(
        Object current,
        Object candidate,
        Set<Object> ancestors,

        Mode mode,
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

                    if (mode == Mode.CARRY_OVER) {
                        fieldAccess.write(field, candidate, before);
                    } else if (changed(before, fieldAccess.read(field, candidate))) {
                        if (mode == Mode.DETECT) {
                            return true;
                        }

                        violations.add(violation(field));
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

                    if (inspect(
                        fieldAccess.read(field, current),
                        fieldAccess.read(field, candidate),
                        ancestors,
                        mode,
                        violations)) {
                        if (mode == Mode.DETECT) {
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

    private static Violation violation(Field field) {
        return Violation.of(
            "restart." + field.getName(),
            "%s in %s only applies at startup; edit the config file and restart instead"
                .formatted(field.getName(), field.getDeclaringClass().getSimpleName()));
    }
}
