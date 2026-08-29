package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigConstraintValidatorTest {

    private final ConfigConstraintValidator validator =
        new ConfigConstraintValidator(TestFixtures.fieldAccess());

    @Test
    void testAcceptsTheDeclaredDefaults() {
        assertEquals(List.of(), validator.run(new TestFixtures.ConstrainedConfig()));
    }

    @Test
    void testReportsAValueBelowTheMinimum() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.hudScale = 0;

        assertEquals(List.of("range.hudScale"), idsOf(candidate));
    }

    @Test
    void testReportsAValueAboveTheMaximum() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.hudScale = 9;

        assertEquals(List.of("range.hudScale"), idsOf(candidate));
    }

    @Test
    void testAcceptsAnyValueAboveAnOpenEndedMinimum() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.spawnChance = 4096;

        assertEquals(List.of(), idsOf(candidate));
    }

    @Test
    void testReportsTextThatDoesNotMatchThePattern() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.profileName = "Default";

        assertEquals(List.of("pattern.profileName"), idsOf(candidate));
    }

    @Test
    void testReportsTextLongerThanAllowed() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.profileName = "waytoolongforthisfield";

        assertTrue(idsOf(candidate).contains("length.profileName"));
    }

    @Test
    void testReportsACollectionOutsideItsAllowedSize() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.features.clear();

        assertEquals(List.of("length.features"), idsOf(candidate));
    }

    @Test
    void testReportsAnUnknownChoiceForAFixedSetOfValues() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.mode = null;

        assertEquals(List.of("value.mode"), idsOf(candidate));
    }

    @Test
    void testChecksNestedObjectsUnderTheirFullPath() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.section.maxDepth = 40;

        assertEquals(List.of("range.section.max_depth"), idsOf(candidate));
    }

    @Test
    void testAllowsANullNestedObjectThatIsNotSynced() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.section = null;

        assertEquals(List.of(), idsOf(candidate));
    }

    @Test
    void testRejectsANullNestedObjectRequiredBySync() {
        ConfigGuard<TestFixtures.SyncedConfig> guard =
            new ConfigGuard<>(TestFixtures.model(TestFixtures.SyncedConfig.class));
        TestFixtures.SyncedConfig candidate = new TestFixtures.SyncedConfig();
        candidate.section = null;

        assertEquals(
            List.of("required.section"),
            guard.violationsOf(candidate).stream().map(Violation::id).toList());
    }

    @Test
    void testReportsEveryBrokenRuleAtOnce() {
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.hudScale = 99;
        candidate.profileName = "NOPE";
        candidate.section.maxDepth = 0;

        assertEquals(
            List.of("range.hudScale", "pattern.profileName", "range.section.max_depth"), idsOf(candidate));
    }

    @Test
    void testSurfacesConstraintFailuresThroughTheValidationRunner() {
        ConfigGuard<TestFixtures.ConstrainedConfig> guard =
            new ConfigGuard<>(TestFixtures.model(TestFixtures.ConstrainedConfig.class));
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.hudScale = 99;

        List<String> ids = new ArrayList<>();
        guard.violationsOf(candidate).forEach(violation -> ids.add(violation.id()));

        assertEquals(List.of("range.hudScale"), ids);
    }

    @Test
    void testIgnoresConfigsThatDeclareNoConstraints() {
        assertEquals(List.of(), validator.run(new TestFixtures.SimpleConfig()));
    }

    @Test
    void testRejectsNaNWhenARangeIsDeclared() {
        assertEquals(List.of("range.value"), validator.run(new NaNValueConfig()).stream().map(Violation::id).toList());
    }

    @Test
    void testAllowsAnEnumThatDeliberatelyDefaultsToNull() {
        ConfigGuard<NullableEnumConfig> guard =
            new ConfigGuard<>(TestFixtures.model(NullableEnumConfig.class));

        assertEquals(List.of(), guard.violationsOf(new NullableEnumConfig()));
    }

    private List<String> idsOf(TestFixtures.ConstrainedConfig candidate) {
        return validator.run(candidate).stream().map(Violation::id).toList();
    }

    static class NaNValueConfig {
        @Range
        double value = Double.NaN;
    }

    @Config(name = "nullable-enum")
    static class NullableEnumConfig {
        TestFixtures.Mode value;
    }
}
