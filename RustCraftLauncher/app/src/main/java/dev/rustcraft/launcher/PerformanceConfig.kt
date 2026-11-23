package dev.rustcraft.launcher

/**
 * Central, version-aware performance settings.
 * This configuration layer does not itself change game performance.
 */
object PerformanceConfig {
    enum class RendererPreference { AUTO, GLES2_COMPATIBLE, GLES3 }

    data class Profile(
        val minecraftVersion: String,
        val javaMajor: Int,
        val minimumMemoryMb: Int,
        val recommendedMemoryMb: Int,
        val maximumMemoryMb: Int,
        val rendererPreference: RendererPreference,
        val extraJvmArguments: List<String> = emptyList()
    )

    fun profileFor(minecraftVersion: String): Profile {
        val javaMajor = RuntimeCatalog.requiredJavaMajor(minecraftVersion)
        return when {
            isAtOrBelow(minecraftVersion, 1, 12, 2) -> Profile(
                minecraftVersion, 8, 768, 1536, 3072, RendererPreference.AUTO
            )
            isAtOrBelow(minecraftVersion, 1, 16, 5) -> Profile(
                minecraftVersion, javaMajor, 1024, 2048, 4096, RendererPreference.AUTO
            )
            else -> Profile(
                minecraftVersion, javaMajor, 1536, 3072, 6144, RendererPreference.AUTO
            )
        }
    }

    /** availableRamMb is total device RAM, not currently free RAM. */
    fun suggestHeapMb(profile: Profile, availableRamMb: Int, userOverrideMb: Int? = null): Int {
        require(availableRamMb > 0) { "RAM value must be positive" }
        val deviceLimit = (availableRamMb * 0.60).toInt()
        val limit = minOf(deviceLimit, profile.maximumMemoryMb)
        if (userOverrideMb != null) {
            require(userOverrideMb >= profile.minimumMemoryMb) {
                "Heap override is below this profile's minimum"
            }
            return userOverrideMb.coerceAtMost(limit)
        }
        return profile.recommendedMemoryMb.coerceAtMost(limit)
            .coerceAtLeast(minOf(profile.minimumMemoryMb, limit))
    }

    fun heapArguments(heapMb: Int): List<String> {
        require(heapMb > 0) { "Heap size must be positive" }
        return listOf("-Xms${heapMb}M", "-Xmx${heapMb}M")
    }

    private fun isAtOrBelow(version: String, targetMajor: Int, targetMinor: Int, targetPatch: Int): Boolean {
        val match = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?.*$""").matchEntire(version.trim()) ?: return false
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].ifBlank { "0" }.toInt()
        if (major != targetMajor) return major < targetMajor
        if (minor != targetMinor) return minor < targetMinor
        return patch <= targetPatch
    }
}
