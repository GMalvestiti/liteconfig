# Lite Config

Lite Config is a JSON5/TOML config library for Minecraft mods on Fabric and NeoForge. You just need to annotate a
 Java class with `@Config`, hand it to a builder, and get back a `ConfigHolder` that handles
file path resolution, read/write operations, corrupt-file recovery, copies, validation, and 
lifecycle events.

**What Lite Config does:**
* **Configuration data layer:** Lite Config handles config files, including paths, loading, saving, default values, corruption recovery, atomic writes, and JSON5/TOML formats.
* **Safe state management:** provides validated snapshots, copies, runtime updates, and custom state cloning.
* **Async and read-only configs:** choose synchronous, asynchronous, or read-only holders depending on your threading and lifecycle needs.
* **Restart guards:** block local runtime updates and defer synchronized changes of restart-aware fields.
* **Custom update API:** `update` and `updateAndSave` return an `UpdateResult` with acceptance status and validation violations.
* **Fine-grained failure policies:** independently control how read, write, and update failures are handled, from graceful fallback to strict exceptions.
* **Lifecycle and event listeners:** hook into config load, save, and update events, or use config-level hooks for normalization and validation.
* **Declared constraints:** bound numbers, strings, and collections with annotations enforced on load and update and exposed through metadata.
* **Config metadata:** query every field's path, type, default, comment, translation key, and constraints at runtime.
* **Opt-in sync:** mark a config or individual field as synced and Lite Config automatically keeps those values synchronized from server to client.
* **Versioning and migrations:** stamp a revision into the file and upgrade older ones step by step instead of losing the player's values.
* **Customizable entries:** control file paths, field names, comments, ignored fields, and other persistence details.

**Outside Lite Config's scope:**
* **Config screen:** Lite Config is a data layer and it does not render UI by itself.

**Documentation:** [Wiki](https://github.com/gmalvestiti/liteconfig/wiki)

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
| `1.21`-`1.21.10` | `1.0.0-1.21`    |
| `1.21.11`        | `1.0.0-1.21.11` |
| `26.1`-`latest`  | `1.0.0-26.1`    |

The examples below target the `1.21` build.

<details>
<summary><b>Fabric — standalone</b></summary>

```groovy
repositories {
    mavenCentral()
}

dependencies {
    modImplementation 'com.gmalvestiti.minecraft:liteconfig-fabric:1.0.0-1.21'
}
```

Declare the dependency so the loader refuses to start without it:

```json
{
  "depends": {
    "liteconfig": ">=1.0.0"
  }
}
```
</details>

<details>
<summary><b>Fabric — embedded</b></summary>

```groovy
repositories {
    mavenCentral()
}

dependencies {
    modImplementation 'com.gmalvestiti.minecraft:liteconfig-fabric:1.0.0-1.21'
    include 'com.gmalvestiti.minecraft:liteconfig-fabric:1.0.0-1.21'
}
```
</details>

<details>
<summary><b>NeoForge — standalone</b></summary>

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.gmalvestiti.minecraft:liteconfig-neoforge:1.0.0-1.21'
}
```

Declare the dependency in `META-INF/neoforge.mods.toml`:

```toml
[[dependencies.yourmodid]]
modId = "liteconfig"
type = "required"
versionRange = "[1.0.0,)"
ordering = "NONE"
side = "BOTH"
```
</details>

<details>
<summary><b>NeoForge — embedded</b></summary>

```groovy
repositories {
    mavenCentral()
}

dependencies {
    jarJar(implementation('com.gmalvestiti.minecraft:liteconfig-neoforge:1.0.0-1.21') {
       version { 
           strictly '1.0.0-1.21'
           prefer '1.0.0-1.21'
       } 
    })
}
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
    path = "mymoddir",
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

    @Entry(comment = "Profile used by server rules.")
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

Register custom codecs before creating the first holder that uses the type. The `Codec` controls
JSON5/TOML persistence and state copying; the `StreamCodec` controls synchronization. Lite Config's
loader entrypoints handle the packets, handshake, batching, and server broadcasts.

```java
public final class MyMod implements ModInitializer {

    public static ConfigHolder<MyModConfig> config;

    @Override
    public void onInitialize() {
        // Codec registration
        LiteConfig.codecs()
            .registerCodec(IntRange.class, IntRange.CODEC)
            .registerStreamCodec(IntRange.class, IntRange.STREAM_CODEC);

        // Holder creation
        config = LiteConfig.holder(MyModConfig.class)
            .modId("mymod")
            .onLoad(ConfigSide.SERVER, state -> System.out.println("Loaded profile " + state.profileName))
            .onUpdate(ConfigSide.BOTH, state -> System.out.println("HUD scale is now " + state.hudScale))
            .onSave(ConfigSide.SERVER, state -> System.out.println("Saved MyMod config"))
            .create();

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

        // Structural metadata for screens, commands, or generated help.
        config.metadata().flatten().forEach(property ->
            System.out.println(property.path() + " -> " + property.type().getSimpleName()));
    }
}
```

The first `create()` loads `config/mymod/server.json5`, migrates older revisions in order, validates
the result, and writes the accepted state back. A file without `configVersion` starts at version 1.
Only `hud_scale` and `spawnRange` are synchronized because they opt in with `sync = true`; the other
values remain local. Use `@Config(sync = true)` instead when every persisted leaf is server-owned.
