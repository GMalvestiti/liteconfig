package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigMetadataSyncTest {

    @Test
    void testSendsNothingUnlessSomethingAsks() {
        assertTrue(metadataOf(TestFixtures.SimpleConfig.class).synced().isEmpty());
        assertTrue(metadataOf(TestFixtures.QuietConfig.class).synced().isEmpty());
        assertFalse(metadataOf(TestFixtures.SyncedConfig.class).synced().isEmpty());
    }

    @Test
    void testSendsTheValuePropertiesInDeclarationOrder() {
        assertEquals(
            List.of("maxTeamSize", "section.strict", "strictMode"),
            paths(metadataOf(TestFixtures.SyncedConfig.class).synced()));
    }

    @Test
    void testSendsOnlyTheFieldsThatAskWhenTheConfigDoesNot() {
        assertEquals(
            List.of("maxTeamSize", "section.strict"),
            paths(metadataOf(TestFixtures.OptedInFieldConfig.class).synced()));
    }

    @Test
    void testAnIncludedObjectTakesItsChildrenWithIt() {
        assertTrue(metadataOf(TestFixtures.OptedInFieldConfig.class)
            .property("section.strict")
            .orElseThrow()
            .sync());
    }

    @Test
    void testMarksTheFieldsThatStayHomeAsUnsynced() {
        ConfigMetadata metadata = metadataOf(TestFixtures.OptedInFieldConfig.class);

        assertTrue(metadata.property("maxTeamSize").orElseThrow().sync());
        assertFalse(metadata.property("auditLogPath").orElseThrow().sync());
    }

    @Test
    void testAFieldWithoutSyncFollowsItsConfig() {
        assertTrue(metadataOf(TestFixtures.SyncedConfig.class).property("strictMode").orElseThrow().sync());
    }

    @Test
    void inheritsThroughUnannotatedAndCommentOnlyFieldsAndHonorsDisabledSubtrees() {
        ConfigMetadata metadata = metadataOf(InheritedModes.class);

        assertEquals(
            List.of("commented", "enabled.commented", "enabled.inherited", "enabled.selected",
                "enabled.unannotated", "inherited", "unannotated"),
            paths(metadata.synced()));
        assertFalse(metadata.property("disabled").orElseThrow().sync());
        assertFalse(metadata.property("disabled.selected").orElseThrow().sync());
        assertFalse(metadata.property("enabled.local").orElseThrow().sync());
        assertEquals(SyncMode.INHERIT, DeclaredConfig.of(InheritedModes.class).property("commented").sync());
        assertEquals(SyncMode.INHERIT, DeclaredConfig.of(InheritedModes.class).property("unannotated").sync());
    }

    @Test
    void inheritedModeStaysLocalWithoutAnEnabledParentButDescendantsCanOptIn() {
        assertEquals(List.of("section.selected"), paths(metadataOf(LocalModes.class).synced()));
    }

    @Test
    void testRejectsInvalidPersistedNamesBeforePublishingSyncMetadata() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> metadataOf(InvalidSyncedEntryConfig.class));

        assertEquals(ConfigError.INVALID_ENTRY_NAME, failure.error());
    }

    private static List<String> paths(List<ConfigProperty> properties) {
        return properties.stream().map(ConfigProperty::path).toList();
    }

    private static ConfigMetadata metadataOf(Class<?> type) {
        return ConfigMetadataFactory.create(type, TestFixtures.SCOPE, ConfigFieldPlan.of(type));
    }

    @Config(name = "invalid-synced-entry")
    static class InvalidSyncedEntryConfig {
        @Entry(name = "section.value", sync = SyncMode.ENABLED)
        int value;
    }

    @Config(name = "inherited-modes", sync = SyncMode.ENABLED)
    static class InheritedModes {
        @Entry(comment = "A comment must not disable synchronization.")
        int commented;
        @Entry(sync = SyncMode.INHERIT)
        int inherited;
        int unannotated;
        @Entry(sync = SyncMode.DISABLED)
        ModeSection disabled = new ModeSection();
        @Entry(sync = SyncMode.ENABLED)
        ModeSection enabled = new ModeSection();
    }

    @Config(name = "local-modes")
    static class LocalModes {
        ModeSection section = new ModeSection();
    }

    static class ModeSection {
        @Entry(comment = "Inherits the parent section.")
        int commented;
        @Entry(sync = SyncMode.INHERIT)
        int inherited;
        int unannotated;
        @Entry(sync = SyncMode.DISABLED)
        int local;
        @Entry(sync = SyncMode.ENABLED)
        int selected;
    }
}
