package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.AllowedValues;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Pattern;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static com.gmalvestiti.minecraft.liteconfig.api.EntryNormalization.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigNormalizationTest {

    @Test
    void normalizesOwnedCandidatesAndChoicesBeforeValidation() {
        ConfigModel<Choices> model = TestFixtures.model(Choices.class);
        ConfigGuard<Choices> guard = new ConfigGuard<>(model, null);
        Choices candidate = new Choices();

        guard.normalize(candidate);

        assertEquals("sqlite", candidate.database);
        assertEquals("SAFE", candidate.nested.mode);
        assertEquals("CuStOm", candidate.preserved);
        assertEquals("Unchanged", candidate.free);
        assertEquals(" padded ", candidate.padded);
        assertDoesNotThrow(() -> guard.validate(candidate));
        ConfigConstraints database = model.metadata().flatten()
            .filter(property -> property.path().equals("database")).findFirst().orElseThrow().constraints();
        assertEquals(LOWER_CASE, database.normalization());
        assertEquals(List.of("mysql", "sqlite"), database.allowedValues());
        assertThrows(UnsupportedOperationException.class, () -> database.allowedValues().add("other"));
        ConfigConstraints mode = model.metadata().flatten()
            .filter(property -> property.path().equals("nested.mode")).findFirst().orElseThrow().constraints();
        assertEquals(UPPER_CASE, mode.normalization());
        assertEquals(List.of("FAST", "SAFE"), mode.allowedValues());
        assertEquals(NONE, model.metadata().flatten()
            .filter(property -> property.path().equals("free")).findFirst().orElseThrow()
            .constraints().normalization());
    }

    @Test
    void trimsBeforeCaseNormalizationWhilePreservingExplicitOptOuts() {
        ConfigModel<TrimChoices> model = TestFixtures.model(TrimChoices.class);
        ConfigGuard<TrimChoices> guard = new ConfigGuard<>(model, null);
        TrimChoices candidate = new TrimChoices();

        guard.normalize(candidate);
        guard.validate(candidate);

        assertEquals("mysql", candidate.database);
        assertEquals("MiXeD", candidate.preservedCase);
        assertEquals(" PADDED ", candidate.whitespace);
        assertEquals(" CaSe ", candidate.verbatim);
        assertEquals("", candidate.empty);
        assertEquals("  Unchanged ", candidate.free);
        assertEquals(List.of("mysql", "sqlite"),
            model.metadata().property("database").orElseThrow().constraints().allowedValues());
        assertEquals("mysql", model.metadata().property("database").orElseThrow().defaultValue());
        assertEquals("MiXeD", model.metadata().property("preservedCase").orElseThrow().defaultValue());
        assertEquals(" PADDED ", model.metadata().property("whitespace").orElseThrow().defaultValue());
        assertEquals(" CaSe ", model.metadata().property("verbatim").orElseThrow().defaultValue());
        assertTrue(model.metadata().property("database").orElseThrow().constraints().trim());
        assertFalse(model.metadata().property("verbatim").orElseThrow().constraints().trim());
        assertFalse(model.metadata().property("free").orElseThrow().constraints().trim());
        assertTrue(model.metadata().property("free").orElseThrow().constraints().isEmpty());
    }

    @Test
    void noneStillUsesCaseInsensitiveMembershipAndValidationNeverMutates() {
        ConfigGuard<Choices> guard = new ConfigGuard<>(TestFixtures.model(Choices.class), null);
        Choices candidate = new Choices();
        candidate.database = "sqlite";
        candidate.nested.mode = "SAFE";
        guard.validate(candidate);
        assertEquals("CuStOm", candidate.preserved);

        candidate.preserved = "unknown";
        assertThrows(LiteConfigException.class, () -> guard.validate(candidate));
        assertEquals("unknown", candidate.preserved);
    }

    @Test
    void normalizationIsLocaleIndependentAndNullSafe() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("sqlite", LOWER_CASE.apply("SQLITE", false));
            assertEquals("SQLITE", UPPER_CASE.apply("sqlite", false));
            assertEquals("MiXeD", NONE.apply("MiXeD", false));
            assertNull(LOWER_CASE.apply(null, false));
            assertEquals(" I ", UPPER_CASE.apply(" i ", false));
            assertEquals("I", UPPER_CASE.apply(" i ", true));
            assertEquals("MiXeD", NONE.apply(" \tMiXeD\r\n", true));
            assertEquals(" MiXeD ", NONE.apply(" MiXeD ", false));
            assertNull(NONE.apply(null, true));
            ConfigGuard<Choices> guard = new ConfigGuard<>(TestFixtures.model(Choices.class), null);
            Choices candidate = new Choices();
            candidate.database = null;
            guard.normalize(candidate);
            assertNull(candidate.database);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void normalizesBeforeComparingRestartValues() {
        ConfigGuard<Choices> guard = new ConfigGuard<>(TestFixtures.model(Choices.class), null);
        Choices current = new Choices();
        guard.normalize(current);
        Choices candidate = new Choices();
        candidate.database = " SQLITE ";
        candidate.nested.mode = " safe ";
        guard.normalize(candidate);
        assertDoesNotThrow(() -> guard.enforceRestart(current, candidate));
        candidate.nested.mode = " fast ";
        guard.normalize(candidate);
        assertThrows(LiteConfigException.class, () -> guard.enforceRestart(current, candidate));
    }

    @Test
    void publishesNormalizedDefaultsAndUpdatesWithoutChangingOlderSnapshots(@TempDir Path directory)
        throws IOException {
        ConfigHolder<Choices> holder = holder(directory);
        Choices initial = holder.data();
        assertEquals("sqlite", initial.database);
        assertEquals("SAFE", initial.nested.mode);
        assertEquals("CuStOm", initial.preserved);
        assertTrue(holder.update(candidate -> candidate.database = " MYSQL ").accepted());
        assertEquals("mysql", holder.data().database);
        assertEquals("sqlite", initial.database);
        assertTrue(holder.update(candidate -> candidate.nested.mode = " safe ").accepted());
        assertTrue(holder.updateAndSave(candidate -> candidate.database = " SQLITE ").accepted());
        assertEquals("sqlite", holder.data().database);
        assertTrue(Files.readString(directory.resolve("mod").resolve("normalization.json5")).contains("sqlite"));
        holder.close();
    }

    @Test
    void normalizesLoadedRootAndNestedValues(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve("mod"));
        Files.writeString(directory.resolve("mod").resolve("normalization.json5"),
            "{\"database\":\" MYSQL \",\"nested\":{\"mode\":\" fast \"},\"preserved\":\" cUsToM \"}");
        ConfigHolder<Choices> holder = holder(directory);
        assertEquals("mysql", holder.data().database);
        assertEquals("FAST", holder.data().nested.mode);
        assertEquals("cUsToM", holder.data().preserved);
        holder.close();
    }

    @Test
    void explicitLoadNormalizesValuesAndFallbackReusesNormalizedDefaults(@TempDir Path directory)
        throws IOException {
        try (ConfigHolder<Choices> holder = holder(directory)) {
            Path file = directory.resolve("mod").resolve("normalization.json5");
            Files.writeString(file, "{\"database\":\" MYSQL \",\"nested\":{\"mode\":\" fast \"}}");

            holder.load();

            assertEquals("mysql", holder.data().database);
            assertEquals("FAST", holder.data().nested.mode);
            Files.writeString(file, "{\"database\":\"postgres\"}");

            holder.load();

            assertEquals("sqlite", holder.data().database);
            assertEquals("SAFE", holder.data().nested.mode);
        }
    }

    private static ConfigHolder<Choices> holder(Path directory) {
        return LiteConfig.holder(Choices.class).modId("mod").baseDir(directory.toString()).create();
    }

    @Config(name = "normalization")
    static class Choices implements ConfigExtension {
        @AllowedValues({"MySQL", "SQLite"})
        @Pattern("[a-z]+")
        String database = "SQLite";
        Nested nested = new Nested();
        @AllowedValues(value = "Custom", normalize = NONE)
        String preserved = "CuStOm";
        @AllowedValues(value = " PADDED ", trim = false)
        String padded = " PADDED ";
        String free = "Unchanged";

        @Override
        public void validate(List<Violation> violations) {
            if (!database.equals(database.toLowerCase(Locale.ROOT))) {
                violations.add(Violation.of("normalized.database", "database must already be normalized"));
            }
        }
    }

    static class Nested {
        @Entry(restart = true)
        @AllowedValues(value = {"Fast", "Safe"}, normalize = UPPER_CASE)
        @Pattern("[A-Z]+")
        String mode = "Safe";
    }

    @Config(name = "trim-choices")
    static class TrimChoices {
        @AllowedValues({" \tMySQL\r", " SQLite "})
        @Pattern("[a-z]+")
        String database = " MYSQL ";
        @AllowedValues(value = " Mixed ", normalize = NONE)
        String preservedCase = " \tMiXeD\r\n";
        @AllowedValues(value = " PADDED ", normalize = UPPER_CASE, trim = false)
        String whitespace = " padded ";
        @AllowedValues(value = " Case ", normalize = NONE, trim = false)
        String verbatim = " CaSe ";
        @AllowedValues(value = {"", "x"}, normalize = NONE)
        String empty = " \t ";
        String free = "  Unchanged ";
    }
}
