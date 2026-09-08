package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.AllowedValues;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Length;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Pattern;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void testListsTheConstantsOfAnEnumField() {
        ConfigProperty property = propertyOf(TestFixtures.ConstrainedConfig.class, "mode");

        assertEquals(List.of("LOW", "MEDIUM", "HIGH"), property.constraints().allowedValues());
    }

    @Test
    void testListsTheAllowedValuesOfAStringField() {
        ConfigProperty property = propertyOf(DatabaseConfig.class, "database");

        assertEquals(List.of("mysql", "sqlite"), property.constraints().allowedValues());
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

        assertEquals(ConfigError.INVALID_CONSTRAINT, typeFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, emptyFailure.error());
        assertEquals(ConfigError.INVALID_CONSTRAINT, duplicateFailure.error());
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

    @Config(name = "database")
    static class DatabaseConfig {
        @AllowedValues({"mysql", "sqlite"})
        String database = "sqlite";
    }

    @Config(name = "nan-range")
    static class NaNRangeConfig {
        @Range(min = Double.NaN)
        double value;
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
