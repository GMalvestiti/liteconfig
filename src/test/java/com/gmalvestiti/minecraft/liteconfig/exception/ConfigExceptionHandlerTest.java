package com.gmalvestiti.minecraft.liteconfig.exception;

import com.gmalvestiti.minecraft.liteconfig.shared.ConfigOperation;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ConfigExceptionHandlerTest {

    private final List<Class<?>> backedUp = new ArrayList<>();

    private Consumer<Class<?>> backupCorrupted = backedUp::add;

    private static final ConfigScope FACTORY = new ConfigScope("mod");

    private final ConfigScope scope = spy(new ConfigScope("mod"));

    @Test
    void testReturnsValueWhenNothingFails() {
        ConfigExceptionHandler handler = handler(FailurePolicy.STRICT, FailurePolicy.STRICT, FailurePolicy.STRICT);

        ConfigOutcome<String> read = handler.onRead(String.class, () -> "ok");
        ConfigOutcome<String> update = handler.onUpdate(() -> "ok");

        assertTrue(read.completed());
        assertEquals("ok", read.value().orElseThrow());
        assertTrue(read.violations().isEmpty());
        assertTrue(update.completed());
        assertEquals("ok", update.value().orElseThrow());
        assertTrue(handler.onWrite(() -> {
        }).completed());

        assertTrue(backedUp.isEmpty());
        verifyNoInteractions(scope);
    }

    @Test
    void testCompletesWithoutAValueWhenStorageHasNothingToRead() {
        ConfigExceptionHandler handler = handler(FailurePolicy.FALLBACK, FailurePolicy.STRICT, FailurePolicy.STRICT);

        ConfigOutcome<String> outcome = handler.onRead(String.class, () -> null);

        assertTrue(outcome.completed(), "an absent file is not a degraded read");
        assertTrue(outcome.value().isEmpty());
        assertEquals("defaults", outcome.valueOr(() -> "defaults"));
        verifyNoInteractions(scope);
    }

    @Test
    void testStrictReadRethrowsWithoutBackup() {
        ConfigExceptionHandler handler = handler(FailurePolicy.STRICT, FailurePolicy.STRICT, FailurePolicy.STRICT);

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> handler.onRead(String.class, () -> {
                throw FACTORY.exception(ConfigError.MALFORMED_CONFIG_DATA, "simple.json");
            }));

        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, failure.error());
        assertTrue(backedUp.isEmpty(), "a rethrown read must not back anything up");
    }

    @Test
    void testFallbackReadDegradesAndBacksUpRecoverableData() {
        ConfigExceptionHandler handler = handler(FailurePolicy.FALLBACK, FailurePolicy.STRICT, FailurePolicy.STRICT);

        ConfigOutcome<String> malformed = handler.onRead(String.class, () -> {
            throw FACTORY.exception(ConfigError.MALFORMED_CONFIG_DATA, "simple.json");
        });
        assertTrue(malformed.degraded());
        assertTrue(malformed.value().isEmpty());
        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, malformed.failure().orElseThrow().error());
        assertEquals(List.of(String.class), backedUp);

        assertTrue(handler.onRead(Long.class, () -> {
            throw FACTORY.exception(ConfigError.VALIDATION_FAILED, "simple", "value");
        }).degraded());
        assertEquals(List.of(String.class, Long.class), backedUp);

        assertTrue(handler.onRead(Integer.class, () -> {
            throw FACTORY.exception(ConfigError.IO_LOAD_FAILURE, "simple.json");
        }).degraded());
        assertEquals(List.of(String.class, Long.class), backedUp, "an IO failure is not recoverable data");

        var warnings = forClass(String.class);
        verify(scope, times(3)).logWarning(warnings.capture());
        assertEquals(List.of(
            "Malformed config data in simple.json; restoring defaults",
            "Validation failed for simple: value; restoring defaults",
            "Failed to load config from simple.json; restoring defaults"
        ), warnings.getAllValues());
    }

    @Test
    void testReportsBackupFailureWithoutMaskingTheOriginalOne() {
        backupCorrupted = type -> {
            throw new IllegalStateException("cannot move file");
        };
        ConfigExceptionHandler handler = handler(FailurePolicy.FALLBACK, FailurePolicy.STRICT, FailurePolicy.STRICT);

        LiteConfigException backupFailure = assertThrows(LiteConfigException.class, () ->
            handler.onRead(String.class, () -> {
                throw FACTORY.exception(ConfigError.MALFORMED_CONFIG_DATA, "simple.json");
            }));

        var message = forClass(String.class);
        var cause = forClass(IllegalStateException.class);
        verify(scope).logError(message.capture(), cause.capture());
        assertEquals("Malformed config data in simple.json; failed to back up the recoverable file", message.getValue());
        assertEquals("cannot move file", cause.getValue().getMessage());
        assertEquals(ConfigError.UNEXPECTED_FAILURE, backupFailure.error());
        assertEquals(ConfigError.MALFORMED_CONFIG_DATA,
            ((LiteConfigException) backupFailure.getSuppressed()[0]).error());
    }

    @Test
    void testStrictWriteRethrowsAndFallbackWriteReportsTheDegradedSave() {
        ConfigExceptionHandler strict = handler(FailurePolicy.STRICT, FailurePolicy.STRICT, FailurePolicy.STRICT);
        assertThrows(LiteConfigException.class, () -> strict.onWrite(() -> {
            throw FACTORY.exception(ConfigError.IO_SAVE_FAILURE, "simple.json");
        }));

        ConfigExceptionHandler fallback = handler(FailurePolicy.STRICT, FailurePolicy.FALLBACK, FailurePolicy.STRICT);
        ConfigOutcome<Void> outcome = fallback.onWrite(() -> {
            throw FACTORY.exception(ConfigError.IO_SAVE_FAILURE, "simple.json");
        });

        assertTrue(outcome.degraded(), "a swallowed save must stay observable to the caller");
        assertEquals(ConfigError.IO_SAVE_FAILURE, outcome.failure().orElseThrow().error());

        var message = forClass(String.class);
        var failure = forClass(LiteConfigException.class);
        verify(scope).logError(message.capture(), failure.capture());
        assertEquals("Failed to save config to simple.json; keeping in-memory state", message.getValue());
        assertEquals(ConfigError.IO_SAVE_FAILURE, failure.getValue().error());
    }

    @Test
    void testStrictUpdateRethrowsAndFallbackUpdateRejectsCandidate() {
        ConfigExceptionHandler strict = handler(FailurePolicy.STRICT, FailurePolicy.STRICT, FailurePolicy.STRICT);
        assertThrows(LiteConfigException.class, () -> strict.onUpdate(() -> {
            throw FACTORY.exception(ConfigError.VALIDATION_FAILED, "simple", "value");
        }));

        ConfigExceptionHandler fallback = handler(FailurePolicy.STRICT, FailurePolicy.STRICT, FailurePolicy.FALLBACK);
        assertTrue(fallback.onUpdate(() -> {
            throw FACTORY.exception(ConfigError.VALIDATION_FAILED, "simple", "value");
        }).degraded());

        verify(scope).logWarning("Validation failed for simple: value");
    }

    @Test
    void testCarriesTheViolationsOnlyWhenFallbackDiscardsTheCandidate() {
        List<Violation> violations = List.of(Violation.of("nonNegative", "broken"));

        ConfigExceptionHandler strict = handler(FailurePolicy.STRICT, FailurePolicy.STRICT, FailurePolicy.STRICT);
        assertTrue(strict.onUpdate(() -> "ok").violations().isEmpty(), "success carries no violations");
        assertThrows(LiteConfigException.class, () -> strict.onUpdate(() -> {
            throw FACTORY.exception(ConfigError.VALIDATION_FAILED, violations, "simple", "value");
        }));

        ConfigExceptionHandler fallback = handler(FailurePolicy.STRICT, FailurePolicy.STRICT, FailurePolicy.FALLBACK);
        ConfigOutcome<String> outcome = fallback.onUpdate(() -> {
            throw FACTORY.exception(ConfigError.VALIDATION_FAILED, violations, "simple", "value");
        });

        assertTrue(outcome.degraded());
        assertEquals(List.of("nonNegative"), outcome.violations().stream().map(Violation::id).toList());
    }

    @Test
    void testTranslatesUnexpectedRuntimeFailuresAndAlwaysPropagatesThem() {
        ConfigExceptionHandler handler = handler(FailurePolicy.FALLBACK, FailurePolicy.FALLBACK, FailurePolicy.FALLBACK);

        LiteConfigException readFailure = assertThrows(LiteConfigException.class,
            () -> handler.onRead(String.class, () -> {
                throw new IllegalStateException("boom");
            }));
        assertEquals(ConfigError.UNEXPECTED_FAILURE, readFailure.error());

        assertThrows(LiteConfigException.class, () -> handler.onWrite(() -> {
            throw new IllegalStateException("boom");
        }));
        assertThrows(LiteConfigException.class, () -> handler.onUpdate(() -> {
            throw new IllegalStateException("boom");
        }));

        verify(scope, times(3)).logError(contains("Unexpected failure"), any(IllegalStateException.class));
    }

    @Test
    void testRejectsNullOperationArgumentsBeforeRunningOrLogging() {
        ConfigExceptionHandler handler =
            handler(FailurePolicy.FALLBACK, FailurePolicy.FALLBACK, FailurePolicy.FALLBACK);

        assertThrows(NullPointerException.class, () -> handler.onRead(null, () -> "value"));
        assertThrows(NullPointerException.class, () -> handler.onRead(String.class, null));
        assertThrows(NullPointerException.class, () -> handler.onWrite(null));
        assertThrows(NullPointerException.class, () -> handler.onUpdate(null));
        assertThrows(NullPointerException.class, () -> handler.reject(null, unsupported()));
        assertThrows(NullPointerException.class, () -> handler.reject(ConfigOperation.READ, null));

        verifyNoInteractions(scope);
    }

    @Test
    void testRejectRoutesEachOperationToItsOwnPolicy() {
        ConfigExceptionHandler writeStrict =
            handler(FailurePolicy.FALLBACK, FailurePolicy.STRICT, FailurePolicy.FALLBACK);

        assertEquals(
            ConfigError.HOLDER_OPERATION_UNSUPPORTED,
            assertThrows(LiteConfigException.class, () -> writeStrict.reject(ConfigOperation.SAVE, unsupported())).error()
        );

        assertTrue(writeStrict.reject(ConfigOperation.LOAD, unsupported()).degraded());
        assertTrue(writeStrict.reject(ConfigOperation.UPDATE, unsupported()).degraded());

        verify(scope, times(2)).logWarning(contains("skipping"));
    }

    @Test
    void testRejectNeverDegradesADefect() {
        ConfigExceptionHandler handler =
            handler(FailurePolicy.FALLBACK, FailurePolicy.FALLBACK, FailurePolicy.FALLBACK);

        LiteConfigException failure = assertThrows(LiteConfigException.class, () -> handler.reject(
            ConfigOperation.UPDATE, FACTORY.exception(ConfigError.VALIDATOR_FAILED, "buggy", "boom")));

        assertEquals(ConfigError.VALIDATOR_FAILED, failure.error());
        verify(scope).logError(any(), any(LiteConfigException.class));
    }

    private static LiteConfigException unsupported() {
        return FACTORY.exception(ConfigError.HOLDER_OPERATION_UNSUPPORTED, "immutable", "load");
    }

    private ConfigExceptionHandler handler(
        FailurePolicy readPolicy,
        FailurePolicy writePolicy,
        FailurePolicy updatePolicy
    ) {
        return TestFixtures.handler(type -> backupCorrupted.accept(type), readPolicy, writePolicy, updatePolicy, scope);
    }
}
