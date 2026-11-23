package dev.rustcraft.launcher

import android.app.ActivityManager
import android.content.Context

/**
 * Estimates a safe Minecraft heap size while reserving memory for Android.
 * Estimates are heuristics and must be tested across real devices.
 */
object MemoryAdvisor {

    data class Recommendation(
        val totalRamMb: Int,
        val recommendedHeapMb: Int,
        val minimumHeapMb: Int,
        val maximumHeapMb: Int
    )

    fun recommend(context: Context, minecraftVersion: String): Recommendation {
        val profile = PerformanceConfig.profileFor(minecraftVersion)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)

        val totalRamMb = (info.totalMem / (1024L * 1024L))
            .coerceAtLeast(512L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        val deviceLimitMb = (totalRamMb * 0.60).toInt()
        val limit = minOf(deviceLimitMb, profile.maximumMemoryMb)
        val minimum = minOf(profile.minimumMemoryMb, limit)
        val recommended = profile.recommendedMemoryMb
            .coerceAtMost(limit)
            .coerceAtLeast(minimum)

        return Recommendation(
            totalRamMb = totalRamMb,
            recommendedHeapMb = recommended,
            minimumHeapMb = minimum,
            maximumHeapMb = limit
        )
    }

    /**
     * Applies an optional user selection, clamped to the recommendation limits.
     */
    fun clampUserHeap(
        recommendation: Recommendation,
        requestedHeapMb: Int
    ): Int {
        require(requestedHeapMb > 0) { "Heap size must be positive" }
        return requestedHeapMb.coerceIn(
            recommendation.minimumHeapMb,
            recommendation.maximumHeapMb
        )
    }
}
