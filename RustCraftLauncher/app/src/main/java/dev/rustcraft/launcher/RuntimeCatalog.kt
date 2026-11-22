
package dev.rustcraft.launcher

/**
 * Runtime catalog for Vortex Launcher.
 *
 * This catalog describes runtime requirements and package metadata.
 * It intentionally contains no unverified download URLs.
 */
object RuntimeCatalog {

    const val PLATFORM = "android-arm64"
    const val ARCHITECTURE = "aarch64"
    const val ABI = "arm64-v8a"

    enum class ArchiveType {
        ZIP
    }

    enum class RuntimeStatus {
        NOT_CONFIGURED,
        READY
    }

    data class RuntimePackage(
        val javaMajor: Int,
        val platform: String,
        val architecture: String,
        val archiveType: ArchiveType,
        val downloadUrl: String?,
        val sha256: String?,
        val fileName: String?,
        val status: RuntimeStatus
    )

    private val supportedMajors = listOf(8, 16, 17, 21)

    /**
     * Placeholder catalog entries.
     * Packages become READY only after a verified source and checksum
     * have been supplied.
     */
    private val packages = supportedMajors.associateWith { major ->
        RuntimePackage(
            javaMajor = major,
            platform = PLATFORM,
            architecture = ARCHITECTURE,
            archiveType = ArchiveType.ZIP,
            downloadUrl = null,
            sha256 = null,
            fileName = null,
            status = RuntimeStatus.NOT_CONFIGURED
        )
    }

    fun supportedJavaVersions(): List<Int> = supportedMajors.toList()

    fun getPackage(javaMajor: Int): RuntimePackage {
        return packages[javaMajor]
            ?: throw IllegalArgumentException(
                "Unsupported Java version: $javaMajor"
            )
    }

    fun isConfigured(javaMajor: Int): Boolean {
        val runtime = getPackage(javaMajor)

        return runtime.status == RuntimeStatus.READY &&
            isValidPackageMetadata(runtime)
    }

    /**
     * Determines the Java version required by Minecraft.
     */
    fun requiredJavaMajor(minecraftVersion: String): Int {
        val match = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?.*$""")
            .matchEntire(minecraftVersion.trim())
            ?: throw IllegalArgumentException(
                "Invalid Minecraft version: $minecraftVersion"
            )

        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].ifBlank { "0" }.toInt()

        require(major == 1) {
            "Unsupported Minecraft version: $minecraftVersion"
        }

        return when {
            minor >= 21 -> 21
            minor == 20 && patch >= 5 -> 21
            minor in 18..20 -> 17
            minor == 17 -> 16
            else -> 8
        }
    }

    /**
     * Returns package metadata only when all required fields are valid.
     */
    fun getReadyPackage(javaMajor: Int): RuntimePackage {
        val runtime = getPackage(javaMajor)

        require(runtime.status == RuntimeStatus.READY) {
            "Runtime Java $javaMajor is not configured yet"
        }

        require(isValidPackageMetadata(runtime)) {
            "Runtime metadata is invalid"
        }

        return runtime
    }

    private fun isValidPackageMetadata(runtime: RuntimePackage): Boolean {
        val url = runtime.downloadUrl ?: return false
        val hash = runtime.sha256 ?: return false
        val name = runtime.fileName ?: return false

        return url.startsWith("https://") &&
            hash.matches(Regex("[a-fA-F0-9]{64}")) &&
            name.matches(Regex("[A-Za-z0-9._-]+")) &&
            runtime.platform == PLATFORM &&
            runtime.architecture == ARCHITECTURE &&
            runtime.archiveType == ArchiveType.ZIP
    }
}
