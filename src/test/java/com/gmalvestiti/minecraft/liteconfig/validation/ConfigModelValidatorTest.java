package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigModelValidatorTest {

    private static <T> void validate(Class<T> type) {
        ConfigModelValidator.validate(TestFixtures.model(type));
    }

    @Test
    void testAcceptsSingleConfigRoots() {
        assertDoesNotThrow(() -> validate(TestFixtures.SimpleConfig.class));
    }

    @Test
    void testRejectsMissingMarkers() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> validate(UnannotatedConfig.class)
        );
        assertEquals(ConfigError.MISSING_CONFIG_MARKER, failure.error());
    }

    @Test
    void testRejectsConfigReferencingAnotherConfig() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> validate(TestFixtures.ParentWithConfigRef.class)
        );
        assertEquals(ConfigError.CONFIG_REFERENCE_FORBIDDEN, failure.error());
    }

    @Test
    void testRejectsAFieldClaimingTheReservedVersionKey() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> validate(TestFixtures.ReservedKeyConfig.class)
        );

        assertEquals(ConfigError.RESERVED_VERSION_KEY, failure.error());
    }

    @Test
    void testRejectsARenamedFieldClaimingTheReservedVersionKey() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> validate(TestFixtures.RenamedReservedKeyConfig.class)
        );

        assertEquals(ConfigError.RESERVED_VERSION_KEY, failure.error());
    }

    static class UnannotatedConfig {}
}
