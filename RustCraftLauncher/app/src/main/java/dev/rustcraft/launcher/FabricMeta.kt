
package dev.rustcraft.launcher

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FabricLoaderVersion(
    val version: String,
    val stable: Boolean
)

object FabricMeta {

    private const val BASE_URL = "https://meta.fabricmc.net/v2"

    fun getLoaderVersions(minecraftVersion: String): List<FabricLoaderVersion> {
        val encodedVersion = URLEncoder.encode(minecraftVersion, "UTF-8")
            .replace("+", "%20")

        val url = "$BASE_URL/versions/loader/$encodedVersion"
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(
                    "Fabric API: HTTP ${connection.responseCode}"
                )
            }

            val json = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val array = JSONArray(json)

            return List(array.length()) { index ->
                val loader = array.getJSONObject(index)
                    .getJSONObject("loader")

                FabricLoaderVersion(
                    version = loader.getString("version"),
                    stable = loader.optBoolean("stable", false)
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}

