package dev.rustcraft.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Profile(
    val id: String,
    val minecraftVersion: String,
    val loader: String,
    val loaderVersion: String?,
    val javaMajor: Int,
    val memoryMb: Int,
    val renderDistance: Int
)

/** Список профилей после операции. Если [error] не null, операция не выполнена. */
data class ProfileResult(val profiles: List<Profile>, val error: String?)

/**
 * Хранит профили в файле profiles.json. Проверку и изменение списка делает Rust,
 * здесь только чтение и запись файла.
 */
class ProfileStore(private val file: File) {

    fun load(): ProfileResult {
        if (!RustBridge.isLoaded) return ProfileResult(emptyList(), "Rust-ядро не загружено")
        val response = JSONObject(RustBridge.profilesNormalize(readText()))
        if (!response.optBoolean("ok")) {
            // Файл повреждён: оставляем копию и начинаем с пустого списка.
            if (file.isFile) file.renameTo(File(file.path + ".bak"))
            return ProfileResult(emptyList(), "Файл профилей был повреждён, начат новый (копия: profiles.json.bak)")
        }
        return ProfileResult(parse(response.getJSONArray("profiles")), null)
    }

    fun upsert(profile: Profile): ProfileResult =
        mutate { list -> RustBridge.profilesUpsert(list, toJson(profile).toString()) }

    fun delete(id: String): ProfileResult =
        mutate { list -> RustBridge.profilesDelete(list, id) }

    private fun mutate(operation: (String) -> String): ProfileResult {
        val before = load()
        if (before.error != null && !RustBridge.isLoaded) return before

        val response = JSONObject(operation(readText()))
        if (!response.optBoolean("ok")) {
            return ProfileResult(before.profiles, response.optString("error", "Ошибка"))
        }
        val array = response.getJSONArray("profiles")
        save(array)
        return ProfileResult(parse(array), null)
    }

    private fun readText(): String =
        if (file.isFile) runCatching { file.readText() }.getOrDefault("[]") else "[]"

    /** Пишем во временный файл и переименовываем, чтобы не остаться с половиной файла. */
    private fun save(array: JSONArray) {
        val temp = File(file.path + ".tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun parse(array: JSONArray): List<Profile> = List(array.length()) { index ->
        val item = array.getJSONObject(index)
        Profile(
            id = item.getString("id"),
            minecraftVersion = item.getString("minecraft_version"),
            loader = item.getString("loader"),
            loaderVersion = if (item.isNull("loader_version")) null else item.getString("loader_version"),
            javaMajor = item.getInt("java_major"),
            memoryMb = item.getInt("memory_mb"),
            renderDistance = item.getInt("render_distance")
        )
    }

    private fun toJson(profile: Profile): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("minecraft_version", profile.minecraftVersion)
        .put("loader", profile.loader)
        .put("loader_version", profile.loaderVersion ?: JSONObject.NULL)
        .put("java_major", profile.javaMajor)
        .put("memory_mb", profile.memoryMb)
        .put("render_distance", profile.renderDistance)
}
