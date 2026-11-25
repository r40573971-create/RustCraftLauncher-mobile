package dev.rustcraft.launcher

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a Java runtime described by RuntimeCatalog, verifies SHA-256,
 * extracts it with RuntimeArchiveExtractor and installs it into
 * runtimesRoot/java-<major>.
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
        val fileName = pkg.fileName ?: throw IOException("Нет имени файла Java")

        root.mkdirs()
        val base = root.canonicalFile
        val finalDir = File(base, "java-$javaMajor")
        val tempDir = File(base, "java-$javaMajor.tmp")
        // The extractor picks the format by file extension, so keep the real name.
        val archive = File(base, "download-$javaMajor-$fileName")
        tempDir.deleteRecursively()
        archive.delete()

        try {
            download(pkg, archive, base, onProgress)

            if (cancelled) throw DownloadCancelled()
            onProgress(DownloadProgress(0, 0, 0, 0, "Распаковка…"))
            RuntimeArchiveExtractor.extractArchive(archive, tempDir)

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
        } finally {
            connection.disconnect()
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
}
