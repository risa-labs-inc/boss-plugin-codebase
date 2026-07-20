import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.3.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: newest boss-plugin-api jar from the sibling repo,
        // so this path never needs hand-bumping on api releases. CI resolves
        // the 'latest' GitHub release instead (build/downloaded-deps).
        // Note: nothing here enforces a minimum version — the showHidden
        // opt-in exists from api 1.0.66, but older jars compile fine because
        // TreeScanner resolves the new members reflectively at runtime.
        val apiJarPattern = Regex("""boss-plugin-api-(\d+)\.(\d+)\.(\d+)\.jar""")
        val newestApiJar = file("$bossPluginApiPath/build/libs").listFiles()
            ?.mapNotNull { jar -> apiJarPattern.matchEntire(jar.name)?.let { m -> jar to m } }
            ?.maxByOrNull { (_, m) ->
                val (major, minor, patch) = m.destructured
                major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
            }?.first
            ?: error(
                "No boss-plugin-api jar found in $bossPluginApiPath/build/libs — " +
                    "run ./gradlew buildPluginJar in the sibling boss-plugin-api checkout first."
            )
        compileOnly(files(newestApiJar))
        // compileOnly isn't visible to the test compilation/runtime
        testImplementation(files(newestApiJar))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
        testImplementation(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Simple Icons for official brand icons (Kotlin, Python, TypeScript, Docker, etc.)
    implementation("br.com.devsrsouza.compose.icons:simple-icons:1.1.1")

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-codebase-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Codebase Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.codebase.CodebaseDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}

// Fat JAR for out-of-process plugin execution
tasks.register<Jar>("shadowJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
        )
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    from("src/main/resources")
}
