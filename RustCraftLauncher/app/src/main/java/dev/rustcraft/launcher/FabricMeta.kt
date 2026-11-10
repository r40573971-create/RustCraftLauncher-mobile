package dev.rustcraft.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class FabricLoaderVersion(
    val version: String,
    val stable: Boolean
)

object FabricMeta {
    private const val BASE_URL = "https://meta.fabricmc.net/v2"

    private val dohClient: OkHttpClient by lazy {
        // Cloudflare DoH endpoint is resolved using its known IP as bootstrap.
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val doh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"))
            .build()

        OkHttpClient.Builder()
            .dns(doh)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun getLoaderVersions(minecraftVersion: String): List<FabricLoaderVersion> {
        val encoded = URLEncoder.encode(minecraftVersion, "UTF-8").replace("+", "%20")
        val request = Request.Builder()
            .url("$BASE_URL/versions/loader/$encoded")
            .header("Accept", "application/json")
            .header("User-Agent", "VortexLauncher/0.1.0")
            .get()
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Fabric API: HTTP ${response.code}")
            }
            val json = response.body?.string()
                ?: throw IOException("Fabric API вернул пустой ответ")
            val array = JSONArray(json)
            return List(array.length()) { index ->
                val loader = array.getJSONObject(index).getJSONObject("loader")
                FabricLoaderVersion(
                    version = loader.getString("version"),
                    stable = loader.optBoolean("stable", false)
                )
            }
        }
    }
}
