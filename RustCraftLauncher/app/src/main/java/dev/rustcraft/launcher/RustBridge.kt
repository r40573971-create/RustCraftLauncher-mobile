package dev.rustcraft.launcher

object RustBridge {
    val isLoaded: Boolean = try {
        System.loadLibrary("rustcraft_core")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun coreVersion(): String

    /**
     * Разбирает version_manifest_v2.json в Rust.
     * Возвращает JSON: {"ok":true,"versions":[{"id","kind","url"}...],"error":null}
     * или {"ok":false,"versions":[],"error":"..."}.
     */
    external fun parseVersions(json: String, includeSnapshots: Boolean): String

    fun status(): String = if (isLoaded) {
        runCatching { coreVersion() }.getOrDefault("loaded, JNI call failed")
    } else {
        "not built yet"
    }
}
