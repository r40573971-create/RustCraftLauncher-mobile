package dev.rustcraft.launcher

import android.content.Context

/**
 * Integrates the performance configuration, memory recommendation,
 * validation and JVM argument builder into one preparation step.
 *
 * This does not start Minecraft yet; it returns validated JVM arguments
 * for a future launch adapter.
 */
object LaunchPreparation {

    data class PreparedLaunch(
        val minecraftVersion: String,
        val javaMajor: Int,
        val heapMb: Int,
        val jvmArguments: List<String>,
        val warnings: List<String>
    )

    sealed class Result {
        data class Ready(val launch: PreparedLaunch) : Result()
        data class Rejected(val errors: List<String>, val warnings: List<String>) : Result()
    }

    fun prepare(
        context: Context,
        minecraftVersion: String,
        customJvmArguments: List<String> = emptyList(),
        requestedHeapMb: Int? = null
    ): Result {
        val profile = try {
            PerformanceConfig.profileFor(minecraftVersion)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected(listOf("Unsupported Minecraft version."), emptyList())
        }

        val memory = MemoryAdvisor.recommend(context, minecraftVersion)
        val heap = requestedHeapMb?.let {
            MemoryAdvisor.clampUserHeap(memory, it)
        } ?: memory.recommendedHeapMb

        val validation = LaunchProfileValidator.validate(
            LaunchProfileValidator.Input(
                minecraftVersion = minecraftVersion,
                javaMajor = profile.javaMajor,
                heapMb = heap,
                customJvmArguments = customJvmArguments
            )
        )

        val errors = validation
            .filter { it.severity == LaunchProfileValidator.Severity.ERROR }
            .map { it.message }

        val warnings = validation
            .filter { it.severity == LaunchProfileValidator.Severity.WARNING }
            .map { it.message }

        if (errors.isNotEmpty()) {
            return Result.Rejected(errors, warnings)
        }

        val args = LaunchArgumentsBuilder.build(
            profile = profile,
            settings = LaunchArgumentsBuilder.Settings(
                heapMb = heap,
                customJvmArguments = customJvmArguments
            )
        )

        return Result.Ready(
            PreparedLaunch(
                minecraftVersion = minecraftVersion,
                javaMajor = profile.javaMajor,
                heapMb = heap,
                jvmArguments = args,
                warnings = warnings
            )
        )
    }
}
