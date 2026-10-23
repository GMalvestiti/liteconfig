# Lite Config

Lite Config is a JSON5/TOML configuration library for Minecraft mods on Fabric and NeoForge. Annotate any Java class with @Config and pass it to a builder to get a ConfigHolder that automatically manages file resolution, safe I/O, lifecycle events, sync, and more.

**Features:**
* **Files:** Read and write JSON5 or TOML, with defaults and recovery from damaged files.
* **Updates:** Validate changes and provide copies when you need to edit config data.
* **Flexible reads:** Read the current config from any thread.
* **Ownership:** Choose client/server settings or world-owned settings.
* **Opt-In Network Sync:** Automatically synchronizes marked configs or individual fields from server to client.
* **Restart settings:** CLIENT changes wait for game startup; WORLD changes apply when that world loads again; SERVER restart settings take effect after a game restart.
* **Custom Update API:** `update` and `updateAndSave` return an `UpdateResult` with acceptance status and validation violations.
* **Failure Policies:** Granular control over read, write, and update errors, from graceful fallbacks to strict exceptions.
* **Lifecycle Events and Hooks:** Synchronous load, save, and update events, plus config-level normalization and validation hooks.
* **Declared constraints:** Enforces numeric bounds, string patterns, and collection sizes, and more via annotations on load/update.
* **Runtime Metadata:** Access every field's path, type, default, comment, translation key, and constraints at runtime.
* **Version Migrations:** Uses stamped file revisions to upgrade older configs step-by-step without losing player data.
* **Persistence Control:** Fully customize file paths, field names, automated comments, and ignored fields.

**Outside Lite Config's Scope:**
* **Config Screen:** Lite Config is a data-layer solution and doesn't render config screens by itself, allowing you to pair it with any UI library or custom screens.

**Documentation:** [Wiki](https://github.com/GMalvestiti/liteconfig/wiki)

## Setup

Lite Config is published to Maven Central under `com.gmalvestiti.minecraft`, with one artifact per
loader:

| Loader | Artifact |
|---|---|
| Fabric | `liteconfig-fabric` |
| NeoForge | `liteconfig-neoforge` |

Version numbers include both the Lite Config version and the Minecraft target:

| Minecraft | Lite Config |
|---|---|
| `1.21`-`1.21.10` | `1.3.0-1.21` |
| `1.21.11` | `1.3.0-1.21.11` |
| `26.1`-`latest` | `1.3.0-26.1` |

The examples below use the `1.21` build.

<details>
<summary><b>Fabric</b></summary>

```groovy
repositories {
    mavenCentral()
}

dependencies {
    modImplementation 'com.gmalvestiti.minecraft:liteconfig-fabric:1.3.0-1.21'
}
```

Declare the dependency in `fabric.mod.json` so Fabric refuses to load the mod without Lite Config:

```json
{
  "depends": {
    "liteconfig": ">=1.3.0-1.21"
  }
}
```
</details>

<details>
<summary><b>NeoForge</b></summary>

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.gmalvestiti.minecraft:liteconfig-neoforge:1.3.0-1.21'
}
```

Declare the dependency in `META-INF/neoforge.mods.toml`:

```toml
[[dependencies.yourmodid]]
modId = "liteconfig"
type = "required"
versionRange = "[1.3.0,)"
ordering = "NONE"
side = "BOTH"
```
</details>

## Quickstart

Start with one plain config class. Give each persisted field a default value.

```java
@Config(name = "mymod", type = ConfigType.CLIENT) // JSON5, mod-id-scoped path by default
public final class MyModConfig {
    public boolean showHints = true;
    public int hudScale = 2;
}
```

To use TOML instead, add `format = ConfigFormat.TOML` to `@Config`.

Create one holder during mod initialization and keep it for the life of the mod:

```java
public final class MyMod implements ModInitializer {

    public static final ConfigHolder<MyModConfig> CONFIG = LiteConfig.holder(MyModConfig.class)
        .modId("mymod")
        .create();
}
```

or in the config class itself:

```java
@Config(name = "mymod", type = ConfigType.CLIENT)
public final class MyModConfig {
    public boolean showHints = true;
    public int hudScale = 2;

