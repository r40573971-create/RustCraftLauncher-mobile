package dev.rustcraft.launcher

/**
 * Validates a Vortex launch profile before the launcher attempts to start Java.
 * Validation is deliberately conservative; loader-specific checks will be added
 * when the launch pipeline supports those loaders.
 */
object LaunchProfileValidator {

    enum class Severity { ERROR, WARNING }

    data class Issue(
        val severity: Severity,
        val message: String
    )

    data class Input(
        val minecraftVersion: String,
        val javaMajor: Int,
        val heapMb: Int,
        val customJvmArguments: List<String> = emptyList()
    )

    fun validate(input: Input): List<Issue> {
        val issues = mutableListOf<Issue>()

        val profile = try {
            PerformanceConfig.profileFor(input.minecraftVersion)
        } catch (e: IllegalArgumentException) {
            issues += Issue(Severity.ERROR, "Invalid Minecraft version.")
            return issues
        }

        if (input.javaMajor != profile.javaMajor) {
            issues += Issue(
                Severity.ERROR,
                "Java ${profile.javaMajor} is expected for this Minecraft version."
            )
        }

        if (input.heapMb < profile.minimumMemoryMb) {
            issues += Issue(Severity.ERROR, "Heap size is below the profile minimum.")
        }

        if (input.heapMb > profile.maximumMemoryMb) {
            issues += Issue(Severity.WARNING, "Heap size exceeds the profile maximum.")
        }

        input.customJvmArguments.forEach { argument ->
            val trimmed = argument.trim()
            if (trimmed.startsWith("-Xms", ignoreCase = true) ||
                trimmed.startsWith("-Xmx", ignoreCase = true)
            ) {
                issues += Issue(
                    Severity.WARNING,
                    "Custom heap flags are ignored; Vortex controls heap size."
                )
                return@forEach
            }

            if ('\n' in trimmed || '\r' in trimmed) {
                issues += Issue(Severity.ERROR, "A custom JVM argument contains a line break.")
            }
        }

        return issues.distinct()
    }

    fun canLaunch(issues: List<Issue>): Boolean {
        return issues.none { it.severity == Severity.ERROR }
    }
}
