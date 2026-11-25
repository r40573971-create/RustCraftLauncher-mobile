package dev.rustcraft.launcher

import android.content.Context
import java.io.File

/**
 * Tracks where Java runtimes live and whether they are installed.
 * Installation itself is intentionally not implemented yet: RuntimeCatalog
 * has no verified download URL / SHA-256, so there is nothing to install.
 */
object JavaRuntimeManager {

    enum class State { NOT_CONFIGURED, NOT_INSTALLED, INSTALLED }

    private const val INSTALLED_MARKER = ".installed"

    fun runtimeDir(context: Context, javaMajor: Int): File =
        File(File(context.filesDir, "runtimes"), "java-$javaMajor")

    fun javaBinary(context: Context, javaMajor: Int): File =
        File(runtimeDir(context, javaMajor), "bin/java")

    fun isInstalled(context: Context, javaMajor: Int): Boolean {
        val dir = runtimeDir(context, javaMajor)
        return File(dir, INSTALLED_MARKER).isFile && javaBinary(context, javaMajor).isFile
    }

    fun stateFor(context: Context, javaMajor: Int): State {
        if (isInstalled(context, javaMajor)) return State.INSTALLED
        val configured = runCatching { RuntimeCatalog.isConfigured(javaMajor) }.getOrDefault(false)
        return if (configured) State.NOT_INSTALLED else State.NOT_CONFIGURED
    }
}
