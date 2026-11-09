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

    /**
     * M2: JSON конкретной версии -> план скачивания.
     * {"ok":true,"id","mainClass","javaMajor","assetIndex":{...},"totalBytes",
     *  "files":[{"url","path","sha1","size","kind"}...]} или {"ok":false,"error":"..."}.
     */
    external fun planVersion(json: String): String

    /**
     * M2: индекс ресурсов -> план скачивания ресурсов.
     * {"ok":true,"legacy":false,"totalBytes","files":[...]} или {"ok":false,"error":"..."}.
     */
    external fun planAssets(json: String): String

    fun status(): String = if (isLoaded) {
        runCatching { coreVersion() }.getOrDefault("loaded, JNI call failed")
    } else {
        "not built yet"
    }
}
