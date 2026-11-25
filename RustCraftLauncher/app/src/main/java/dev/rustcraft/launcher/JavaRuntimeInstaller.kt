package dev.rustcraft.launcher

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads a Java runtime described by RuntimeCatalog, verifies SHA-256,
 * extracts the ZIP safely and installs it into runtimesRoot/java-<major>.
 * Throws IOException with a readable message if the catalog entry is not configured.
 */
class JavaRuntimeInstaller(runtimesRoot: File) {
    private val root = runtimesRoot
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    fun install(javaMajor: Int, onProgress: (DownloadProgress) -> Unit): File {
        val pkg = try {
            RuntimeCatalog.getReadyPackage(javaMajor)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "Java $javaMajor не настроена")
        }
        root.mkdirs()
        val base = root.canonicalFile
        val finalDir = File(base, "java-$javaMajor")
        val tempDir = File(base, "java-$javaMajor.tmp")
        val archive = File(base, "java-$javaMajor.zip.part")
        tempDir.deleteRecursively()
        archive.delete()

        try {
            download(pkg, archive, base, onProgress)
            extract(archive, tempDir, onProgress)

            val runtimeRoot = findRuntimeRoot(tempDir)
                ?: throw IOException("В архиве не найден bin/java")
            markExecutable(runtimeRoot)
            File(runtimeRoot, ".installed").writeText(pkg.sha256.orEmpty())

            finalDir.deleteRecursively()
            if (!runtimeRoot.renameTo(finalDir)) throw IOException("Не удалось сохранить Java")
            return finalDir
        } finally {
            archive.delete()
            if (tempDir.exists()) tempDir.deleteRecursively()
        }
    }

    private fun download(
        pkg: RuntimeCatalog.RuntimePackage,
        dest: File,
        base: File,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val url = pkg.downloadUrl ?: throw IOException("Нет ссылки на Java")
        val expected = (pkg.sha256 ?: throw IOException("Нет SHA-256 для Java")).lowercase()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            if (total > 0 && base.usableSpace < total * 3) {
                throw IOException("Недостаточно места: нужно около ${total * 3 / 1_048_576} МБ")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw DownloadCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 400) {
                            lastReport = now
                            onProgress(DownloadProgress(0, 1, done, total, "Скачивание Java…"))
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException("Не совпала контрольная сумма SHA-256")
            onProgress(DownloadProgress(1, 1, done, total.coerceAtLeast(done), "Скачано"))
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(archive: File, dest: File, onProgress: (DownloadProgress) -> Unit) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        var files = 0
        var written = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (cancelled) throw DownloadCancelled()
                val out = File(destRoot, entry.name).canonicalFile
                if (!out.path.startsWith(destRoot.path + File.separator)) {
                    throw IOException("Небезопасный путь в архиве: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > MAX_EXTRACTED_BYTES) throw IOException("Архив слишком большой")
                        output.write(buffer, 0, n)
                    }
                }
                files++
                if (files % 50 == 0) {
                    onProgress(DownloadProgress(0, 0, 0, 0, "Распаковка… $files файлов"))
                }
            }
        }
    }

    private fun findRuntimeRoot(dir: File): File? {
        if (File(dir, "bin/java").isFile) return dir
        return dir.listFiles()?.singleOrNull { it.isDirectory && File(it, "bin/java").isFile }
    }

    private fun markExecutable(runtimeRoot: File) {
        File(runtimeRoot, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(runtimeRoot, "lib").walkTopDown()
            .filter { it.isFile && it.name == "jspawnhelper" }
            .forEach { it.setExecutable(true, false) }
    }

    private companion object {
        const val MAX_EXTRACTED_BYTES = 1_500_000_000L
    }
}
