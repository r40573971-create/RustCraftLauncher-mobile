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

    /** Профили: прочитать файл. {"ok":true,"profiles":[...],"error":null} или {"ok":false,...}. */
    external fun profilesNormalize(json: String): String

    /** Профили: добавить или заменить профиль (listJson — текущий список, profileJson — профиль). */
    external fun profilesUpsert(listJson: String, profileJson: String): String

    /** Профили: удалить профиль по id. */
    external fun profilesDelete(listJson: String, id: String): String

    /** Какая Java обычно нужна версии Minecraft (запасная оценка для нового профиля). */
    external fun suggestJava(version: String): Int

    fun status(): String = if (isLoaded) {
        runCatching { coreVersion() }.getOrDefault("loaded, JNI call failed")
    } else {
        "not built yet"
    }
}
