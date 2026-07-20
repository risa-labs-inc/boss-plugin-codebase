package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-side scan facade (issue #6).
 *
 * Routes tree scans through the host provider when it honors the showHidden
 * flag (api >= 1.0.66 with supportsHiddenEntries = true), and falls back to
 * the plugin-local [LocalFileScanner] on older hosts.
 *
 * REFLECTION, NOT STATIC CALLS: the host's BinaryCompatibilityValidator
 * statically scans the plugin jar and refuses to load it if it references
 * API members the runtime API lacks — so the new provider members must not
 * be referenced directly anywhere in shipped code. The overloads are real
 * JVM methods on the interface (verified via javap on the released 1.0.66
 * jar: `scanDirectory(String, boolean, Continuation)` etc. are distinct
 * `public default` members, not Kotlin default-parameter synthetics), so
 * exact-signature lookup is sound.
 *
 * Once minBossVersion can require a host with supportsHiddenEntries = true,
 * delete this reflection plus [LocalFileScanner] and call the provider
 * overloads directly (#6).
 */
internal class TreeScanner(private val provider: FileSystemDataProvider?) {

    private val scanMethod: Method?
    private val scanWithDepthMethod: Method?

    init {
        var scan: Method? = null
        var scanDepth: Method? = null
        if (provider != null) {
            // Benign probe: hosts whose API predates the opt-in simply lack
            // the member (NoSuchMethodException) — that is the normal old-host
            // case, distinct from the mismatch handled below.
            val supports = try {
                provider.javaClass
                    .getMethod("getSupportsHiddenEntries")
                    .invoke(provider) as? Boolean == true
            } catch (_: Throwable) {
                false
            }

            if (supports) {
                try {
                    // suspend fun compiles to (…args, Continuation) returning Object
                    scan = provider.javaClass.getMethod(
                        "scanDirectory",
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                        Continuation::class.java
                    )
                    scanDepth = provider.javaClass.getMethod(
                        "scanDirectoryWithDepth",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Continuation::class.java
                    )
                } catch (t: Throwable) {
                    // The host ADVERTISES the opt-in yet the overloads don't
                    // resolve — a real API/host signature mismatch that should
                    // never happen, unlike the benign old-host case above.
                    // Surface it distinctly; fall back so the panel still works.
                    println(
                        "[codebase-plugin] TreeScanner WARNING: host advertises supportsHiddenEntries " +
                            "but overload lookup failed (${t.javaClass.simpleName}: ${t.message}) — " +
                            "falling back to LocalFileScanner"
                    )
                    scan = null
                    scanDepth = null
                }
            }
        }
        scanMethod = scan
        scanWithDepthMethod = scanDepth

        // One routing line per process, not per construction (a scanner is
        // built per ViewModel and per MCP provider).
        if (provider != null && !loggedRouting) {
            loggedRouting = true
            val routed = scan != null && scanDepth != null
            println(
                "[codebase-plugin] TreeScanner: " +
                    if (routed) "host provider honors showHidden — provider-backed scans"
                    else "host predates the showHidden opt-in — using LocalFileScanner fallback"
            )
        }
    }

    /** True when scans go through the host provider (it honors showHidden). */
    val usesProvider: Boolean = scanMethod != null && scanWithDepthMethod != null

    // Both entry points dispatch to IO internally, so the unintercepted-
    // continuation resumption detail below never leaks to callers: whatever
    // thread the host resumes from, withContext reinstates the caller's
    // dispatcher on return. (The LocalFileScanner fallback is blocking IO
    // and benefits from the same dispatch.)

    suspend fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? =
        withContext(Dispatchers.IO) {
            val method = scanMethod
            if (method != null && provider != null) {
                invokeSuspend(method, provider, path, showHidden) as FileNodeData?
            } else {
                LocalFileScanner.scanDirectory(path, showHidden)
            }
        }

    suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
        showHidden: Boolean
    ): FileNodeData? =
        withContext(Dispatchers.IO) {
            val method = scanWithDepthMethod
            if (method != null && provider != null) {
                invokeSuspend(method, provider, path, maxDepth, startDepth, showHidden) as FileNodeData?
            } else {
                LocalFileScanner.scanDirectoryWithDepth(path, maxDepth, startDepth, showHidden)
            }
        }

    /**
     * Invoke a reflected suspend method: pass the current continuation as the
     * trailing parameter; the call either returns the value directly or
     * COROUTINE_SUSPENDED (and resumes the continuation later, on whatever
     * thread the host resumes from — see the dispatch note above).
     */
    private suspend fun invokeSuspend(method: Method, receiver: Any, vararg args: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(receiver, *args, continuation)
            } catch (e: InvocationTargetException) {
                // Surface the provider's real failure, not the reflective wrapper
                throw e.targetException ?: e
            }
        }

    private companion object {
        @Volatile
        private var loggedRouting = false
    }
}
