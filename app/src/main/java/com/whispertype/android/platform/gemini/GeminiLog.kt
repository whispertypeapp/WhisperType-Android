package com.whispertype.android.platform.gemini

import java.lang.reflect.Method

/**
 * JVM-safe logger for the Gemini session. The Android mockable `android.jar`
 * used by host unit tests stubs `android.util.Log` (`RuntimeException: Stub!`),
 * so direct `Log` calls would crash the JVM contract tests. This wrapper
 * resolves `android.util.Log` only when a real Android runtime is present and
 * no-ops otherwise (see docs/GEMINI_LIVE_PROTOCOL.md §6).
 *
 * Reflection is resolved once per level at first use and cached, not on every
 * log call; when the lookup fails (host JVM) the cached null makes the log a
 * no-op fast path.
 */
object GeminiLog {

    fun i(tag: String, message: String) = log(iMethod, tag, message)

    fun w(tag: String, message: String) = log(wMethod, tag, message)

    private val iMethod: Method? by lazy { resolve("i") }

    private val wMethod: Method? by lazy { resolve("w") }

    private fun resolve(name: String): Method? = try {
        Class.forName("android.util.Log").getMethod(name, String::class.java, String::class.java)
    } catch (_: Throwable) {
        // Host JVM: android.util.Log is stubbed or absent.
        null
    }

    private fun log(method: Method?, tag: String, message: String) {
        if (method == null) return
        try {
            method.invoke(null, tag, message)
        } catch (_: Throwable) {
            // Swallow silently, exactly as before.
        }
    }
}
