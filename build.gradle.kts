import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.6.0"

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
        // Compiling needs api >= 1.0.66 (TreeScanner calls the showHidden
        // overloads statically); plugin.json's minApiVersion gates installs
        // to hosts whose runtime api layer has them.
        //
        // The lookup lives in a provider so it runs at dependency-RESOLUTION
        // time, not configuration time: clean/help/tasks still work on a
        // fresh checkout without the sibling jar built; compilation fails
        // with this actionable message instead of unresolved-reference noise.
        val newestApiJar = provider {
            val apiJarPattern = Regex("""boss-plugin-api-(\d+)\.(\d+)\.(\d+)\.jar""")
            file("$bossPluginApiPath/build/libs").listFiles()
                ?.mapNotNull { jar -> apiJarPattern.matchEntire(jar.name)?.let { m -> jar to m } }
                // Lexicographic (major, minor, patch) — no packing arithmetic
                // that would mis-order components >= 1000.
                ?.maxWithOrNull(
                    compareBy(
                        { it.second.groupValues[1].toInt() },
                        { it.second.groupValues[2].toInt() },
                        { it.second.groupValues[3].toInt() }
                    )
                )?.first
                ?: error(
                    "No boss-plugin-api jar found in $bossPluginApiPath/build/libs — " +
                        "run ./gradlew buildPluginJar in the sibling boss-plugin-api checkout first."
                )
        }
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

    // Compiled classes AND processed resources - sourceSets.main.output already
    // carries build/resources/main, so the raw src/main/resources must NOT be
    // added as well. With DuplicatesStrategy.EXCLUDE the first copy wins, so
    // adding both shipped the correct manifest only because the processed one
    // happened to be declared first. That is a live landmine now that
    // plugin.json holds the 0.0.0-dev placeholder and the plugin reads its
    // version back out of the jar at runtime: a reorder would ship a plugin
    // reporting 0.0.0-dev to the host.
    from(sourceSets.main.get().output)
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    // The filter rewrites the manifest version at execution time; declaring the
    // version as an input makes a version bump re-run the task. Without this the
    // processed manifest stays UP-TO-DATE and the new jar carries the old version.
    val pluginVersion = version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("**/plugin.json") {
        filter { line ->
            // The LAMBDA overload of replace, so the replacement is used
            // verbatim. The String overload hands it to java.util.regex.Matcher,
            // where a backslash escapes and a dollar is a group reference: the
            // previous form emitted a literal backslash and only produced the
            // right output because Matcher then swallowed it. It worked for
            // every semver by accident, and the manifest version is now
            // load-bearing (CodebaseDynamicPlugin reads it back at runtime).
            Regex(""""version"\s*:\s*"[^"]*"""").replace(line) { """"version": "$pluginVersion"""" }
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
    // Processed resources only - see buildPluginJar above.
    from(sourceSets.main.get().output)
}
