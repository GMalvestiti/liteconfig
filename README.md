# Lite Config

Lite Config is a JSON5/TOML configuration library for Minecraft mods on Fabric and NeoForge. Annotate any Java class with @Config and pass it to a builder to get a ConfigHolder that automatically manages file resolution, safe I/O, lifecycle events, sync, and more.

**Features:**
* **Data layer:** Handles JSON5/TOML formats, paths, default values, atomic writes, and automatic corruption recovery.
* **State Management:** Provides validated snapshots, deep copies, runtime updates, and custom state cloning.
* **Opt-In Network Sync:** Automatically synchronizes marked configs or individual fields from server to client.
* **Async and Read-only:** Offers synchronous, asynchronous, or read-only options to match your threading model.
* **Restart Guards:** Blocks runtime updates and defers synchronized changes for fields requiring a game restart.
* **Custom Update API:** `update` and `updateAndSave` return an `UpdateResult` with acceptance status and validation violations.
* **Failure Policies:** Granular control over read, write, and update errors, from graceful fallbacks to strict exceptions.
* **Lifecycle Hooks:** Event listeners for load, save, and update phases, plus config-level normalization and validation hooks.
* **Declared constraints:** Enforces numeric bounds, string patterns, and collection sizes, and more via annotations on load/update.
* **Runtime Metadata:** Access every field's path, type, default, comment, translation key, and constraints at runtime.
* **Version Migrations:** Uses stamped file revisions to upgrade older configs step-by-step without losing player data.
* **Persistence Control:** Fully customize file paths, field names, automated comments, and ignored fields.

**Outside Lite Config's Scope:**
* **Config Screen:** Lite Config is a data-layer solution and doesn't render config screens by itself, allowing you to pair it with any UI library or custom screens.

