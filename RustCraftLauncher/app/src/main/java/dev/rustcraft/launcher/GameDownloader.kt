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
    val doneFiles: Int,
    val totalFiles: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    val message: String
) {
    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

/** Что нужно знать о скачанной версии, чтобы потом её запускать (M3). */
data class InstalledGame(
    val id: String,
    val mainClass: String,
    val javaMajor: Int,
    val legacyAssets: Boolean
)

class DownloadCancelled : IOException("Отменено")

private data class PlannedFile(val url: String, val path: String, val sha1: String, val size: Long)

fun httpGetText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 20_000
    try {
        if (connection.responseCode != 200) {
            throw IOException("HTTP ${connection.responseCode}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/**
 * Скачивает игру в [root]: клиент, библиотеки и ресурсы выбранной версии.
 * Какие файлы нужны и безопасны ли пути, решает Rust (planVersion/planAssets).
 * Здесь только сеть, проверка SHA-1 и запись на диск.
 *
 * download() блокирующий: вызывай из фонового потока.
 */
class GameDownloader(root: File) {
    private val root: File = root.canonicalFile

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun download(
        versionUrl: String,
        onProgress: (DownloadProgress) -> Unit
    ): InstalledGame {
        if (!RustBridge.isLoaded) throw IOException("Rust-ядро не загружено")

        onProgress(DownloadProgress(0, 0, 0, 0, "Загрузка описания версии…"))
        val versionJson = httpGetText(versionUrl)
        val plan = requireOk(RustBridge.planVersion(versionJson))
        val id = plan.getString("id")

        // Описание версии нужно будет для запуска (аргументы, библиотеки).
        val versionFile = target("versions/$id/$id.json")
        versionFile.parentFile?.mkdirs()
        versionFile.writeText(versionJson)

        val planFiles = readFiles(plan.getJSONArray("files"))

        // Сначала индекс ресурсов: по нему узнаём список ассетов.
        val indexRef = plan.getJSONObject("assetIndex")
        val indexFile = planFiles.first { it.path == "assets/indexes/${indexRef.getString("id")}.json" }
        onProgress(DownloadProgress(0, 0, 0, 0, "Загрузка индекса ресурсов…"))
        ensureFile(indexFile) { }

        val assetsPlan = requireOk(RustBridge.planAssets(target(indexFile.path).readText()))
        val assetFiles = readFiles(assetsPlan.getJSONArray("files"))

        val all = (planFiles.filter { it.path != indexFile.path } + assetFiles)
        checkFreeSpace(all)
        runParallel(all, onProgress)

        return InstalledGame(
            id = id,
            mainClass = plan.getString("mainClass"),
            javaMajor = plan.getInt("javaMajor"),
            legacyAssets = assetsPlan.optBoolean("legacy")
        )
    }

    // ----- план -----

    private fun requireOk(response: String): JSONObject {
        val json = JSONObject(response)
        if (!json.optBoolean("ok")) {
            throw IOException("Ошибка Rust: ${json.optString("error", "неизвестная ошибка")}")
        }
        return json
    }

    private fun readFiles(array: JSONArray): List<PlannedFile> = List(array.length()) { index ->
        val file = array.getJSONObject(index)
        PlannedFile(
            url = file.getString("url"),
            path = file.getString("path"),
            sha1 = file.getString("sha1"),
            size = file.getLong("size")
        )
    }

    /** Ещё одна защита от выхода за папку игры (основная проверка в Rust). */
    private fun target(path: String): File {
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) {
            throw IOException("Небезопасный путь: $path")
        }
        return file
    }

    private fun checkFreeSpace(files: List<PlannedFile>) {
        root.mkdirs()
        val missing = files.filter { !target(it.path).exists() }.sumOf { it.size }
        val needed = missing + missing / 10
        if (root.usableSpace < needed) {
            val mb = needed / 1_048_576
            throw IOException("Недостаточно места: нужно около $mb МБ")
        }
    }

    // ----- скачивание -----

    private fun runParallel(files: List<PlannedFile>, onProgress: (DownloadProgress) -> Unit) {
        val totalBytes = files.sumOf { it.size }
        val doneBytes = AtomicLong(0)
        val doneFiles = AtomicInteger(0)
        val lastReport = AtomicLong(0)

        fun report(force: Boolean) {
            val now = System.currentTimeMillis()
            val previous = lastReport.get()
            if (force || (now - previous >= 400 && lastReport.compareAndSet(previous, now))) {
                onProgress(
                    DownloadProgress(
                        doneFiles.get(), files.size, doneBytes.get(), totalBytes, "Скачивание…"
                    )
                )
            }
        }

        report(true)
        // 4 потока с фоновым приоритетом, чтобы интерфейс не лагал.
        val pool = Executors.newFixedThreadPool(4) { task ->
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }
        }
        try {
            val futures = files.map { file ->
                pool.submit(Callable<Unit> {
                    if (cancelled) throw DownloadCancelled()
                    ensureFile(file) { delta ->
                        doneBytes.addAndGet(delta)
                        report(false)
                    }
                    doneFiles.incrementAndGet()
                    report(false)
                })
            }
            try {
                futures.forEach { it.get() }
            } catch (e: ExecutionException) {
                cancelled = true
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }
        report(true)
    }

    /** Файл уже на месте и целый — пропускаем, иначе скачиваем (до 3 попыток). */
    private fun ensureFile(file: PlannedFile, onBytes: (Long) -> Unit) {
        val destination = target(file.path)
        if (destination.isFile && destination.length() == file.size && sha1Of(destination) == file.sha1) {
            onBytes(file.size)
            return
        }
        destination.parentFile?.mkdirs()

        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                downloadOnce(file, destination, onBytes)
                return
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Не удалось скачать ${file.path}: ${lastError?.message}", lastError)
    }

    private fun downloadOnce(file: PlannedFile, destination: File, onBytes: (Long) -> Unit) {
        val part = File(destination.path + ".part")
        val connection = URL(file.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        var counted = 0L
        try {
            if (connection.responseCode != 200) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val digest = MessageDigest.getInstance("SHA-1")
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw DownloadCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        counted += read
                        onBytes(read.toLong())
                    }
                }
            }
            if (hex(digest.digest()) != file.sha1) {
                throw IOException("не совпала контрольная сумма")
            }
            destination.delete()
            if (!part.renameTo(destination)) {
                throw IOException("не удалось сохранить файл")
            }
        } catch (e: Throwable) {
            onBytes(-counted)
            throw e
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
