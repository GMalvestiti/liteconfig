package com.gmalvestiti.minecraft.liteconfig.validation.check;

import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Per-class list of the properties validation has to look at; the rest are never read. */
final class ConstraintPlan {

    private static final ClassValue<List<Step>> STEPS = new ClassValue<>() {
        @Override
        protected List<Step> computeValue(Class<?> type) {
            ConfigFieldPlan plan = ConfigFieldPlan.of(type);
            List<Step> steps = new ArrayList<>();

            for (DeclaredConfig.Property property : plan.properties(type)) {
                PropertyRules rules = property.constraints().isEmpty()
                    ? null
                    : new PropertyRules(property.constraints());
                boolean descendable = plan.descendable(property);

                if (rules != null || descendable) {
                    steps.add(new Step(property.field(), property.key(), rules, descendable));
                }
            }

            return List.copyOf(steps);
        }
    };

    private ConstraintPlan() {}

    static List<Step> of(Class<?> type) {
        return STEPS.get(type);
    }

    record Step(Field field, String key, PropertyRules rules, boolean descendable) {}
}
