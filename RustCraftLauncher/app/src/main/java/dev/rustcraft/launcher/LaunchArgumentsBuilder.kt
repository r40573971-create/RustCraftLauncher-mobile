package dev.rustcraft.launcher

/**
 * Builds JVM arguments from Vortex's performance profile and user settings.
 *
 * This builder only assembles arguments. The launch pipeline must pass the
 * returned list to the Java process and test compatibility per game/runtime.
 */
object LaunchArgumentsBuilder {

    data class Settings(
        val heapMb: Int,
        val customJvmArguments: List<String> = emptyList()
    )

    fun build(
        profile: PerformanceConfig.Profile,
        settings: Settings
    ): List<String> {
        require(settings.heapMb >= profile.minimumMemoryMb) {
            "Heap size is below this profile\u0027s minimum"
        }
        require(settings.heapMb <= profile.maximumMemoryMb) {
            "Heap size exceeds this profile\u0027s maximum"
        }

        val builtIn = PerformanceConfig.heapArguments(settings.heapMb)
        val custom = sanitizeCustomArguments(settings.customJvmArguments)

        // Heap settings from the Vortex profile take precedence over custom heap flags.
        val nonHeapCustom = custom.filterNot(::isHeapArgument)

        return builtIn + nonHeapCustom
    }

    /**
     * Removes blank entries and heap flags from custom input.
     * Heap flags are handled centrally to prevent contradictory -Xms/-Xmx values.
     */
    private fun sanitizeCustomArguments(arguments: List<String>): List<String> {
        return arguments
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(::isHeapArgument)
            .filterNot(::containsLineBreak)
    }

    private fun isHeapArgument(argument: String): Boolean {
        val normalized = argument.lowercase()
        return normalized.startsWith("-xms") || normalized.startsWith("-xmx")
    }

    private fun containsLineBreak(argument: String): Boolean {
        return '\n' in argument || '\r' in argument
    }
}