    public static final ConfigHolder<MyModConfig> HOLDER;
    
    static {
        HOLDER = LiteConfig.holder(MyModConfig.class)
            .modId("mymod")
            .create();
    }
}
```

`create()` validates the config model and loads active local settings, or writes defaults if the
file does not exist yet.

Holders can be created before the game starts. Keep constructors, migrations and custom state
cloners independent of Minecraft's runtime state.

Read the current published state with `data()`:

```java
if (MyMod.CONFIG.data().showHints) {
    // ...
}
```

Apply runtime changes with `update(...)` or `updateAndSave(...)`:

```java
MyMod.CONFIG.update(config -> config.hudScale = 3);        // memory only
MyMod.CONFIG.updateAndSave(config -> config.hudScale = 4); // memory + disk
```

Treat `data()` and its nested values as read-only. Use `copy()` when you need to edit a separate copy.

If a change is invalid, Lite Config rejects it and keeps the current state:

```java
UpdateResult result = MyMod.CONFIG.update(config -> config.hudScale = 99);
if (!result.accepted()) {
    result.violations().forEach(v -> LOGGER.warn("{}: {}", v.id(), v.message()));
}
```

The saved JSON5 file looks like this:

```json5
{
  "showHints": true,
  "hudScale": 4
}
```

## Full Configuration Example

```java
@Config(
    name = "mymodfile",
    path = "mymoddir1/mymoddir2",
    type = ConfigType.SERVER,
    modIdDirectory = true,
    readOnly = false,
    format = ConfigFormat.JSON,
    comment = "MyMod settings.",
    version = 3,
    stateCloner = MyModConfigCloner.class,
    readFailurePolicy = FailurePolicy.FALLBACK,
    writeFailurePolicy = FailurePolicy.STRICT,
    updateFailurePolicy = FailurePolicy.FALLBACK,
    restartSyncPolicy = RestartSyncPolicy.DISCONNECT
)
public final class MyModConfig implements ConfigExtension {

    @Entry(
        name = "hud_scale",
        comment = "Scale of the HUD, from 1 to 4.",
        translationKey = "mymod.config.hud_scale",
        sync = SyncMode.ENABLED,
        listener = "onHudScaleChanged"
    )
    @Range(min = 1, max = 4)
    public int hudScale = 2;

    @Entry(
        comment = "Rendering backend. Applied after the next restart.",
        restart = true,
        sync = SyncMode.ENABLED
    )
    public Renderer renderer = Renderer.DEFAULT;

    @AllowedValues({"mysql", "sqlite"})
    public String database = "sqlite";

    @NotBlank
    @Length(max = 16)
    @Pattern("[a-z0-9_]+")
    @Entry(comment = {"Profile used by server rules.", "Must be lowercase, alphanumeric, or underscore."})
    public String profileName = "default";

    @NotNull
    @Entry(comment = "Server-owned spawn range.", sync = SyncMode.ENABLED)
    public IntRange spawnRange = new IntRange(1, 12);

    public Display display = new Display();

    @Ignore
    public Map<String, String> runtimeCache = new HashMap<>();

    @Override
    public void afterLoad() {
        if (profileName != null) {
            profileName = profileName.strip().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public void beforeSave() {
        if (display != null && display.hiddenHints != null) {
            display.hiddenHints.sort(String::compareTo);
        }
    }

    @Override
    public void validate(List<Violation> violations) {
        if (spawnRange == null || spawnRange.minimum() > spawnRange.maximum()) {
            violations.add(Violation.of(
                "spawn-range.order",
                "spawnRange minimum must not exceed its maximum"
            ));
        }
    }

    private void onHudScaleChanged(Integer oldValue, Integer newValue, boolean fromSync) {
        System.out.printf("HUD scale: %d -> %d (from server: %s)%n",
            oldValue, newValue, fromSync);
    }

    @Migration(from = 1)
    static void toVersion2(ConfigData data) {
        data.rename("hudScale", "hud_scale");
    }

    @Migration(from = 2)
    static void toVersion3(ConfigData data) {
        if (!data.has("spawnRange")) {
            data.set("spawnRange.minimum", 1)
                .set("spawnRange.maximum", 12);
        }
    }

    public static final class Display {
        @Entry(comment = "Show contextual hints.")
        public boolean showHints = true;

        @Length(max = 32)
        public List<String> hiddenHints = new ArrayList<>();
    }

    public enum Renderer {
        DEFAULT,
        COMPATIBILITY
    }
}

// Custom type
public record IntRange(int minimum, int maximum) {
    public static final Codec<IntRange> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("minimum").forGetter(IntRange::minimum),
            Codec.INT.fieldOf("maximum").forGetter(IntRange::maximum)
        ).apply(instance, IntRange::new)
    );

