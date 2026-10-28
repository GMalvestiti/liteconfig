package com.gmalvestiti.minecraft.liteconfig.validation.check;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Checks the declared constraints of a config object tree and reports what fails. */
public final class ConstraintValidator {

    private final ConfigFieldAccess fieldAccess;
    private final Set<String> requiredChoices;
    private final Set<String> requiredObjects;

    public ConstraintValidator(ConfigFieldAccess fieldAccess, ConfigMetadata metadata) {
        this.fieldAccess = fieldAccess;
        this.requiredChoices = metadata.flatten()
            .filter(property -> !property.constraints().allowedValues().isEmpty())
            .filter(property -> property.defaultValue() != null)
            .map(ConfigProperty::path)
            .collect(Collectors.toUnmodifiableSet());
        this.requiredObjects = requiredObjects(metadata);
    }

    public List<Violation> validate(Object candidate) {
        if (candidate == null) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        collect(candidate, "", null, violations);

        return violations.isEmpty() ? List.of() : Collections.unmodifiableList(violations);
    }

    private void collect(Object owner, String prefix, Set<Object> ancestors, List<Violation> violations) {
        boolean tracked = false;

        try {
            for (ConstraintPlan.Step step : ConstraintPlan.of(owner.getClass())) {
                Object value = fieldAccess.read(step.field(), owner);

                if (step.rules() != null) {
                    step.rules().check(prefix, step.key(), value, requiredChoices, violations);
                }

                if (!step.descendable()) {
                    continue;
                }

                String path = PropertyRules.path(prefix, step.key());
                if (value == null) {
                    if (requiredObjects.contains(path)) {
                        violations.add(Violation.of("required." + path, "%s must not be null".formatted(path)));
                    }
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
                    collect(value, path, ancestors, violations);
                }
            }
        } finally {
            if (tracked) {
                ancestors.remove(owner);
            }
        }
    }

    private static Set<String> requiredObjects(ConfigMetadata metadata) {
        Set<String> required = new LinkedHashSet<>();

        for (ConfigProperty property : metadata.synced()) {
            String path = property.path();
            int separator = path.indexOf('.');

            while (separator >= 0) {
                required.add(path.substring(0, separator));
                separator = path.indexOf('.', separator + 1);
            }
        }

        return Set.copyOf(required);
    }
}
