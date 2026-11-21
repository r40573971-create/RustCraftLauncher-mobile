package dev.rustcraft.launcher

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Safely extracts ZIP-based runtime packages into a staging directory.
 * TAR/TAR.GZ packages and symlink entries are not supported by this component.
 */
object RuntimeArchiveExtractor {
    private const val MAX_ENTRIES = 20_000
    private const val MAX_TOTAL_BYTES = 1L * 1024 * 1024 * 1024 // 1 GiB cap

    fun extractZip(archive: File, destination: File): File {
        require(archive.isFile) { "Archive does not exist" }
        if (!destination.exists() && !destination.mkdirs()) {
            throw IllegalStateException("Could not create extraction destination")
        }

        val root = destination.canonicalFile
        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw SecurityException("Too many archive entries")

                val output = File(root, entry.name).canonicalFile
                if (output != root && !output.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("Archive entry escapes destination")
                }

                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw IllegalStateException("Could not create directory")
                    }
                } else {
                    output.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) {
                            throw IllegalStateException("Could not create parent directory")
                        }
                    }
                    FileOutputStream(output).use { fileOut ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw SecurityException("Archive exceeds extraction size limit")
                            }
                            fileOut.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return root
    }
}
