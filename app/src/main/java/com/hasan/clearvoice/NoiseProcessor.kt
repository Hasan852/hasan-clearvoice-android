package com.hasan.clearvoice

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time / offline DSP for ClearVoice — designed for two hard targets
 * set by Hasan:
 *
 *  1. 10 ms response: audio is processed in 10 ms frames
 *     (480 samples @ 48 kHz, sampleRate/100 in general). One frame in,
 *     one frame out — the processing itself adds a single 10 ms frame
 *     of algorithmic delay, the same frame size Krisp/RNNoise use.
 *     Note honestly: total phone latency also includes the Android
 *     AudioRecord buffer, which the app keeps as small as the device
 *     allows; the DSP never adds more than its 10 ms frame.
 *
 *  2. NO robotic voice: robotic/choppy sound comes from a gate that
 *     slams open/shut sample-by-sample. This processor never does that:
 *       - Gain is decided ONCE per 10 ms frame, then RAMPED smoothly
 *         across the whole frame (no clicks, no pumping).
 *       - Soft knee: between "clearly speech" and "clearly noise" the
 *         gain slides gradually instead of switching.
 *       - 200 ms hold: after speech stops the gate stays open for
 *         20 frames, so word tails and quiet syllables are not clipped.
 *       - Gentle floors: even at MEDIUM the "closed" gain is 0.22, not
 *         near-zero — background gets quieter, never unnaturally dead.
 *         Dead-silence gaps are the main thing ears hear as "robotic".
 *       - No aggressive boost/normalisation while speaking.
 *
 * Pipeline:
 *  1. Single-pole high-pass (~80 Hz) — rumble / handling / fan hum.
 *  2. Frame-based adaptive soft gate (above).
 *  3. tanh soft limiter above 0.85 — peaks never hard-clip.
 *
 * When [enabled] is false the processor is a strict pass-through:
 * recording is RAW — intentional, for a true ON/OFF comparison.
 */
class NoiseProcessor(
    private val sampleRate: Int,
    var strength: Strength = Strength.MEDIUM,
    var enabled: Boolean = true
) {
    enum class Strength {
        LIGHT, MEDIUM, STRONG
    }

    /** 10 ms frame: 480 samples @ 48 kHz. */
    private val frameSamples: Int = (sampleRate / 100).coerceAtLeast(1)

    // High-pass state
    private var hpPrevInput = 0f
    private var hpPrevOutput = 0f
    private val hpAlpha: Float

    // Gate state
    private var noiseFloor = 0.004f
    private var currentGain = 1f
    private var holdFrames = 0

    // Scratch buffer for one frame of high-passed audio
    private var scratch = FloatArray(frameSamples)

    init {
        val rc = 1f / (2f * Math.PI.toFloat() * 80f)
        val dt = 1f / sampleRate.toFloat()
        hpAlpha = rc / (rc + dt)
    }

    fun reset() {
        hpPrevInput = 0f
        hpPrevOutput = 0f
        noiseFloor = 0.004f
        currentGain = 1f
        holdFrames = 0
    }

    private fun thresholdMultiplier(): Float = when (strength) {
        Strength.LIGHT -> 2.0f
        Strength.MEDIUM -> 3.0f
        Strength.STRONG -> 4.5f
    }

    private fun minFloor(): Float = when (strength) {
        Strength.LIGHT -> 0.002f
        Strength.MEDIUM -> 0.003f
        Strength.STRONG -> 0.005f
    }

    /** Gentlest floor that still reads as "clean" — never near-zero except STRONG. */
    private fun closedGain(): Float = when (strength) {
        Strength.LIGHT -> 0.45f
        Strength.MEDIUM -> 0.22f
        Strength.STRONG -> 0.08f
    }

    /**
     * Process a buffer in place in 10 ms frames; returns peak 0..1.
     * Works for any count: the buffer is walked frame by frame and a
     * trailing partial frame is processed as its own (shorter) frame.
     */
    fun process(buffer: ShortArray, count: Int): Float {
        if (!enabled) {
            var peak = 0f
            var i = 0
            while (i < count) {
                val a = abs(buffer[i].toInt()) / 32768f
                if (a > peak) peak = a
                i++
            }
            return peak
        }
        var peak = 0f
        var offset = 0
        while (offset < count) {
            val n = minOf(frameSamples, count - offset)
            val framePeak = processFrame(buffer, offset, n)
            if (framePeak > peak) peak = framePeak
            offset += n
        }
        return peak
    }

    private fun processFrame(buffer: ShortArray, offset: Int, n: Int): Float {
        if (scratch.size < n) scratch = FloatArray(n)

        // Pass 1: high-pass + frame RMS.
        var sumSq = 0f
        var i = 0
        while (i < n) {
            val x = buffer[offset + i].toFloat() / 32768f
            val hp = hpAlpha * (hpPrevOutput + x - hpPrevInput)
            hpPrevInput = x
            hpPrevOutput = hp
            scratch[i] = hp
            sumSq += hp * hp
            i++
        }
        val rms = sqrt(sumSq / n.toFloat())

        // Noise floor learning: only from frames well below speech level,
        // and very slowly, so speech never teaches the floor to eat speech.
        if (rms < noiseFloor * 2.0f) {
            noiseFloor = 0.995f * noiseFloor + 0.005f * rms
            if (noiseFloor < minFloor()) noiseFloor = minFloor()
        }

        // Soft-knee target gain.
        val low = noiseFloor * thresholdMultiplier()          // below: noise
        val high = low * 2.5f                                  // above: speech
        val closed = closedGain()
        var target = when {
            rms >= high -> 1f
            rms <= low -> closed
            else -> {
                val t = (rms - low) / (high - low)
                closed + (1f - closed) * t * t * (3f - 2f * t) // smoothstep
            }
        }

        // 200 ms hold (20 frames): keep the gate open after speech so
        // word tails / soft endings are never chopped.
        if (rms >= low) {
            holdFrames = 20
        } else if (holdFrames > 0) {
            holdFrames--
            if (target < currentGain) target = currentGain.coerceAtMost(1f)
            if (holdFrames == 0) target = closed
            else if (target < 1f && rms > low * 0.7f) target = 1f
        }

        // Pass 2: apply gain RAMPED from currentGain to target across
        // the frame + soft limiter. Ramping is the anti-robotic key.
        val startGain = currentGain
        var peak = 0f
        i = 0
        while (i < n) {
            val t = if (n > 1) i.toFloat() / (n - 1).toFloat() else 1f
            val gain = startGain + (target - startGain) * t
            var y = scratch[i] * gain
            val absY = abs(y)
            y = if (absY > 0.85f) {
                val sign = if (y < 0f) -1f else 1f
                sign * (0.85f + 0.15f * tanh((absY - 0.85f) / 0.15f))
            } else {
                y
            }
            val out = (y * 32767f).toInt().coerceIn(-32768, 32767)
            buffer[offset + i] = out.toShort()
            val a = abs(out) / 32768f
            if (a > peak) peak = a
            i++
        }
        currentGain = target
        return peak
    }

    companion object {
        /** Samples in one 10 ms frame at [sampleRate] (480 @ 48 kHz). */
        fun frameSamplesFor(sampleRate: Int): Int = (sampleRate / 100).coerceAtLeast(1)

        /** Decay helper kept for future tuning. */
        fun smoothingFactor(timeConstantSeconds: Float, sampleRate: Int): Float {
            return exp(-1f / (timeConstantSeconds * sampleRate))
        }
    }
}
