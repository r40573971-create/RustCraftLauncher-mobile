
package dev.rustcraft.launcher

import java.io.File

/**
 * Downloads, extracts, validates and installs a runtime.
 * Call install() on a background thread (Dispatchers.IO).
 */
class RuntimeInstaller(
    private val runtimeManager: RuntimeManager,
    private val runtimeDownloader: RuntimeDownloader,
    private val archiveExtractor: RuntimeArchiveExtractor
) {

    fun install(
        javaMajor: Int,
        downloadUrl: String,
        sha256: String,
        archiveName: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {

        require(javaMajor in setOf(8, 16, 17, 21))
        require(downloadUrl.startsWith("https://"))
        require(archiveName.matches(Regex("[A-Za-z0-9._-]+")))

        val runtimeRoot = runtimeManager.prepareRuntimeDirectory(javaMajor)
        val parent = runtimeRoot.parentFile
            ?: error("Runtime parent directory unavailable")

        val stagingRoot = File(parent, "staging-$javaMajor")
        val extractedRoot = File(stagingRoot, "extracted")
        val backup = File(parent, "backup-$javaMajor")

        try {
            stagingRoot.deleteRecursively()
            stagingRoot.mkdirs()

            val download = runtimeDownloader.download(
                url = downloadUrl,
                expectedSha256 = sha256,
                fileName = archiveName,
                onProgress = onProgress
            )

            archiveExtractor.extractZip(
                archive = download.file,
                destination = extractedRoot
            )

            val candidate = findRuntimeRoot(extractedRoot)
                ?: error("Java runtime not found in archive")

            validateRuntime(candidate)

            // Preserve the previous installation until the new one is ready.
            if (backup.exists()) {
                backup.deleteRecursively()
            }

            if (runtimeRoot.exists()) {
                if (!runtimeRoot.renameTo(backup)) {
                    error("Could not backup previous runtime")
                }
            }

            if (!candidate.renameTo(runtimeRoot)) {
                // Restore the previous installation if finalization fails.
                if (backup.exists()) {
                    backup.renameTo(runtimeRoot)
                }
                error("Could not finalize runtime installation")
            }

            // New installation is in place.
            backup.deleteRecursively()

            return runtimeRoot

        } catch (e: Exception) {
            throw IllegalStateException(
                "Runtime installation failed: ${e.message}",
                e
            )
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private fun findRuntimeRoot(directory: File): File? {
        if (hasJavaExecutable(directory)) return directory

        val children = directory.listFiles()
            ?.filter { it.isDirectory }
            ?: return null

        for (child in children) {
            val result = findRuntimeRoot(child)
            if (result != null) return result
        }

        return null
    }

    private fun hasJavaExecutable(directory: File): Boolean {
        return File(directory, "bin/java").exists() ||
            File(directory, "bin/java.exe").exists()
    }

    private fun validateRuntime(directory: File) {
        require(directory.isDirectory) {
            "Runtime directory is invalid"
        }

        require(hasJavaExecutable(directory)) {
            "Java executable not found"
        }
    }
}
