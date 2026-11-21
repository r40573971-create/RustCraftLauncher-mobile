package dev.rustcraft.launcher

import android.content.Context
import java.io.File

/**
 * Initial runtime-management scaffold for Vortex Launcher.
 * It manages directories and Java-version requirements, not JVM installation/execution yet.
 */
class RuntimeManager(context: Context) {
    private val runtimeRoot: File = File(context.filesDir, "runtimes").apply {
        if (!exists()) mkdirs()
    }

    data class RuntimeInfo(val javaMajor: Int, val directory: File, val isInstalled: Boolean)

    fun requiredJavaMajor(minecraftVersion: String): Int {
        val minor = minecraftVersion.split('.').getOrNull(1)?.toIntOrNull() ?: return 17
        return when {
            minor >= 21 -> 21
            minor >= 18 -> 17
            minor >= 17 -> 16
            else -> 8
        }
    }

    fun listRuntimes(): List<RuntimeInfo> = listOf(8, 16, 17, 21).map { major ->
        val directory = File(runtimeRoot, "java-$major")
        RuntimeInfo(major, directory,
            File(directory, "bin/java").exists() || File(directory, "release").exists())
    }

    fun prepareRuntimeDirectory(javaMajor: Int): File {
        require(javaMajor in listOf(8, 16, 17, 21)) { "Unsupported Java major version: $javaMajor" }
        val directory = File(runtimeRoot, "java-$javaMajor")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create runtime directory: $directory")
        }
        return directory
    }
}