**Documentation:** [Wiki](https://github.com/GMalvestiti/liteconfig/wiki)

## Setup

Artifacts are published to Maven Central under the group `com.gmalvestiti.minecraft`, with one
artifact per loader:

| Loader   | Artifact              |
|----------|-----------------------|
| Fabric   | `liteconfig-fabric`   |
| NeoForge | `liteconfig-neoforge` |

Maven versions include the Lite Config version and the Minecraft build target:

| Minecraft        | Lite Config     |
|------------------|-----------------|
| `1.21`-`1.21.10` | `1.2.0-1.21`    |
| `1.21.11`        | `1.2.0-1.21.11` |
| `26.1`-`latest`  | `1.2.0-26.1`    |

The examples below target the `1.21` build.

<details>
<summary><b>Fabric</b></summary>

```groovy
repositories {
    mavenCentral()
}

dependencies {
    modImplementation 'com.gmalvestiti.minecraft:liteconfig-fabric:1.2.0-1.21'
}
```

Declare the dependency so the loader refuses to start without it:

```json
{
  "depends": {
    "liteconfig": ">=1.2.0-1.21"
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
    implementation 'com.gmalvestiti.minecraft:liteconfig-neoforge:1.2.0-1.21'
}
```

Declare the dependency in `META-INF/neoforge.mods.toml`:

```toml
[[dependencies.yourmodid]]
modId = "liteconfig"
type = "required"
versionRange = "[1.2.0,)"
ordering = "NONE"
side = "BOTH"
```
</details>

## Quickstart

The builder creates either a mutable or read-only holder. Every holder exposes synchronous methods
for calling-thread work and asynchronous methods backed by the shared config worker:

| Builder call | Behavior | Best fit |
|---|---|---|
| `create()` | Allows synchronous and asynchronous changes | Regular runtime config |
| `readOnly().create()` | Refuses loads and updates | Config a mod reads but never changes |

Declare the config class. Initialize persisted fields to their defaults and provide a public
no-argument constructor:

```java
@Config(name = "mymod") // format defaults to JSON5
public final class MyModConfig {
    public boolean showHints = true;
    public int hudScale = 2;
}
```

or for TOML:

```java
@Config(name = "mymod", format = ConfigFormat.TOML)
public final class MyModConfig {
    public boolean showHints = true;
    public int hudScale = 2;
}
```

Create the holder once during mod initialization and keep it for the lifetime of the mod. `create()`
resolves the file path, validates the model, loads any existing file (or writes the defaults if
none exists), and validates the loaded state — the holder is ready to read immediately after it
returns:

```java
public final class MyMod implements ModInitializer {

    public static final ConfigHolder<MyModConfig> CONFIG = LiteConfig.holder(MyModConfig.class)
        .modId("mymod")
        .create();
}
```

Read through `data()`, mutate through `update` / `updateAndSave`:

```java
if (MyMod.CONFIG.data().showHints) {
    // ...
}

MyMod.CONFIG.updateAndSave(config -> config.hudScale = 3);
```

That writes `config/mymod.json5`:

```json5
{
  "showHints": true,
  "hudScale": 3
}
```

## Full Configuration Example


```java
@Config(
    name = "mymodfile",
    path = "mymoddir1/mymoddir2",
    format = ConfigFormat.JSON5,
    comment = "MyMod settings.",
    version = 3,
    stateCloner = MyModConfigCloner.class,
    readFailurePolicy = FailurePolicy.FALLBACK,
    writeFailurePolicy = FailurePolicy.STRICT,
    updateFailurePolicy = FailurePolicy.FALLBACK
)
public final class MyModConfig implements ConfigExtension {

    @Entry(
        name = "hud_scale",
        comment = "Scale of the HUD, from 1 to 4.",
        translationKey = "mymod.config.hud_scale",
        sync = true,
        callback = "onHudScaleChanged"
    )
    @Range(min = 1, max = 4)
    public int hudScale = 2;

    @Entry(comment = "Rendering backend. Applied after the next restart.", restart = true)
    public Renderer renderer = Renderer.DEFAULT;

    @AllowedValues({"mysql", "sqlite"})
    public String database = "sqlite";

    @Entry(comment = {"Profile used by server rules.", "Must be lowercase, alphanumeric, or underscore."})
    @Pattern("[a-z0-9_]+")
    @Length(max = 16)
    public String profileName = "default";

    @Entry(comment = "Server-owned spawn range.", sync = true)
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
public static final class Cloner implements StateCloner<MyModConfig> {
    @Override
    public MyModConfig copy(MyModConfig source) {
        MyModConfig copy = new MyModConfig();
        copy.hudScale = source.hudScale;
        copy.renderer = source.renderer;
        copy.profileName = source.profileName;
        copy.spawnRange = source.spawnRange;
        copy.display = copyDisplay(source.display);
        copy.runtimeCache = source.runtimeCache == null
            ? new HashMap<>()
            : new HashMap<>(source.runtimeCache);
        return copy;
    }

    private static Display copyDisplay(Display source) {
        if (source == null) {
            return null;
        }
        Display copy = new Display();
        copy.showHints = source.showHints;
        copy.hiddenHints = source.hiddenHints == null
            ? null
            : new ArrayList<>(source.hiddenHints);
        return copy;
    }
}
```

Register custom codecs while creating a holder. Registrations use the shared process-wide registry
and are not bound to that holder or config. The `Codec` controls JSON5/TOML persistence and state
copying; if it rejects a value, Lite Config falls back to reflective serialization for that use.
The `StreamCodec` controls synchronization. Lite Config's loader entrypoints handle the packets,
handshake, batching, and server broadcasts.

```java
public final class MyMod implements ModInitializer {

    public static ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class, codecs -> codecs
            .registerCodec(IntRange.class, IntRange.CODEC)
            .registerStreamCodec(IntRange.class, IntRange.STREAM_CODEC))
        .modId("mymod")
        .onLoad(ConfigSide.SERVER, state -> System.out.println("Loaded profile " + state.profileName))
        .onUpdate(ConfigSide.BOTH, state -> System.out.println("HUD scale is now " + state.hudScale))
        .onSave(ConfigSide.SERVER, state -> System.out.println("Saved MyMod config"))
        .create();

    @Override
    public void onInitialize() {
        // As an alternative to the holder's codec registration above
        LiteConfig.codecs()
            .registerCodec(IntRange.class, IntRange.CODEC)
            .registerStreamCodec(IntRange.class, IntRange.STREAM_CODEC);
        
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

        // Serialized update and save. Synced values are broadcast by the server after acceptance.
        config.updateAndSaveAsync(state ->
            state.spawnRange = new IntRange(2, 24)
        ).thenAccept(update ->
            System.out.println("Saved: " + update.accepted()));

        // Structural metadata for screens, commands, generated help, etc.
        config.metadata().flatten().forEach(property ->
            System.out.println(property.path() + " -> " + property.type().getSimpleName()));
    }
}
```

The first `create()` loads `config/mymoddir1/mymoddir2/mymodfile.json5`, migrates older revisions in order, validates
the result, and writes the accepted state back. A file without `configVersion` starts at version 1.
Only `hud_scale` and `spawnRange` are synchronized because they opt in with `sync = true`; the other
values remain local. Use `@Config(sync = true)` instead when every persisted leaf is server-owned.
