package dev.rustcraft.launcher

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Downloads a runtime archive into app-private storage and verifies its SHA-256.
 * Extraction and JVM setup are intentionally handled by a later component.
 */
class RuntimeDownloader(context: Context) {
    private val downloadRoot = File(context.filesDir, "runtime-downloads").apply {
        if (!exists() && !mkdirs()) {
            throw IllegalStateException("Could not create runtime download directory")
        }
    }
    private val client = OkHttpClient()

    data class DownloadResult(val file: File, val sha256: String)

    fun download(
        url: String,
        expectedSha256: String,
        fileName: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): DownloadResult {
        require(url.startsWith("https://")) { "Runtime downloads must use HTTPS" }
        require(fileName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid file name" }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "A valid SHA-256 checksum is required"
        }

        val target = File(downloadRoot, fileName)
        val temp = File(downloadRoot, "$fileName.part")
        val request = Request.Builder().url(url).build()

        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("Download failed: HTTP ${it.code}")
            val body = it.body ?: throw IllegalStateException("Empty download response")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress?.invoke(downloaded, total)
                    }
                    output.flush()
                }
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                temp.delete()
                throw SecurityException("Runtime archive checksum mismatch")
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                throw IllegalStateException("Could not replace existing archive")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IllegalStateException("Could not finalize runtime archive")
            }
            return DownloadResult(target, actualSha)
        }
    }
}
