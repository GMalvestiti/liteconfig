package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
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
        @Entry(name = "section.value", sync = true)
        int value;
    }
}
