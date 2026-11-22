
package dev.rustcraft.launcher

import java.io.File

/**
 * Orchestrates runtime download, extraction and installation.
 *
 * Blocking API: call from Dispatchers.IO or another background thread.
 */
class RuntimeInstaller(
    private val runtimeManager: RuntimeManager,
    private val runtimeDownloader: RuntimeDownloader,
    private val archiveExtractor: RuntimeArchiveExtractor
) {

    /**
     * Installs a runtime for the requested Java major version.
     *
     * @param javaMajor Java major version (8, 16, 17, 21).
     * @param downloadUrl HTTPS URL to the runtime archive.
     * @param sha256 Expected archive SHA-256.
     * @param archiveName Safe archive filename.
     * @param onProgress Download progress callback.
     */
    fun install(
        javaMajor: Int,
        downloadUrl: String,
        sha256: String,
        archiveName: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {

        require(javaMajor in setOf(8, 16, 17, 21)) {
            "Unsupported Java version: $javaMajor"
        }

        require(downloadUrl.startsWith("https://")) {
            "Runtime download must use HTTPS"
        }

        require(archiveName.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Invalid archive filename"
        }

        val runtimeRoot = runtimeManager.runtimeDirectory(javaMajor)
        val stagingRoot = File(runtimeRoot.parentFile, "staging-$javaMajor")
        val downloadRoot = File(runtimeRoot.parentFile, "downloads")

        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            error("Cannot create staging directory")
        }

        if (!downloadRoot.exists() && !downloadRoot.mkdirs()) {
            error("Cannot create download directory")
        }

        val archiveFile = File(downloadRoot, archiveName)
        val extractedRoot = File(stagingRoot, "extracted")

        try {
            runtimeDownloader.download(
                url = downloadUrl,
                destination = archiveFile,
                expectedSha256 = sha256,
                onProgress = onProgress
            )

            if (extractedRoot.exists()) {
                extractedRoot.deleteRecursively()
            }

            if (!extractedRoot.mkdirs()) {
                error("Cannot create extraction directory")
            }

            archiveExtractor.extract(
                archiveFile,
                extractedRoot
            )

            val runtimeCandidate = findRuntimeRoot(extractedRoot)
                ?: error("Runtime files not found in archive")

            validateRuntime(runtimeCandidate)

            val installParent = runtimeRoot.parentFile
            val backup = File(installParent, "backup-$javaMajor")

            if (backup.exists()) {
                backup.deleteRecursively()
            }

            if (runtimeRoot.exists()) {
                if (!runtimeRoot.renameTo(backup)) {
                    error("Cannot backup existing runtime")
                }
            }

            if (!runtimeCandidate.renameTo(runtimeRoot)) {
                if (backup.exists()) {
                    backup.renameTo(runtimeRoot)
                }
                error("Cannot finalize runtime installation")
            }

            if (backup.exists()) {
                backup.deleteRecursively()
            }

            return runtimeRoot

        } catch (e: Exception) {
            throw RuntimeException(
                "Runtime installation failed: ${e.message}",
                e
            )
        } finally {
            stagingRoot.deleteRecursively()
            archiveFile.delete()
        }
    }

    private fun findRuntimeRoot(extracted: File): File? {
        if (hasRuntimeFiles(extracted)) {
            return extracted
        }

        val children = extracted.listFiles()
            ?.filter { it.isDirectory }
            ?: return null

        for (child in children) {
            val result = findRuntimeRoot(child)
            if (result != null) return result
        }

        return null
    }

    private fun hasRuntimeFiles(directory: File): Boolean {
        val bin = File(directory, "bin")
        val java = File(bin, "java")
        val javaExe = File(bin, "java.exe")

        return java.exists() || javaExe.exists()
    }

    private fun validateRuntime(directory: File) {
        val java = File(directory, "bin/java")
        val javaExe = File(directory, "bin/java.exe")

        require(java.exists() || javaExe.exists()) {
            "Runtime executable not found"
        }

        require(directory.isDirectory) {
            "Runtime directory is invalid"
        }
    }
}
