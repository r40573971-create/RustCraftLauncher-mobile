package dev.rustcraft.launcher

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class FabricLoaderVersion(
    val version: String,
    val stable: Boolean
)

object FabricMeta {
    private const val BASE_URL =
        "https://bold-snow-a6b4.a66937094.workers.dev/v2"
    private const val PREFS = "fabric_versions_cache"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun cacheKey(minecraftVersion: String) = "fabric_$minecraftVersion"

    fun getCachedLoaderVersions(
        context: Context,
        minecraftVersion: String
    ): List<FabricLoaderVersion> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cacheKey(minecraftVersion), null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                FabricLoaderVersion(
                    version = item.getString("version"),
                    stable = item.optBoolean("stable", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun refreshLoaderVersions(
        context: Context,
        minecraftVersion: String
    ): List<FabricLoaderVersion> {
        val result = fetchLoaderVersions(minecraftVersion)
        val array = JSONArray()
        result.forEach { item ->
            array.put(
                JSONObject()
                    .put("version", item.version)
                    .put("stable", item.stable)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(cacheKey(minecraftVersion), array.toString())
            .apply()
        return result
    }

    fun getLoaderVersions(minecraftVersion: String): List<FabricLoaderVersion> =
        fetchLoaderVersions(minecraftVersion)

    private fun fetchLoaderVersions(minecraftVersion: String): List<FabricLoaderVersion> {
        val encoded = URLEncoder.encode(minecraftVersion, "UTF-8")
            .replace("+", "%20")

        val request = Request.Builder()
            .url("$BASE_URL/versions/loader/$encoded")
            .header("Accept", "application/json")
            .header("User-Agent", "VortexLauncher/0.1.0")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Fabric API: HTTP ${response.code}")
            }

            val json = response.body?.string()
                ?: throw IOException("Fabric API вернул пустой ответ")

            val array = JSONArray(json)

            return List(array.length()) { index ->
                val loader = array.getJSONObject(index)
                    .getJSONObject("loader")

                FabricLoaderVersion(
                    version = loader.getString("version"),
                    stable = loader.optBoolean("stable", false)
                )
            }
        }
    }

    /**
     * Загружает полный профиль Fabric для выбранной версии Minecraft
     * и версии загрузчика. Профиль содержит mainClass и библиотеки
     * для последующей установки Fabric.
     */
    fun getLoaderProfile(
        minecraftVersion: String,
        loaderVersion: String
    ): JSONObject {
        val game = URLEncoder.encode(minecraftVersion, "UTF-8")
            .replace("+", "%20")
        val loader = URLEncoder.encode(loaderVersion, "UTF-8")
            .replace("+", "%20")

        val request = Request.Builder()
            .url("$BASE_URL/versions/loader/$game/$loader/profile/json")
            .header("Accept", "application/json")
            .header("User-Agent", "VortexLauncher/0.1.0")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Fabric profile: HTTP ${response.code}")
            }

            val body = response.body?.string()
                ?: throw IOException("Fabric API вернул пустой профиль")

            return JSONObject(body)
        }
    }
}
