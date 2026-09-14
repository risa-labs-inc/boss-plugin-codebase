package ai.rever.boss.plugin.dynamic.codebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The version chain, end to end: build.gradle.kts -> the processResources
 * filter -> the bundled plugin.json -> [CodebaseDynamicPlugin.version].
 *
 * Worth a test because every link is silent when it breaks. The filter used a
 * replacement string that java.util.regex.Matcher reinterprets - it emitted a
 * stray backslash and produced the right answer only because Matcher then
 * swallowed it, i.e. by accident, for every semver. And the manifest version
 * stopped being decoration the moment the plugin started reading it back
 * instead of carrying a fourth hand-maintained copy.
 */
class PluginManifestTest {

    @Test
    fun `the plugin reports the version the build stamped into the manifest`() {
        val version = CodebaseDynamicPlugin().version
        assertTrue(
            Regex("""^\d+\.\d+\.\d+$""").matches(version),
            "expected a bare semver from the processed manifest, got \"$version\"",
        )
        assertTrue(
            version != "0.0.0",
            "0.0.0 is the FALLBACK - the manifest was not found or had no version field",
        )
        assertTrue(
            version != "0.0.0-dev",
            "0.0.0-dev is the unprocessed source placeholder - processResources did not run",
        )
    }

    @Test
    fun `the manifest on the classpath is this plugin's own`() {
        // readManifestVersion walks every META-INF/boss-plugin/plugin.json on
        // the classpath and takes the one whose pluginId matches, because that
        // path is not namespaced per plugin and the first hit under parent-first
        // delegation may belong to somebody else.
        val text =
            CodebaseDynamicPlugin::class.java.classLoader
                .getResourceAsStream("META-INF/boss-plugin/plugin.json")!!
                .use { it.readBytes().decodeToString() }
        assertTrue(
            """"pluginId"\s*:\s*"ai\.rever\.boss\.plugin\.dynamic\.codebase"""".toRegex().containsMatchIn(text),
        )
    }

    @Test
    fun `every permission the git tools require is declared in the manifest`() {
        // An undeclared permission cannot be granted in the admin UI, so the
        // tool behind it would be permanently unreachable for non-admins.
        val text =
            CodebaseDynamicPlugin::class.java.classLoader
                .getResourceAsStream("META-INF/boss-plugin/plugin.json")!!
                .use { it.readBytes().decodeToString() }
        val declared =
            Regex(""""name"\s*:\s*"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        val required =
            CodebaseGitMcpToolProvider("codebase", { null }, { null })
                .tools()
                .flatMap { it.requiredPermissions }
                .toSet()
        assertTrue(required.isNotEmpty(), "the provider declares no permissions at all")
        assertEquals(
            emptySet(),
            required - declared,
            "required but not declared in plugin.json definedPermissions",
        )
    }
}
