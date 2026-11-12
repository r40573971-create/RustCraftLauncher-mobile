package dev.rustcraft.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val doneFiles: Int, val totalFiles: Int, val doneBytes: Long,
    val totalBytes: Long, val message: String
) {
    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

data class InstalledGame(val id: String, val mainClass: String, val javaMajor: Int, val legacyAssets: Boolean)
class DownloadCancelled : IOException("Отменено")
private data class PlannedFile(val url: String, val path: String, val sha1: String, val size: Long)

fun httpGetText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 20_000
    try {
        if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}

/** Vanilla + optional Fabric installer file planner/downloader. */
class GameDownloader(root: File) {
    private val root = root.canonicalFile
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    fun download(
        versionUrl: String,
        fabricLoaderVersion: String? = null,
        onProgress: (DownloadProgress) -> Unit
    ): InstalledGame {
        if (!RustBridge.isLoaded) throw IOException("Rust-ядро не загружено")
        onProgress(DownloadProgress(0, 0, 0, 0, "Загрузка описания версии…"))
        val vanillaJson = httpGetText(versionUrl)
        val plan = requireOk(RustBridge.planVersion(vanillaJson))
        val vanillaId = plan.getString("id")
        val files = readFiles(plan.getJSONArray("files")).toMutableList()
        var installedId = vanillaId
        var mainClass = plan.getString("mainClass")
        var installJson = JSONObject(vanillaJson)

        if (!fabricLoaderVersion.isNullOrBlank()) {
            onProgress(DownloadProgress(0, 0, 0, 0, "Загрузка профиля Fabric…"))
            val profile = FabricMeta.getLoaderProfile(vanillaId, fabricLoaderVersion)
            installedId = profile.optString("id").ifBlank { "fabric-loader-$fabricLoaderVersion-$vanillaId" }
            if (!safeComponent(installedId)) throw IOException("Fabric вернул небезопасный ID версии")
            mainClass = profile.optString("mainClass")
            if (mainClass.isBlank()) throw IOException("В профиле Fabric отсутствует mainClass")
            files += readFabricLibraries(profile)

            installJson.put("id", installedId)
            installJson.put("mainClass", mainClass)
            val merged = JSONArray()
            val vanillaLibs = installJson.optJSONArray("libraries") ?: JSONArray()
            for (i in 0 until vanillaLibs.length()) merged.put(vanillaLibs.get(i))
            val fabricLibs = profile.optJSONArray("libraries") ?: JSONArray()
            for (i in 0 until fabricLibs.length()) merged.put(fabricLibs.get(i))
            installJson.put("libraries", merged)
        }

        val versionFile = target("versions/$installedId/$installedId.json")
        versionFile.parentFile?.mkdirs()
        versionFile.writeText(installJson.toString(2))

        val assetIndex = plan.getJSONObject("assetIndex")
        val indexPath = "assets/indexes/${assetIndex.getString("id")}.json"
        val indexFile = files.firstOrNull { it.path == indexPath }
            ?: throw IOException("В плане отсутствует индекс ресурсов")
        onProgress(DownloadProgress(0, 0, 0, 0, "Загрузка индекса ресурсов…"))
        ensureFile(indexFile) { }
        val assetsPlan = requireOk(RustBridge.planAssets(target(indexPath).readText()))
        val assets = readFiles(assetsPlan.getJSONArray("files"))
        val all = (files.filter { it.path != indexPath } + assets).distinctBy { it.path }
        checkFreeSpace(all)
        runParallel(all, onProgress)
        return InstalledGame(installedId, mainClass, plan.getInt("javaMajor"), assetsPlan.optBoolean("legacy"))
    }

    private fun requireOk(response: String): JSONObject {
        val json = JSONObject(response)
        if (!json.optBoolean("ok")) throw IOException("Ошибка Rust: ${json.optString("error", "неизвестная ошибка")}")
        return json
    }

    private fun readFiles(array: JSONArray): List<PlannedFile> = List(array.length()) { i ->
        val f = array.getJSONObject(i)
        PlannedFile(f.getString("url"), f.getString("path"), f.getString("sha1").lowercase(), f.getLong("size"))
    }

    /** Fabric Maven coordinates are resolved using the repository URL and verified via .sha1 sidecar. */
    private fun readFabricLibraries(profile: JSONObject): List<PlannedFile> {
        val libs = profile.optJSONArray("libraries") ?: JSONArray()
        val result = mutableListOf<PlannedFile>()
        for (i in 0 until libs.length()) {
            val lib = libs.optJSONObject(i) ?: continue
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact")
            if (artifact != null) {
                val p = artifact.optString("path")
                val u = artifact.optString("url")
                val h = artifact.optString("sha1")
                if (p.isNotBlank() && u.isNotBlank() && h.isNotBlank()) {
                    result += checkedFile(u, p, h, artifact.optLong("size", -1L))
                    continue
                }
            }
            val coordinate = lib.optString("name")
            if (coordinate.isBlank()) continue
            val path = mavenPath(coordinate)
            val base = lib.optString("url").ifBlank { "https://maven.fabricmc.net/" }.trimEnd('/') + "/"
            val artifactUrl = base + path
            val hash = httpGetText("$artifactUrl.sha1").trim().substringBefore(' ').lowercase()
            result += checkedFile(artifactUrl, path, hash, -1L)
        }
        return result.distinctBy { it.path }
    }

    private fun mavenPath(coordinate: String): String {
        val at = coordinate.split('@', limit = 2)
        val c = at[0].split(':')
        if (c.size !in 3..4 || c.any { it.isBlank() }) throw IOException("Неверная Maven-координата: $coordinate")
        val (group, artifact, version) = c
        val classifier = c.getOrNull(3)
        val ext = at.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "jar"
        val parts = listOf(group, artifact, version, ext) + listOfNotNull(classifier)
        if (parts.any { it.contains('/') || it.contains('\\') || it == "." || it == ".." }) throw IOException("Небезопасная Maven-координата")
        val file = buildString { append(artifact).append('-').append(version); if (!classifier.isNullOrBlank()) append('-').append(classifier); append('.').append(ext) }
        val path = group.replace('.', '/') + "/$artifact/$version/$file"
        if (!safePath(path)) throw IOException("Небезопасный путь библиотеки")
        return path
    }

    private fun checkedFile(url: String, path: String, sha1: String, size: Long): PlannedFile {
        if (!url.startsWith("https://")) throw IOException("Ссылка библиотеки должна использовать HTTPS")
        if (!safePath(path)) throw IOException("Небезопасный путь библиотеки: $path")
        if (!sha1.matches(Regex("[0-9a-fA-F]{40}"))) throw IOException("Некорректный SHA-1: $path")
        return PlannedFile(url, "libraries/$path", sha1.lowercase(), size)
    }

    private fun safePath(path: String) = path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    private fun safeComponent(value: String) = safePath(value) && !value.contains('/')

    private fun target(path: String): File {
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) throw IOException("Небезопасный путь: $path")
        return file
    }

    private fun checkFreeSpace(files: List<PlannedFile>) {
        root.mkdirs()
        val missing = files.filter { !target(it.path).exists() }.sumOf { it.size.coerceAtLeast(0L) }
        val needed = missing + missing / 10
        if (root.usableSpace < needed) throw IOException("Недостаточно места: нужно около ${needed / 1_048_576} МБ")
    }

    private fun runParallel(files: List<PlannedFile>, onProgress: (DownloadProgress) -> Unit) {
        val total = files.sumOf { it.size.coerceAtLeast(0L) }
        val bytes = AtomicLong(0); val done = AtomicInteger(0); val last = AtomicLong(0)
        fun report(force: Boolean) {
            val now = System.currentTimeMillis(); val old = last.get()
            if (force || (now - old >= 400 && last.compareAndSet(old, now))) onProgress(DownloadProgress(done.get(), files.size, bytes.get(), total, "Скачивание…"))
        }
        report(true)
        val pool = Executors.newFixedThreadPool(4) { task -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() } }
        try {
            val futures = files.map { file -> pool.submit(Callable<Unit> {
                if (cancelled) throw DownloadCancelled()
                ensureFile(file) { delta -> bytes.addAndGet(delta); report(false) }
                done.incrementAndGet(); report(false)
            }) }
            try { futures.forEach { it.get() } } catch (e: ExecutionException) { cancelled = true; throw e.cause ?: e }
        } finally { pool.shutdownNow() }
        report(true)
    }

    private fun ensureFile(file: PlannedFile, onBytes: (Long) -> Unit) {
        val dest = target(file.path)
        if (dest.isFile && (file.size < 0 || dest.length() == file.size) && sha1Of(dest) == file.sha1) {
            if (file.size > 0) onBytes(file.size)
            return
        }
        dest.parentFile?.mkdirs()
        var error: Exception? = null
        for (attempt in 1..3) {
            try { downloadOnce(file, dest, onBytes); return }
            catch (e: DownloadCancelled) { throw e }
            catch (e: Exception) { error = e }
        }
        throw IOException("Не удалось скачать ${file.path}: ${error?.message}", error)
    }

    private fun downloadOnce(file: PlannedFile, dest: File, onBytes: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        val connection = URL(file.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        var counted = 0L
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-1")
            connection.inputStream.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) throw DownloadCancelled()
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); digest.update(buffer, 0, n); counted += n; onBytes(n.toLong())
                }
            } }
            if (file.size >= 0 && counted != file.size) throw IOException("Не совпал размер файла")
            if (hex(digest.digest()) != file.sha1) throw IOException("Не совпала контрольная сумма SHA-1")
            dest.delete(); if (!part.renameTo(dest)) throw IOException("Не удалось сохранить файл")
        } catch (e: Throwable) { onBytes(-counted); throw e }
        finally { connection.disconnect(); part.delete() }
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
