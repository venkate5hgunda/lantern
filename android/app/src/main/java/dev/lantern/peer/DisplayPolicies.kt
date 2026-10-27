package dev.lantern.peer

import kotlin.math.abs

/** A low-cost wake cue, never a person/identity or security classification. */
internal class SignificantMotion(
    private val minimumChangedFraction: Double = 0.12,
    private val pixelDelta: Int = 18,
    private val requiredFrames: Int = 2
) {
    private var previous: ByteArray? = null
    private var previousAt = Long.MIN_VALUE
    private var consecutive = 0
    init { require(minimumChangedFraction in 0.0..1.0 && pixelDelta > 0 && requiredFrames > 0) }
    @Synchronized fun reset() { previous = null; consecutive = 0; previousAt = Long.MIN_VALUE }
    @Synchronized fun observe(luma: ByteArray, at: Long): Boolean {
        require(luma.isNotEmpty())
        val baseline = previous
        previous = luma.copyOf()
        val elapsed = if (previousAt == Long.MIN_VALUE) Long.MAX_VALUE else at - previousAt
        previousAt = at
        if (baseline == null || baseline.size != luma.size || elapsed !in 250..5_000) {
            consecutive = 0
            return false
        }
        // Remove the global exposure offset so lights switching alone do not
        // count as substantial movement. This is intentionally not a CV model.
        var offset = 0.0
        for (index in luma.indices) offset += (luma[index].toInt() and 255) - (baseline[index].toInt() and 255)
        offset /= luma.size
        var changed = 0
        for (index in luma.indices) {
            val delta = (luma[index].toInt() and 255) - (baseline[index].toInt() and 255) - offset
            if (abs(delta) >= pixelDelta) changed++
        }
        consecutive = if (changed.toDouble() / luma.size >= minimumChangedFraction) consecutive + 1 else 0
        return consecutive >= requiredFrames
    }
}

internal class AmbientDisplayPolicy(private val idleMs: Long = 30_000) {
    private var interactedAt = 0L
    init { require(idleMs > 0) }
    fun interact(at: Long) { interactedAt = at }
    fun ambient(now: Long, lastSceneActivity: Long): Boolean =
        now - maxOf(interactedAt, lastSceneActivity) >= idleMs
}
