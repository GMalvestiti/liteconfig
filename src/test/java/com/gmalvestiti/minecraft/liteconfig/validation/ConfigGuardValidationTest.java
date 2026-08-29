package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigGuardValidationTest {

    @Test
    void testSkipsValidationWhenTheRootIsNotAnExtension() {
        ConfigGuard<TestFixtures.SimpleConfig> guard =
            new ConfigGuard<>(TestFixtures.model(TestFixtures.SimpleConfig.class));

        assertTrue(guard.violationsOf(new TestFixtures.SimpleConfig()).isEmpty());
        assertDoesNotThrow(() -> guard.validate(new TestFixtures.SimpleConfig()));
    }

    @Test
    void testSkipsValidationWhenTheRootKeepsTheDefault() {
        ConfigGuard<TestFixtures.HookFailureConfig> guard =
            new ConfigGuard<>(TestFixtures.model(TestFixtures.HookFailureConfig.class));

        assertTrue(guard.violationsOf(new TestFixtures.HookFailureConfig()).isEmpty());
    }

    @Test
    void testAcceptsValidCandidates() {
        ConfigGuard<ValidatingConfig> guard = guardOf();

        assertDoesNotThrow(() -> guard.validate(new ValidatingConfig()));
        assertTrue(guard.violationsOf(new ValidatingConfig()).isEmpty());
    }

    @Test
    void testReportsViolationsWithoutThrowing() {
        ConfigGuard<ValidatingConfig> guard = guardOf();
        ValidatingConfig config = new ValidatingConfig();
        config.violations = List.of(Violation.of("first", "broken"));

        List<Violation> violations = guard.violationsOf(config);

        assertEquals(1, violations.size());
        assertEquals("first", violations.get(0).id());
    }

    @Test
    void testRejectsInvalidCandidatesWithEveryViolationSummarised() {
        ConfigGuard<ValidatingConfig> guard = guardOf();
        ValidatingConfig config = new ValidatingConfig();
        config.violations = List.of(
            Violation.of("first", "broken"),
            Violation.of("second", "also broken")
        );

        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> guard.validate(config));

        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
        assertTrue(failure.getMessage().contains("ValidatingConfig"));
        assertTrue(failure.getMessage().contains("broken"));
        assertTrue(failure.getMessage().contains("also broken"));
        assertEquals(List.of("first", "second"), failure.violations().stream().map(Violation::id).toList());
    }

    @Test
    void testWrapsRuntimeValidationFailures() {
        ConfigGuard<TestFixtures.ThrowingValidatorConfig> guard =
            new ConfigGuard<>(TestFixtures.model(TestFixtures.ThrowingValidatorConfig.class));
        TestFixtures.ThrowingValidatorConfig config = new TestFixtures.ThrowingValidatorConfig();
        config.value = -1;

        LiteConfigException failure = assertThrows(
            LiteConfigException.class, () -> guard.violationsOf(config));

        assertEquals(ConfigError.VALIDATOR_FAILED, failure.error());
        assertTrue(failure.defect());
    }

    @Test
    void testRejectsInvalidViolationPayloads() {
        ConfigGuard<ValidatingConfig> guard = guardOf();

        ValidatingConfig nullEntry = new ValidatingConfig();
        nullEntry.violations = new ArrayList<>(Arrays.asList((Violation) null));
        assertEquals(
            ConfigError.VALIDATOR_PRODUCED_NULL_VIOLATION,
            assertThrows(LiteConfigException.class, () -> guard.violationsOf(nullEntry)).error());

        ValidatingConfig blankId = new ValidatingConfig();
        blankId.violations = List.of(new TestFixtures.UncheckedViolation("  ", "bad"));
        assertEquals(
            ConfigError.VALIDATOR_PRODUCED_BLANK_ID,
            assertThrows(LiteConfigException.class, () -> guard.violationsOf(blankId)).error());

        ValidatingConfig nullList = new ValidatingConfig();
        nullList.violations = null;
        assertTrue(guard.violationsOf(nullList).isEmpty());
    }

    @Test
    void testReturnsNoViolationsWhenTheExtensionOverridesNothing() {
        ConfigGuard<TestFixtures.BareExtensionConfig> guard =
            new ConfigGuard<>(TestFixtures.model(TestFixtures.BareExtensionConfig.class));

        assertTrue(guard.violationsOf(new TestFixtures.BareExtensionConfig()).isEmpty());
        assertDoesNotThrow(() -> guard.validate(new TestFixtures.BareExtensionConfig()));
    }

    private static ConfigGuard<ValidatingConfig> guardOf() {
        return new ConfigGuard<>(TestFixtures.model(ValidatingConfig.class));
    }

    public static class ValidatingConfig implements ConfigExtension {
        public List<Violation> violations = List.of();

        @Override
        public void validate(List<Violation> sink) {
            if (violations != null) {
                sink.addAll(violations);
            }
        }
    }
}
