package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.EntryNormalization;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.AllowedValues;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Length;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.NotBlank;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.NotNull;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Pattern;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMetadataFactoryTest {

    @Test
    void testDescribesEveryPropertyOfTheRoot() {
        ConfigMetadata metadata = metadataOf(TestFixtures.ConstrainedConfig.class);

        assertEquals(TestFixtures.ConstrainedConfig.class, metadata.type());
        assertEquals(List.of("Constrained fixture."), metadata.comment());
        assertEquals(
            List.of("features", "hudScale", "mode", "profileName", "section", "spawnChance"),
            metadata.properties().stream().map(ConfigProperty::key).toList());
    }

    @Test
    void testPublishesTheDeclaredDefaults() {
        ConfigProperty property = propertyOf(TestFixtures.ConstrainedConfig.class, "hudScale");

        assertEquals(2, property.defaultValue());
        assertEquals(int.class, property.type());
    }

    @Test
    void testPublishesNestedDefaultsFromTheRootDefaultGraph() {
        ConfigProperty property = propertyOf(RootCustomizedDefaultConfig.class, "section.value");

        assertEquals(9, property.defaultValue());
    }

    @Test
    void testPublishesContainerDefaultsAsImmutableSnapshots() {
        @SuppressWarnings("unchecked")
        List<String> defaults = (List<String>) propertyOf(
            TestFixtures.ConstrainedConfig.class, "features").defaultValue();

        assertThrows(UnsupportedOperationException.class, () -> defaults.add("changed"));
        assertEquals(List.of("hud"), defaults);
    }

    @Test
    void testReturnsIndependentCopiesOfPrimitiveArrayDefaults() {
        ConfigProperty property = propertyOf(PrimitiveArrayDefault.class, "values");

        int[] first = (int[]) property.defaultValue();
        first[0] = 9;

        assertArrayEquals(new int[] {1, 2}, (int[]) property.defaultValue());
    }

    @Test
    void testPublishesTheDeclaredRange() {
        ConfigProperty property = propertyOf(TestFixtures.ConstrainedConfig.class, "hudScale");

        assertTrue(property.constraints().hasRange());
        assertEquals(1, property.constraints().min().getAsDouble());
        assertEquals(8, property.constraints().max().getAsDouble());
    }

    @Test
    void testLeavesAnUndeclaredBoundOpen() {
        ConfigProperty property = propertyOf(TestFixtures.ConstrainedConfig.class, "spawnChance");

        assertEquals(0, property.constraints().min().getAsDouble());
        assertTrue(property.constraints().max().isEmpty());
    }

    @Test
    void testPublishesThePatternAndLength() {
        ConfigProperty property = propertyOf(TestFixtures.ConstrainedConfig.class, "profileName");

        assertTrue(property.constraints().hasPattern());
        assertTrue(property.constraints().hasLength());
        assertEquals("[a-z]+", property.constraints().pattern().orElseThrow().pattern());
        assertEquals(12, property.constraints().maxLength().getAsInt());
    }

    @Test
    void testPublishesNullabilityConstraints() {
        ConfigProperty required = propertyOf(NullabilityConfig.class, "required");
        ConfigProperty text = propertyOf(NullabilityConfig.class, "text");

        assertTrue(required.constraints().hasNotNull());
        assertFalse(required.constraints().hasNotBlank());
        assertTrue(text.constraints().hasNotBlank());
    }

    @Test
    void testListsTheConstantsOfAnEnumField() {
        ConfigProperty property = propertyOf(TestFixtures.ConstrainedConfig.class, "mode");

        assertEquals(List.of("LOW", "MEDIUM", "HIGH"), property.constraints().allowedValues());
        assertFalse(property.constraints().trim());
    }

    @Test
    void testListsTheAllowedValuesOfAStringField() {
        ConfigProperty property = propertyOf(DatabaseConfig.class, "database");

        assertEquals(List.of("mysql", "sqlite"), property.constraints().allowedValues());
    }

    @Test
    void normalizesMetadataDefaultsAndChoicesUsingTheDeclaredMode() {
        ConfigMetadata metadata = metadataOf(NormalizedDefaults.class);
        assertEquals("sqlite", metadata.property("database").orElseThrow().defaultValue());
        assertEquals(List.of("mysql", "sqlite"),
            metadata.property("database").orElseThrow().constraints().allowedValues());
        assertEquals("SAFE", metadata.property("mode").orElseThrow().defaultValue());
        assertEquals(List.of("FAST", "SAFE"),
            metadata.property("mode").orElseThrow().constraints().allowedValues());
        assertEquals("CuStOm", metadata.property("preserved").orElseThrow().defaultValue());
    }

    @Test
    void testPublishesTheCommentAndTranslationKey() {
        ConfigProperty property = propertyOf(TestFixtures.ConstrainedConfig.class, "hudScale");

        assertEquals(List.of("Scale of the HUD."), property.comment());
        assertEquals("mod.config.hudScale", property.translationKey().orElseThrow());
    }

    @Test
    void testLeavesTheTranslationKeyEmptyWhenUnset() {
        assertTrue(propertyOf(TestFixtures.ConstrainedConfig.class, "mode").translationKey().isEmpty());
    }

    @Test
    void testDescribesNestedObjectsUnderTheirFileKeys() {
        ConfigProperty nested = propertyOf(TestFixtures.ConstrainedConfig.class, "section.max_depth");

        assertEquals("max_depth", nested.key());
        assertEquals("maxDepth", nested.fieldName());
        assertEquals(4, nested.constraints().max().getAsDouble());
    }

    @Test
    void testMarksRestartOnlyProperties() {
        ConfigMetadata metadata = metadataOf(TestFixtures.EntryConfig.class);

        assertTrue(metadata.property("worldPreset").orElseThrow().restart());
        assertFalse(metadata.property("hud_scale").orElseThrow().restart());
    }

    @Test
    void testFlattenWalksTheWholeTree() {
        ConfigMetadata metadata = metadataOf(TestFixtures.ConstrainedConfig.class);

        assertTrue(metadata.flatten().anyMatch(property -> property.path().equals("section")));
        assertTrue(metadata.flatten().anyMatch(property -> property.path().equals("section.max_depth")));
    }

    @Test
    void testPublishesTheDeclaredVersion() {
        assertEquals(3, metadataOf(TestFixtures.VersionedConfig.class).version());
    }

    @Test
    void testRejectsARangeThatNoValueCouldSatisfy() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(TestFixtures.ImpossibleRangeConfig.class));

        assertEquals(ConfigError.INVALID_CONSTRAINT, failure.error());
    }

    @Test
    void testRejectsAPatternThatDoesNotCompile() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(TestFixtures.BrokenPatternConfig.class));

        assertEquals(ConfigError.INVALID_CONSTRAINT, failure.error());
    }

    @Test
    void testRejectsPersistedNamesContainingThePathSeparator() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(DottedEntryConfig.class));

        assertEquals(ConfigError.INVALID_ENTRY_NAME, failure.error());
    }

    @Test
    void testRejectsDuplicatePersistedNames() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(DuplicateEntryConfig.class));

        assertEquals(ConfigError.DUPLICATE_ENTRY_NAME, failure.error());
    }

    @Test
    void testRejectsInheritedDuplicatePersistedNames() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InheritedDuplicateEntryConfig.class));

        assertEquals(ConfigError.DUPLICATE_ENTRY_NAME, failure.error());
    }

    @Test
    void testRejectsInaccessiblePersistedFieldsDuringMetadataCreation() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InaccessibleConfig.class));

        assertEquals(ConfigError.REFLECTION_ACCESS, failure.error());
    }

    @Test
    void testRejectsFinalPersistedFieldsDuringMetadataCreation() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(FinalFieldConfig.class));

        assertEquals(ConfigError.FINAL_CONFIG_FIELD, failure.error());
    }

    @Test
    void testTreatsClassesWithoutNoArgConstructorAsLeafValues() {
        ConfigProperty property = metadataOf(PlatformFieldConfig.class).properties().get(0);

        assertTrue(property.children().isEmpty());
    }

    @Test
    void testTreatsRecordsWithoutCodecAsLeafValues() {
        ConfigProperty property = metadataOf(RecordFieldConfig.class).properties().get(0);

        assertTrue(property.children().isEmpty());
    }

    @Test
    void testRejectsConstraintsAppliedToUnsupportedTypes() {
        LiteConfigException rangeFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InvalidRangeTypeConfig.class));
        LiteConfigException patternFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InvalidPatternTypeConfig.class));
        LiteConfigException lengthFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InvalidLengthTypeConfig.class));

        assertEquals(ConfigError.INVALID_CONSTRAINT, rangeFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, patternFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, lengthFailure.error());
    }

    @Test
    void testRejectsInvalidAllowedValuesDeclarations() {
        LiteConfigException typeFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InvalidAllowedValuesTypeConfig.class));
        LiteConfigException emptyFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(EmptyAllowedValuesConfig.class));
        LiteConfigException duplicateFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(DuplicateAllowedValuesConfig.class));
        LiteConfigException trimmedDuplicateFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(TrimmedDuplicateAllowedValuesConfig.class));
        LiteConfigException normalizedDuplicateFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(NormalizedDuplicateAllowedValuesConfig.class));

        assertEquals(ConfigError.INVALID_CONSTRAINT, typeFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, emptyFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, duplicateFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, trimmedDuplicateFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, normalizedDuplicateFailure.error());
    }

    @Test
    void allowsWhitespaceDistinctChoicesWhenTrimmingIsDisabled() {
        ConfigProperty property = propertyOf(WhitespaceDistinctConfig.class, "value");

        assertEquals(List.of("value", " value "), property.constraints().allowedValues());
        assertEquals(" value ", property.defaultValue());
        assertFalse(property.constraints().trim());
    }

    @Test
    void testRejectsNullabilityConstraintsAppliedToUnsupportedTypes() {
        LiteConfigException notNullFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InvalidNotNullTypeConfig.class));
        LiteConfigException notBlankFailure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InvalidNotBlankTypeConfig.class));

        assertEquals(ConfigError.INVALID_CONSTRAINT, notNullFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, notBlankFailure.error());
    }

    @Test
    void testRejectsNaNRangeBounds() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(NaNRangeConfig.class));

        assertEquals(ConfigError.INVALID_CONSTRAINT, failure.error());
    }

    private static ConfigMetadata metadataOf(Class<?> type) {
        return ConfigMetadataFactory.create(type, TestFixtures.SCOPE, ConfigFieldPlan.of(type));
    }

    private static ConfigProperty propertyOf(Class<?> type, String path) {
        return metadataOf(type).property(path).orElseThrow();
    }

    @Config(name = "dotted-entry")
    static class DottedEntryConfig {
        @Entry(name = "section.value")
        int value;
    }

    @Config(name = "final-field")
    static class FinalFieldConfig {
        final int value = 1;
    }

    @Config(name = "platform-field")
    static class PlatformFieldConfig {
        NoDefaultConstructor value = new NoDefaultConstructor(1);
    }

    @Config(name = "record-field")
    static class RecordFieldConfig {
        PlainRecord value = new PlainRecord(1);
    }

    record PlainRecord(int id) {
    }

    static final class NoDefaultConstructor {
        final int id;

        NoDefaultConstructor(int id) {
            this.id = id;
        }
    }

    @Config(name = "duplicate-entry")
    static class DuplicateEntryConfig {
        int value;

        @Entry(name = "value")
        int other;
    }

    static class DuplicateEntryParent {
        int value;
    }

    @Config(name = "inherited-duplicate-entry")
    static class InheritedDuplicateEntryConfig extends DuplicateEntryParent {
        @Entry(name = "value")
        int other;
    }

    @Config(name = "inaccessible")
    static class InaccessibleConfig extends ArrayList<String> {
        int value;
    }

    @Config(name = "invalid-range-type")
    static class InvalidRangeTypeConfig {
        @Range
        String value = "invalid";
    }

    @Config(name = "invalid-pattern-type")
    static class InvalidPatternTypeConfig {
        @Pattern(".*")
        int value;
    }

    @Config(name = "invalid-length-type")
    static class InvalidLengthTypeConfig {
        @Length
        int value;
    }

    @Config(name = "invalid-allowed-values-type")
    static class InvalidAllowedValuesTypeConfig {
        @AllowedValues("one")
        int value;
    }

    @Config(name = "empty-allowed-values")
    static class EmptyAllowedValuesConfig {
        @AllowedValues({})
        String value;
    }

    @Config(name = "duplicate-allowed-values")
    static class DuplicateAllowedValuesConfig {
        @AllowedValues({"mysql", "MYSQL"})
        String value;
    }

    @Config(name = "trimmed-duplicate-allowed-values")
    static class TrimmedDuplicateAllowedValuesConfig {
        @AllowedValues({" mysql ", "MYSQL"})
        String value;
    }

    @Config(name = "normalized-duplicate-allowed-values")
    static class NormalizedDuplicateAllowedValuesConfig {
        @AllowedValues(value = {"\u00df", "SS"}, normalize = EntryNormalization.UPPER_CASE)
        String value;
    }

    @Config(name = "whitespace-distinct-allowed-values")
    static class WhitespaceDistinctConfig {
        @AllowedValues(value = {"value", " value "}, trim = false)
        String value = " value ";
    }

    @Config(name = "database")
    static class DatabaseConfig {
        @AllowedValues({"mysql", "sqlite"})
        String database = "sqlite";
    }

    @Config(name = "normalized-defaults")
    static class NormalizedDefaults {
        @AllowedValues({"MySQL", "SQLite"})
        String database = "SQLite";
        @AllowedValues(value = {"Fast", "Safe"}, normalize = EntryNormalization.UPPER_CASE)
        String mode = "Safe";
        @AllowedValues(value = "Custom", normalize = EntryNormalization.NONE)
        String preserved = "CuStOm";
    }

    @Config(name = "primitive-array-default")
    static class PrimitiveArrayDefault {
        int[] values = {1, 2};
    }

    @Config(name = "nan-range")
    static class NaNRangeConfig {
        @Range(min = Double.NaN)
        double value;
    }

    @Config(name = "nullability")
    static class NullabilityConfig {
        @NotNull
        Object required = new Object();

        @NotBlank
        String text = "value";
    }

    @Config(name = "invalid-not-null-type")
    static class InvalidNotNullTypeConfig {
        @NotNull
        int value;
    }

    @Config(name = "invalid-not-blank-type")
    static class InvalidNotBlankTypeConfig {
        @NotBlank
        Object value;
    }

    @Config(name = "root-customized-default")
    static class RootCustomizedDefaultConfig {
        CustomizedDefaultSection section = new CustomizedDefaultSection();

        RootCustomizedDefaultConfig() {
            section.value = 9;
        }
    }

    static class CustomizedDefaultSection {
        int value = 1;
    }

}
