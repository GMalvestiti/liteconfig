package com.gmalvestiti.minecraft.liteconfig.exception;

import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigScopeTest {

    @Test
    void testRejectsMissingModId() {
        assertThrows(NullPointerException.class, () -> new ConfigScope(null));
    }

    @Test
    void testPrefixesFailuresWithTheModId() {
        LiteConfigException failure = new ConfigScope("mymod").exception(ConfigError.BUILD_REQUIRED_ARGUMENT, "modId");

        assertEquals(ConfigError.BUILD_REQUIRED_ARGUMENT, failure.error());
        assertTrue(failure.getMessage().startsWith("[mymod]"), failure.getMessage());
    }

    @Test
    void testKeepsTheCauseAttached() {
        RuntimeException cause = new RuntimeException("boom");
        LiteConfigException failure = new ConfigScope("mymod")
            .exception(ConfigError.UNEXPECTED_FAILURE, cause, "load", cause.getMessage());

        assertSame(cause, failure.getCause());
    }

    @Test
    void testFillsTheErrorTemplateWithTheGivenArguments() {
        LiteConfigException failure = new ConfigScope("mod")
            .exception(ConfigError.VALIDATOR_FAILED, "MyConfig", "boom");

        assertEquals("[mod] Validation for MyConfig threw an exception: boom", failure.getMessage());
        assertEquals(ConfigError.VALIDATOR_FAILED, failure.error());
        assertTrue(failure.violations().isEmpty());
    }

    @Test
    void testAttachesViolationsWithoutLosingTheFormattedMessage() {
        List<Violation> violations = List.of(Violation.of("rule", "broken"));

        LiteConfigException failure = new ConfigScope("mymod")
            .exception(ConfigError.VALIDATION_FAILED, violations, "MyConfig", "broken");

        assertEquals("[mymod] Validation failed for MyConfig: broken", failure.getMessage());
        assertEquals(List.of("rule"), failure.violations().stream().map(Violation::id).toList());
        assertNull(failure.getCause());
    }

    @Test
    void testComparesByModId() {
        assertEquals(new ConfigScope("mymod"), new ConfigScope("mymod"));
        assertEquals(new ConfigScope("mymod").hashCode(), new ConfigScope("mymod").hashCode());
        assertNotEquals(new ConfigScope("mymod"), new ConfigScope("other"));
    }

    @Test
    void testLogsWithoutThrowing() {
        ConfigScope scope = new ConfigScope("mymod");

        assertDoesNotThrow(() -> scope.logInfo("hello"));
        assertDoesNotThrow(() -> scope.logWarning("careful"));
        assertDoesNotThrow(() -> scope.logError("broken", new RuntimeException("boom")));
    }

    @Test
    void testFallsBackToAnUnknownIdBeforeTheModIdIsKnown() {
        assertTrue(ConfigScope.unknown().exception(ConfigError.BUILD_REQUIRED_ARGUMENT, "modId")
            .getMessage().startsWith("[unknown]"));
    }
}

