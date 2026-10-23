package com.gmalvestiti.minecraft.liteconfig.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFieldPlanTest {

    @Test
    void testPlansRecursiveAndSharedNestedTypesWithoutRevisitingThem() {
        ConfigFieldPlan plan = ConfigFieldPlan.of(RecursiveRoot.class);

        assertEquals(List.of("first", "second"),
            plan.properties(RecursiveRoot.class).stream().map(DeclaredConfig.Property::key).toList());
        assertEquals(List.of("next", "value"),
            plan.properties(Section.class).stream().map(DeclaredConfig.Property::key).toList());
        assertSame(DeclaredConfig.of(Section.class).properties(), plan.properties(Section.class));
        assertTrue(plan.descendable(plan.properties(Section.class).getFirst()));
        assertSame(plan, ConfigFieldPlan.of(RecursiveRoot.class));
    }

    @Test
    void testFallbackPlansRetainInheritedFieldClassification() {
        ConfigFieldPlan plan = ConfigFieldPlan.of(RecursiveRoot.class);
        DeclaredConfig.Property inherited = plan.properties(InheritedSection.class).getFirst();

        assertEquals("next", inherited.key());
        assertTrue(plan.descendable(inherited));
    }

    static class RecursiveRoot {
        Section first;
        Section second;
    }

    static class Section {
        Section next;
        int value;
    }

    static class InheritedSection extends Section {}
}
