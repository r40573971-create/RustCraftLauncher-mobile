package dev.rustcraft.launcher

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Runtime archive extraction with path traversal protection.
 * Supports ZIP, TAR.XZ and TAR.GZ.
 */
object RuntimeArchiveExtractor {

    private const val MAX_ENTRIES = 20_000
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

    fun extractArchive(archive: File, destination: File): File {
        val name = archive.name.lowercase()

        return when {
            name.endsWith(".zip") ->
                extractZip(archive, destination)

            name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                extractTarXz(archive, destination)

            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                extractTarGz(archive, destination)

            else -> throw IllegalArgumentException(
                "Unsupported archive format: ${archive.name}"
            )
        }
    }

    fun extractZip(archive: File, destination: File): File {
        require(archive.isFile) { "Archive does not exist" }

        val root = prepareRoot(destination)
        var entries = 0
        var totalBytes = 0L

        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++

                if (entries > MAX_ENTRIES)
                    throw SecurityException("Too many archive entries")

                val output = safeFile(root, entry.name)

                if (entry.isDirectory) {
                    ensureDirectory(root, output)
                } else {
                    ensureDirectory(root, output.parentFile!!)
                    checkSymlink(output)

                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(32 * 1024)

                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break

                            totalBytes += count
                            checkSize(totalBytes)

                            out.write(buffer, 0, count)
                        }
                    }

                    if (entry.name == "bin/java" ||
                        entry.name.endsWith("/bin/java") ||
                        entry.name.endsWith("/bin/java.exe")) {
                        output.setExecutable(true, false)
                    }
                }

                zip.closeEntry()
            }
        }

        return root
    }

    fun extractTarXz(archive: File, destination: File): File {
        require(archive.isFile) { "Archive does not exist" }

        XZCompressorInputStream(
            BufferedInputStream(archive.inputStream())
        ).use { xz ->
            return extractTarStream(xz, destination)
        }
    }

    fun extractTarGz(archive: File, destination: File): File {
        require(archive.isFile) { "Archive does not exist" }

        GzipCompressorInputStream(
            BufferedInputStream(archive.inputStream())
        ).use { gzip ->
            return extractTarStream(gzip, destination)
        }
    }

    private fun extractTarStream(
        input: java.io.InputStream,
        destination: File
    ): File {
        val root = prepareRoot(destination)
        var entries = 0
        var totalBytes = 0L

        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                entries++

                if (entries > MAX_ENTRIES)
                    throw SecurityException("Too many archive entries")

                val output = safeFile(root, entry.name)

                when {
                    entry.isDirectory -> {
                        ensureDirectory(root, output)
                    }

                    entry.isSymbolicLink -> {
                        ensureDirectory(root, output.parentFile!!)

                        val linkTarget = File(
                            output.parentFile,
                            entry.linkName
                        ).canonicalFile

                        if (!isInside(root, linkTarget))
                            throw SecurityException("Symlink escapes extraction root")

                        if (output.exists() || output.isFile)
                            throw SecurityException("Symlink destination already exists")

                        checkSymlinkParent(root, output)

                        Os.symlink(
                            entry.linkName,
                            output.absolutePath
                        )
                    }

                    entry.isFile -> {
                        ensureDirectory(root, output.parentFile!!)
                        checkSymlinkParent(root, output)
                        checkSymlink(output)

                        FileOutputStream(output).use { out ->
                            val buffer = ByteArray(32 * 1024)

                            while (true) {
                                val count = tar.read(buffer)
                                if (count < 0) break

                                totalBytes += count
                                checkSize(totalBytes)

                                out.write(buffer, 0, count)
                            }
                        }

                        if ((entry.mode and 0b001001001) != 0) {
                            output.setExecutable(true, false)
                        }
                    }

                    else -> {
                        // Reject hard links and special files.
                        throw SecurityException(
                            "Unsupported TAR entry type: ${entry.name}"
                        )
                    }
                }
            }
        }

        return root
    }

    private fun prepareRoot(destination: File): File {
        if (!destination.exists() && !destination.mkdirs())
            throw IllegalStateException("Could not create destination")

        return destination.canonicalFile
    }

    private fun safeFile(root: File, name: String): File {
        val output = File(root, name).canonicalFile

        if (!isInside(root, output))
            throw SecurityException("Archive entry escapes destination")

        return output
    }

    private fun isInside(root: File, file: File): Boolean {
        return file == root ||
            file.path.startsWith(root.path + File.separator)
    }

    private fun ensureDirectory(root: File, directory: File) {
        if (!isInside(root, directory.canonicalFile))
            throw SecurityException("Directory escapes extraction root")

        checkSymlinkParent(root, directory)

        if (!directory.exists() && !directory.mkdirs())
            throw IllegalStateException("Could not create directory")

        if (!directory.isDirectory)
            throw IllegalStateException("Not a directory")
    }

    private fun checkSymlinkParent(root: File, file: File) {
        var current = file.parentFile

        while (current != null && current != root) {
            if (current.exists() && current.canonicalFile != current.absoluteFile) {
                if (!isInside(root, current.canonicalFile))
                    throw SecurityException("Symlink escapes extraction root")
            }
            current = current.parentFile
        }
    }

    private fun checkSymlink(file: File) {
        if (file.exists() && file.canonicalFile != file.absoluteFile)
            throw SecurityException("Refusing to overwrite symlink")
    }

    private fun checkSize(total: Long) {
        if (total > MAX_TOTAL_BYTES)
            throw SecurityException("Archive exceeds extraction size limit")
    }
}
