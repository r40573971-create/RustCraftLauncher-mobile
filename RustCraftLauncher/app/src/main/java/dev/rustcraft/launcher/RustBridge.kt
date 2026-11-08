package dev.rustcraft.launcher

object RustBridge {
    private val loaded: Boolean = try {
        System.loadLibrary("rustcraft_core")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun coreVersion(): String

    fun status(): String = if (loaded) {
        runCatching { coreVersion() }.getOrDefault("loaded, JNI call failed")
    } else {
        "not built yet"
    }
}