    public static final StreamCodec<ByteBuf, IntRange> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IntRange::minimum,
            ByteBufCodecs.VAR_INT, IntRange::maximum,
            IntRange::new
        );
}

// Custom config state cloner
public final class MyModConfigCloner implements StateCloner<MyModConfig> {
    @Override
    public MyModConfig copy(MyModConfig source) {
        MyModConfig copy = new MyModConfig();
        copy.hudScale = source.hudScale;
        copy.renderer = source.renderer;
        copy.database = source.database;
        copy.profileName = source.profileName;
        copy.spawnRange = source.spawnRange;
        copy.display = copyDisplay(source.display);
        copy.runtimeCache = source.runtimeCache == null
            ? new HashMap<>()
            : new HashMap<>(source.runtimeCache);
        return copy;
    }

    private static MyModConfig.Display copyDisplay(MyModConfig.Display source) {
        if (source == null) {
            return null;
        }
        MyModConfig.Display copy = new MyModConfig.Display();
        copy.showHints = source.showHints;
        copy.hiddenHints = source.hiddenHints == null
            ? null
            : new ArrayList<>(source.hiddenHints);
        return copy;
    }
}
```

Register a custom `Codec` for file persistence and state copying, and a `StreamCodec` for
synchronization.

```java
public final class MyMod implements ModInitializer {

    public static ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class, codecs -> codecs
            .registerCodec(IntRange.class, IntRange.CODEC)
            .registerStreamCodec(IntRange.class, IntRange.STREAM_CODEC))
        .modId("mymod")
        .onUpdate(state -> System.out.println("HUD scale is now " + state.hudScale))
        .onSave(state -> System.out.println("Saved MyMod config"))
        .create();

    @Override
    public void onInitialize() {
        // Fast shared read. Treat the returned object as read-only.
        int currentScale = config.data().hudScale;

        // Stable deep copy owned by this caller.
        MyModConfig snapshot = config.copy();
        snapshot.display.hiddenHints.add("crafting");

        // Validated in-memory update.
        UpdateResult result = config.update(state -> {
            state.hudScale = 3;
            state.display.hiddenHints = new ArrayList<>(
                snapshot.display.hiddenHints
            );
        });
        if (!result.accepted()) {
            result.violations().forEach(violation ->
                System.err.println(violation.id() + ": " + violation.message()));
        }

        // Update and save.
        UpdateResult saved = config.updateAndSave(state ->
            state.spawnRange = new IntRange(2, 24)
        );
        System.out.println("Saved: " + saved.accepted());

        // Structural metadata for screens, commands, generated help, etc.
        config.metadata().flatten().forEach(property ->
            System.out.println(property.path() + " -> " + property.type().getSimpleName()));
    }
}
```

The first `create()` loads `config/mymod/mymoddir1/mymoddir2/mymodfile.json5`, applies migrations,
validates the result, and saves it. Files without `configVersion` start at version 1.

Only `hud_scale`, `renderer`, and `spawnRange` sync because they opt in with `SyncMode.ENABLED`.

Built-in values include primitives and wrappers, enums, arrays, typed `List`/`Set`/`Map` collections,
`UUID`, `BigInteger`, `BigDecimal`, `Duration`, `Instant`, `LocalDate`, `LocalTime`, and `LocalDateTime`.
