package dev.rustcraft.launcher

import android.content.Context
import java.io.File

/**
 * Runtime directory/catalog scaffold for Vortex Launcher.
 * This does not download, install, or execute a JVM yet.
 */
class RuntimeManager(context: Context) {
    private val runtimeRoot = File(context.filesDir, "runtimes").apply {
        if (!exists()) mkdirs()
    }

    data class RuntimeInfo(
        val javaMajor: Int,
        val directory: File,
        val isInstalled: Boolean
    )

    /**
     * Requirements:
     * - Minecraft 1.16.5 and older: Java 8
     * - Minecraft 1.17.x: Java 16
     * - 1.18.x through 1.20.4: Java 17
     * - 1.20.5 and newer: Java 21
     *
     * Unknown version strings are rejected instead of guessed.
     */
    fun requiredJavaMajor(minecraftVersion: String): Int {
        val match = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?.*$""")
            .matchEntire(minecraftVersion.trim())
            ?: throw IllegalArgumentException("Invalid Minecraft version: $minecraftVersion")

        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].ifBlank { "0" }.toInt()

        require(major == 1) { "Unsupported Minecraft version format: $minecraftVersion" }

        return when {
            minor >= 21 -> 21
            minor == 20 && patch >= 5 -> 21
            minor in 18..20 -> 17
            minor == 17 -> 16
            else -> 8
        }
    }

    fun listRuntimes(): List<RuntimeInfo> = supportedJavaMajors.map { major ->
        val directory = File(runtimeRoot, "java-$major")
        RuntimeInfo(
            javaMajor = major,
            directory = directory,
            isInstalled = File(directory, "bin/java").exists() ||
                File(directory, "release").exists()
        )
    }

    fun prepareRuntimeDirectory(javaMajor: Int): File {
        require(javaMajor in supportedJavaMajors) {
            "Unsupported Java major version: $javaMajor"
        }
        val directory = File(runtimeRoot, "java-$javaMajor")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create runtime directory: $directory")
        }
        return directory
    }

    companion object {
        private val supportedJavaMajors = listOf(8, 16, 17, 21)
    }
}
