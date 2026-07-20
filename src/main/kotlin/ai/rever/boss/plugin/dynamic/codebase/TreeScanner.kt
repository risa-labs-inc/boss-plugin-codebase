package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Read-side scan facade (issue #6).
 *
 * Routes tree scans through the host provider when it honors the showHidden
 * flag (api >= 1.0.65 with supportsHiddenEntries = true), and falls back to
 * the plugin-local [LocalFileScanner] on older hosts.
 *
 * REFLECTION, NOT STATIC CALLS: the host's BinaryCompatibilityValidator
 * statically scans the plugin jar and refuses to load it if it references
 * API members the runtime API lacks — so the new provider members must not
 * be referenced directly anywhere in shipped code. The capability probe and
 * the overload invocations are all done reflectively; on hosts without the
 * opt-in every lookup fails cleanly and the local fallback is used.
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
            try {
                val supports = provider.javaClass
                    .getMethod("getSupportsHiddenEntries")
                    .invoke(provider) as? Boolean == true
                if (supports) {
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
                }
            } catch (t: Throwable) {
                // Host API predates the opt-in (or probing failed) — use the fallback
                println(
                    "[codebase-plugin] TreeScanner: provider showHidden probe failed " +
                        "(${t.javaClass.simpleName}) — using LocalFileScanner fallback"
                )
                scan = null
                scanDepth = null
            }
        }
        scanMethod = scan
        scanWithDepthMethod = scanDepth
        if (provider != null && scanMethod != null) {
            println("[codebase-plugin] TreeScanner: host provider honors showHidden — provider-backed scans")
        }
    }

    /** True when scans go through the host provider (it honors showHidden). */
    val usesProvider: Boolean = scanMethod != null && scanWithDepthMethod != null

    suspend fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? {
        val method = scanMethod
        return if (method != null && provider != null) {
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
    ): FileNodeData? {
        val method = scanWithDepthMethod
        return if (method != null && provider != null) {
            invokeSuspend(method, provider, path, maxDepth, startDepth, showHidden) as FileNodeData?
        } else {
            LocalFileScanner.scanDirectoryWithDepth(path, maxDepth, startDepth, showHidden)
        }
    }

    /**
     * Invoke a reflected suspend method: pass the current continuation as the
     * trailing parameter; the call either returns the value directly or
     * COROUTINE_SUSPENDED (and resumes the continuation later).
     *
     * DISPATCH INVARIANT: this is an unintercepted continuation, so
     * resumption happens on whatever thread the host resumes from. Every
     * call site in this plugin wraps scans in withContext(Dispatchers.IO),
     * which reinstates proper dispatch on return — keep that invariant for
     * any new caller.
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
}
