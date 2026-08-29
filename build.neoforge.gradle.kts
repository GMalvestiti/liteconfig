plugins {
    id("build.common")
    id("neoforge.mutex")
    id("net.neoforged.moddev") version "2.0.144"
}

version = "${property("mod.version")}-${sc.current.version}"

if (property("dev.snapshot").toString().toBoolean()) {
    version = "$version-SNAPSHOT"
}

base.archivesName = "${property("mod.id")}-neoforge"

sourceSets.main {
    resources.srcDir("src/main/resources")
    resources.srcDir("src/main/generated")
    resources.exclude("**/.cache")
}

val distributionJar: Provider<RegularFile> = tasks.shadowJar.flatMap { it.archiveFile }

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

java {
    withJavadocJar()
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

neoForge {
    version = property("deps.neoforge_loader") as String

    validateAccessTransformers = true

    mods {
        register("${property("mod.id")}") {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        configureEach {
            gameDirectory = file("../../run/")
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }

        register("client") {
            client()
            gameDirectory = file("../../runClient/")
            programArgument("--username=${property("dev.username")}")
            programArgument("--uuid=${property("dev.uuid")}")
        }

        register("server") {
            server()
            programArgument("--nogui")
        }
    }

    unitTest {
        enable()
        testedMod.set(mods.getByName("${property("mod.id")}"))
    }
}

dependencies {

}

tasks {
    register<Copy>("buildAndCollect") {
        group = "custom"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        dependsOn(build)

        inputs.property("version", project.property("mod.version"))

        from(distributionJar)

        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }

    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
            register("description", "mod.description")
            register("author", "mod.author")
            register("contact_homepage", "mod.contact_homepage")
            register("contact_sources", "mod.contact_sources")
            register("contact_issues", "mod.contact_issues")
            register("license", "mod.license")
            register("neoforge_loader", "deps.neoforge_loader")
        }

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

        from(rootProject.file("LICENSE.md")) { into("") }

        exclude("fabric.mod.json", "*.ct", "*.classtweaker")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }
}

afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("mavenJava") {
                artifact(distributionJar) {
                    classifier = ""
                }
            }
        }
    }
}

publishMods {
    file.set(distributionJar)
    displayName.set("${property("mod.name")} NeoForge ${property("mod.version")} for ${property("publish.start")}")
    modLoaders.add("neoforge")

    curseforge {
        javaVersions.add(requiredJava)
    }
}
