package com.hasan.clearvoice

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Real-time / offline DSP for ClearVoice.
 *
 * Pipeline per sample (when enabled):
 *  1. Single-pole high-pass filter (~80 Hz) — removes rumble, handling noise, fan hum.
 *  2. Adaptive noise gate — tracks a slow noise-floor estimate and smoothly
 *     attenuates samples that sit near/below that floor. Strength changes the
 *     threshold multiplier, the floor minimum and the closed-gain.
 *  3. Soft limiter — tanh soft-clipping above 0.85 so loud peaks do not hard-clip.
 *
 * When [enabled] is false the processor is a strict pass-through: recording is
 * RAW. That is intentional — Hasan asked for a true ON/OFF comparison.
 */
class NoiseProcessor(
    private val sampleRate: Int,
    var strength: Strength = Strength.MEDIUM,
    var enabled: Boolean = true
) {
    enum class Strength {
        LIGHT, MEDIUM, STRONG
    }

    // High-pass state
    private var hpPrevInput = 0f
    private var hpPrevOutput = 0f
    private val hpAlpha: Float

    // Gate state
    private var noiseFloor = 0.004f
    private var gateGain = 1f
    private var envelope = 0f

    init {
        val rc = 1f / (2f * Math.PI.toFloat() * 80f)
        val dt = 1f / sampleRate.toFloat()
        hpAlpha = rc / (rc + dt)
    }

    fun reset() {
        hpPrevInput = 0f
        hpPrevOutput = 0f
        noiseFloor = 0.004f
        gateGain = 1f
        envelope = 0f
    }

    private fun thresholdMultiplier(): Float = when (strength) {
        Strength.LIGHT -> 2.2f
        Strength.MEDIUM -> 3.5f
        Strength.STRONG -> 5.5f
    }

    private fun minFloor(): Float = when (strength) {
        Strength.LIGHT -> 0.002f
        Strength.MEDIUM -> 0.004f
        Strength.STRONG -> 0.007f
    }

    private fun closedGain(): Float = when (strength) {
        Strength.LIGHT -> 0.35f
        Strength.MEDIUM -> 0.12f
        Strength.STRONG -> 0.03f
    }

    /** Process one PCM16 sample, returns a PCM16 sample. */
    fun processSample(input: Short): Short {
        if (!enabled) return input
        val x = input.toFloat() / 32768f

        // 1) High-pass
        val hp = hpAlpha * (hpPrevOutput + x - hpPrevInput)
        hpPrevInput = x
        hpPrevOutput = hp

        val magnitude = abs(hp)

        // Envelope follower (fast attack, slow release)
        envelope = if (magnitude > envelope) {
            0.5f * envelope + 0.5f * magnitude
        } else {
            0.995f * envelope + 0.005f * magnitude
        }

        // 2) Adaptive noise floor: only learn downwards / from quiet frames so
        // speech does not push the floor up and gate itself out.
        if (magnitude < noiseFloor * 2.5f) {
            noiseFloor = 0.999f * noiseFloor + 0.001f * magnitude
            if (noiseFloor < minFloor()) noiseFloor = minFloor()
        }

        val threshold = noiseFloor * thresholdMultiplier()
        val targetGain = if (envelope > threshold) 1f else closedGain()
        // Smooth gain moves to avoid clicks / pumping: open fast, close slower.
        gateGain = if (targetGain > gateGain) {
            gateGain + (targetGain - gateGain) * 0.35f
        } else {
            gateGain + (targetGain - gateGain) * 0.06f
        }

        var y = hp * gateGain

        // 3) Soft limiter / gentle normalisation for quiet-but-clean takes.
        val absY = abs(y)
        if (absY > 0.001f && absY < 0.35f && gateGain > 0.9f) {
            y *= 1.25f
        }
        val clipped = if (abs(y) > 0.85f) {
            val sign = if (y < 0f) -1f else 1f
            sign * (0.85f + 0.15f * tanh((abs(y) - 0.85f) / 0.15f))
        } else {
            y
        }

        val out = (clipped * 32767f).toInt().coerceIn(-32768, 32767)
        return out.toShort()
    }

    /** Process a buffer in place; returns peak amplitude 0..1 for the level meter. */
    fun process(buffer: ShortArray, count: Int): Float {
        var peak = 0f
        var i = 0
        while (i < count) {
            val processed = processSample(buffer[i])
            buffer[i] = processed
            val a = abs(processed.toInt()) / 32768f
            if (a > peak) peak = a
            i++
        }
        return peak
    }

    companion object {
        /** Decay helper kept for future tuning; exp smoothing factor for a time constant. */
        fun smoothingFactor(timeConstantSeconds: Float, sampleRate: Int): Float {
            return exp(-1f / (timeConstantSeconds * sampleRate))
        }
    }
}
