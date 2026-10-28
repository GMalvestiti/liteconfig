package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.AllowedValues;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.NotBlank;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.NotNull;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.validation.check.constraint.ConstraintValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstraintValidatorTest {

    private final ConstraintValidator validator =
        validatorFor(TestFixtures.ConstrainedConfig.class);

    @Test
    void testAcceptsTheDeclaredDefaults() {
        assertEquals(List.of(), validator.validate(new TestFixtures.ConstrainedConfig()));
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
    void testAcceptsAllowedStringValuesWithoutCase() {
        assertEquals(List.of(), validatorFor(DatabaseConfig.class).validate(new DatabaseConfig("MySQL")));
    }

    @Test
    void testReportsAStringOutsideTheAllowedValues() {
        assertEquals(
            List.of("value.database"),
            validatorFor(DatabaseConfig.class).validate(new DatabaseConfig("postgres")).stream().map(Violation::id).toList()
        );
    }

    @Test
    void testRejectsAMissingAllowedStringWithANonNullDefault() {
        assertEquals(
            List.of("value.database"),
            validatorFor(DatabaseConfig.class).validate(new DatabaseConfig(null))
                .stream().map(Violation::id).toList());
    }

    @Test
    void testAllowsAnAllowedStringThatDeliberatelyDefaultsToNull() {
        assertEquals(List.of(), validatorFor(NullableDatabaseConfig.class).validate(new NullableDatabaseConfig()));
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
            new ConfigGuard<>(TestFixtures.model(TestFixtures.SyncedConfig.class), null);
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
            new ConfigGuard<>(TestFixtures.model(TestFixtures.ConstrainedConfig.class), null);
        TestFixtures.ConstrainedConfig candidate = new TestFixtures.ConstrainedConfig();
        candidate.hudScale = 99;

        List<String> ids = new ArrayList<>();
        guard.violationsOf(candidate).forEach(violation -> ids.add(violation.id()));

        assertEquals(List.of("range.hudScale"), ids);
    }

    @Test
    void testIgnoresConfigsThatDeclareNoConstraints() {
        assertEquals(List.of(), validatorFor(TestFixtures.SimpleConfig.class).validate(new TestFixtures.SimpleConfig()));
    }

    @Test
    void testRejectsNaNWhenARangeIsDeclared() {
        assertEquals(List.of("range.value"), validatorFor(NaNValueConfig.class).validate(new NaNValueConfig()).stream().map(Violation::id).toList());
    }

    @Test
    void testAllowsAnEnumThatDeliberatelyDefaultsToNull() {
        ConfigGuard<NullableEnumConfig> guard =
            new ConfigGuard<>(TestFixtures.model(NullableEnumConfig.class), null);

        assertEquals(List.of(), guard.violationsOf(new NullableEnumConfig()));
    }

    @Test
    void testReportsANullRequiredValue() {
        assertEquals(
            List.of("notnull.value"),
            validatorFor(NotNullConfig.class).validate(new NotNullConfig()).stream().map(Violation::id).toList()
        );
    }

    @Test
    void testReportsNullEmptyAndWhitespaceOnlyBlankValues() {
        assertEquals(List.of("notblank.value"), blankViolationIds(null));
        assertEquals(List.of("notblank.value"), blankViolationIds(""));
        assertEquals(List.of("notblank.value"), blankViolationIds("  \t"));
    }

    @Test
    void testAcceptsTextWithANonWhitespaceCharacter() {
        assertEquals(List.of(), blankViolationIds(" profile "));
    }

    private List<String> idsOf(TestFixtures.ConstrainedConfig candidate) {
        return validator.validate(candidate).stream().map(Violation::id).toList();
    }

    private static ConstraintValidator validatorFor(Class<?> type) {
        ConfigModel<?> model = TestFixtures.model(type);
        return new ConstraintValidator(model.fieldAccess(), model.metadata());
    }

    static class NaNValueConfig {
        @Range
        double value = Double.NaN;
    }

    @Config(name = "nullable-enum")
    static class NullableEnumConfig {
        TestFixtures.Mode value;
    }

    static class DatabaseConfig {
        @AllowedValues({"mysql", "sqlite"})
        String database;

        DatabaseConfig() {
            this("sqlite");
        }

        DatabaseConfig(String database) {
            this.database = database;
        }
    }

    static class NullableDatabaseConfig {
        @AllowedValues({"mysql", "sqlite"})
        String database;
    }

    static class NotNullConfig {
        @NotNull
        String value;
    }

    static class NotBlankConfig {
        @NotBlank
        String value;

        NotBlankConfig() {
            this("profile");
        }

        NotBlankConfig(String value) {
            this.value = value;
        }
    }

    private List<String> blankViolationIds(String value) {
        return validatorFor(NotBlankConfig.class).validate(new NotBlankConfig(value)).stream().map(Violation::id).toList();
    }
}
