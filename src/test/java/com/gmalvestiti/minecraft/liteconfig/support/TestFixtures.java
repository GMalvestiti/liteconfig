package com.gmalvestiti.minecraft.liteconfig.support;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigFormat;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Ignore;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Length;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Migration;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Pattern;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.api.migration.ConfigData;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigExceptionHandler;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigStorage;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigPathResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class TestFixtures {

    public static final ConfigScope SCOPE = new ConfigScope("mod");

    private TestFixtures() {
    }

    /**
     * Fallback-everywhere handler for tests that only care about the happy path.
     */
    public static ConfigExceptionHandler fallbackHandler(ConfigStorage<?> storage) {
        return handler(ignored -> storage.backupCorrupted(), FailurePolicy.FALLBACK, FailurePolicy.STRICT,
            FailurePolicy.FALLBACK, SCOPE);
    }

    /**
     * The static facts of {@code type}, read against the standard test scope.
     */
    public static <T> ConfigModel<T> model(Class<T> type) {
        return ConfigModel.of(type, SCOPE);
    }

    public static ConfigFieldAccess fieldAccess() {
        return new ConfigFieldAccess(SCOPE);
    }

    /**
     * Storage rooted at {@code baseDirectory}, wired with the standard test scope.
     */
    public static <T> ConfigStorage<T> storage(Path baseDirectory, Class<T> configType) {
        return new ConfigStorage<>(
            configType,
            new ConfigPathResolver(baseDirectory, SCOPE, new ConfigFileOwnership()),
            SCOPE
        );
    }

    public static ConfigExceptionHandler handler(
        Consumer<Class<?>> backupCorrupted,
        FailurePolicy readPolicy,
        FailurePolicy writePolicy,
        FailurePolicy updatePolicy,
        ConfigScope scope
    ) {
        return new ConfigExceptionHandler(scope, readPolicy, writePolicy, updatePolicy, backupCorrupted);
    }

    @Config(name = "hook-failure")
    public static class HookFailureConfig implements ConfigExtension {
        public int value = 1;
        public boolean explicit;

        @Override
        public void afterLoad() {
            throw failure();
        }

        @Override
        public void beforeSave() {
            throw failure();
        }

        private RuntimeException failure() {
            return explicit
                ? SCOPE.exception(ConfigError.VALIDATION_FAILED, "keep")
                : new IllegalStateException("boom");
        }
    }

    @Config(name = "throwing-validator")
    public static class ThrowingValidatorConfig implements ConfigExtension {
        public int value = 1;

        @Override
        public void validate(List<Violation> violations) {
            if (value < 0) {
                throw new NullPointerException("boom");
            }
        }
    }

    @Config(name = "simple")
    public static class SimpleConfig {
        public int value = 1;
        public String text = "default";
    }

    /**
     * Exercises every {@link Entry} attribute at once: a renamed field, a commented field,
     * and a field that may only change between runs.
     */
    @Config(name = "entries", comment = "Settings for the entry fixture.")
    public static class EntryConfig {
        @Entry(name = "hud_scale", comment = "Scale of the HUD overlay.")
        public int hudScale = 2;

        @Entry(restart = true)
        public String worldPreset = "default";

        @Entry(name = "nested", comment = "Grouped options.")
        public EntrySection section = new EntrySection();
    }

    public static class EntrySection {
        @Entry(name = "max_depth")
        public int maxDepth = 4;

        @Entry(restart = true)
        public boolean experimental;
    }

    /**
     * Comment text that would break the file if it reached the codec unescaped.
     */
    @Config(name = "awkward-comments", comment = {"Two lines,", "so this renders as a block."})
    public static class AwkwardCommentConfig {
        @Entry(comment = {"Contains */ a block terminator", "and // a line marker"})
        public int value = 1;
    }

    @Config(
        name = "awkward-comments-toml",
        format = ConfigFormat.TOML,
        comment = {"Two lines,", "so this renders as a block."})
    public static class AwkwardCommentTomlConfig {
        @Entry(comment = {"Contains */ a block terminator", "and // a line marker"})
        public int value = 1;
    }

    @Config(name = "toml-fixture", format = ConfigFormat.TOML, comment = "A TOML-backed fixture.")
    public static class TomlConfig {
        @Entry(comment = "How loud, from 0 to 10.")
        public int volume = 5;

        @Entry(name = "display_name")
        public String displayName = "player";

        public List<String> tags = new ArrayList<>(List.of("a", "b"));

        public TomlSection section = new TomlSection();
    }

    public static class TomlSection {
        public double ratio = 0.5;
        public boolean enabled = true;
    }

    /**
     * Implements the extension interface but overrides nothing, so every hook is the default.
     */
    @Config(name = "bare-extension")
    public static class BareExtensionConfig implements ConfigExtension {
        public int value = 1;
    }

    @Config(name = "with-extension")
    public static class ConfigWithExtension implements ConfigExtension {
        public int value = 1;
        public boolean afterLoadCalled;
        public boolean beforeSaveCalled;

        @Override
        public void afterLoad() {
            afterLoadCalled = true;
        }

        @Override
        public void beforeSave() {
            beforeSaveCalled = true;
        }

        @Override
        public void validate(List<Violation> violations) {
            if (value < 0) {
                violations.add(Violation.of("nonNegative", "value must be >= 0"));
            }
        }
    }

    @Config(name = "strict-read-with-extension", readFailurePolicy = FailurePolicy.STRICT)
    public static class StrictReadConfigWithExtension extends ConfigWithExtension {}

    @Config(name = "strict-update-with-extension", updateFailurePolicy = FailurePolicy.STRICT)
    public static class StrictUpdateConfigWithExtension extends ConfigWithExtension {}

    @Config(
        name = "strict-with-extension",
        readFailurePolicy = FailurePolicy.STRICT,
        writeFailurePolicy = FailurePolicy.STRICT,
        updateFailurePolicy = FailurePolicy.STRICT
    )
    public static class StrictConfigWithExtension extends ConfigWithExtension {}

    @Config(name = "strict-entries", updateFailurePolicy = FailurePolicy.STRICT)
    public static class StrictEntryConfig extends EntryConfig {}

    @Config(name = "invalid-defaults")
    public static class InvalidDefaultsConfig implements ConfigExtension {
        public int value = -1;

        @Override
        public void validate(List<Violation> violations) {
            if (value < 0) {
                violations.add(Violation.of("nonNegative", "value must be >= 0"));
            }
        }
    }

    @Config(name = "child")
    public static class ChildConfig {
        public int nested = 7;
    }

    @Config(name = "parent")
    public static class ParentWithConfigRef {
        public ChildConfig child = new ChildConfig();
    }

    /**
     * Gson refuses to serialize {@link Class} values, which is the cheapest honest way to make a
     * write fail without stubbing out the storage layer.
     */
    @Config(name = "unserializable")
    public static class UnserializableConfig {
        public Class<?> marker = String.class;
    }

    public static class NoDefaultConstructorConfig {
        public NoDefaultConstructorConfig(String ignored) {
        }
    }

    public static class ThrowingConstructorConfig {
        public ThrowingConstructorConfig() {
            throw new IllegalStateException("boom");
        }
    }

    /**
     * Skips the checks {@link Violation#of} performs, so tests can hand the runner input it
     * would otherwise refuse to construct — a blank id, for instance.
     */
    public record UncheckedViolation(String id, String message) implements Violation {
    }

    /**
     * A field tagged {@link Ignore} must not appear in the file or be read back.
     */
    @Config(name = "ignored-field")
    public static class IgnoredFieldConfig {
        public int persisted = 5;

        @Ignore
        public int ignored = 99;
    }

    /** {@link Ignore} must suppress the field even when {@link Entry} also annotates it. */
    @Config(name = "ignored-with-entry")
    public static class IgnoredWithEntryConfig {
        public int persisted = 5;

        @Ignore
        @Entry(comment = "This comment must never reach the file.")
        public int ignored = 99;
    }

    public enum Mode {
        LOW,
        MEDIUM,
        HIGH
    }

    @Config(name = "constrained", comment = "Constrained fixture.")
    public static class ConstrainedConfig {
        @Entry(comment = "Scale of the HUD.", translationKey = "mod.config.hudScale")
        @Range(min = 1, max = 8)
        public int hudScale = 2;

        @Range(min = 0)
        public double spawnChance = 0.25;

        @Pattern("[a-z]+")
        @Length(max = 12)
        public String profileName = "default";

        @Length(min = 1, max = 3)
        public List<String> features = new ArrayList<>(List.of("hud"));

        public Mode mode = Mode.MEDIUM;

        public ConstrainedSection section = new ConstrainedSection();
    }

    public static class ConstrainedSection {
        @Entry(name = "max_depth")
        @Range(min = 1, max = 4)
        public int maxDepth = 2;
    }

    @Config(name = "impossible-range")
    public static class ImpossibleRangeConfig {
        @Range(min = 10, max = 1)
        public int value = 5;
    }

    @Config(name = "broken-pattern")
    public static class BrokenPatternConfig {
        @Pattern("[unclosed")
        public String value = "a";
    }

    @Config(name = "versioned", version = 3)
    public static class VersionedConfig {
        public VersionedHud hud = new VersionedHud();
        public String profile = "default";

        @Migration(from = 1)
        static void toVersion2(ConfigData data) {
            data.rename("hudScale", "hud.scale");
        }

        @Migration(from = 2)
        static void toVersion3(ConfigData data) {
            if (!data.has("profile")) {
                data.set("profile", "default");
            }
        }
    }

    public static class VersionedHud {
        public int scale = 2;
    }

    @Config(name = "versioned-toml", format = ConfigFormat.TOML, version = 2)
    public static class VersionedTomlConfig {
        public int scale = 2;

        @Migration(from = 1)
        static void toVersion2(ConfigData data) {
            data.set("scale", 2);
        }
    }

    @Config(name = "gapped", version = 3)
    public static class GappedMigrationConfig {
        public int scale = 2;

        @Migration(from = 1)
        static void toVersion2(ConfigData data) {
            data.set("scale", 3);
        }
    }

    @Config(name = "unreachable-migration", version = 2)
    public static class UnreachableMigrationConfig {
        @Migration(from = 1)
        static void toVersion2(ConfigData data) {
            data.set("value", 2);
        }

        @Migration(from = 2)
        static void toVersion3(ConfigData data) {
            data.set("value", 3);
        }
    }

    @Config(name = "throwing-migration", version = 2)
    public static class ThrowingMigrationConfig {
        public int scale = 2;

        @Migration(from = 1)
        static void toVersion2(ConfigData data) {
            throw new IllegalStateException("boom");
        }
    }

    @Config(name = "duplicate-migration", version = 2)
    public static class DuplicateMigrationConfig {
        public int scale = 2;

        @Migration(from = 1)
        static void first(ConfigData data) {
            data.set("scale", 1);
        }

        @Migration(from = 1)
        static void second(ConfigData data) {
            data.set("scale", 2);
        }
    }

    @Config(name = "instance-migration", version = 2)
    public static class InstanceMigrationConfig {
        public int scale = 2;

        @Migration(from = 1)
        void toVersion2(ConfigData data) {
            data.set("scale", 1);
        }
    }

    @Config(name = "reserved-key", version = 2)
    public static class ReservedKeyConfig {
        public int configVersion = 1;
    }

    @Config(name = "reserved-key-renamed", version = 2)
    public static class RenamedReservedKeyConfig {
        @Entry(name = "configVersion")
        public int revision = 1;
    }

    @Config(name = "synced", sync = true)
    public static class SyncedConfig {
        public int maxTeamSize = 4;

        public SyncedSection section = new SyncedSection();

        @Entry(comment = "Described, and carried along with the rest of the config.")
        public boolean strictMode = true;
    }

    public static class SyncedSection {
        public boolean strict = true;
    }

    /**
     * Sends nothing except the one value and the one section a client has to render.
     */
    @Config(name = "opt-in-field")
    public static class OptedInFieldConfig {
        public String auditLogPath = "logs/audit";

        @Entry(sync = true)
        public int maxTeamSize = 4;

        @Entry(sync = true)
        public SyncedSection section = new SyncedSection();
    }

    @Config(name = "quiet")
    public static class QuietConfig {
        public int value = 1;
    }

}
