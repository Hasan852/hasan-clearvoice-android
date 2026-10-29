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
 *  2. AI noise suppression (when a session is supplied): RNNoise — the
 *     open-source RNN model (Xiph.Org, also used by OBS) — runs FIRST,
 *     per 10 ms frame. See [RnNoise] / cpp/rnnoise/VENDORED.md. It is
 *     an honest open-source model, not Krisp's proprietary one.
 *  3. Frame-based adaptive soft gate as a gentle polish on top of the
 *     AI output (the gate design above is what keeps the voice natural).
 *  4. tanh soft limiter above 0.85 — peaks never hard-clip.
 *
 * Strength mapping with AI active (gate = polish only):
 *   LIGHT  -> RNNoise only, no gate
 *   MEDIUM -> RNNoise + LIGHT gate
 *   STRONG -> RNNoise + MEDIUM gate
 * Without AI (native library unavailable, or sample rate != 48 kHz —
 * RNNoise is 48 kHz only), the gate runs alone at the chosen strength,
 * exactly as in v1. The fallback is automatic and recording never
 * breaks because of the native library.
 *
 * When [enabled] is false the processor is a strict pass-through:
 * recording is RAW — intentional, for a true ON/OFF comparison.
 */
class NoiseProcessor(
    private val sampleRate: Int,
    var strength: Strength = Strength.MEDIUM,
    var enabled: Boolean = true,
    aiSession: RnNoise? = null
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

    // AI (RNNoise) session. 48 kHz only — RNNoise has exactly one rate.
    // Set to null automatically if the native side ever fails mid-stream.
    private var rn: RnNoise? = if (sampleRate == 48000) aiSession else null

    /** True while the RNNoise AI stage is live for this processor. */
    val usingAi: Boolean
        get() = rn != null

    /** Padded 480-float frame handed to RNNoise (partial frames are zero-padded). */
    private val aiFrame = FloatArray(RnNoise.FRAME_SIZE)

    init {
        // RNNoise is 48 kHz only: if a session was handed over for another
        // rate it can never be used here — close it instead of leaking the
        // native state.
        if (sampleRate != 48000) aiSession?.close()
    }

    /** Release the RNNoise session (call when recording / cleaning finishes). */
    fun close() {
        rn?.close()
        rn = null
    }

    /**
     * Effective gate level after the AI strength mapping:
     * null = gate fully off (AI LIGHT), otherwise the polish strength.
     */
    private fun gateLevel(): Strength? {
        if (rn == null) return strength // DSP-only: gate at chosen strength
        return when (strength) {
            Strength.LIGHT -> null
            Strength.MEDIUM -> Strength.LIGHT
            Strength.STRONG -> Strength.MEDIUM
        }
    }

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

    private fun thresholdMultiplier(level: Strength): Float = when (level) {
        Strength.LIGHT -> 2.0f
        Strength.MEDIUM -> 3.0f
        Strength.STRONG -> 4.5f
    }

    private fun minFloor(level: Strength): Float = when (level) {
        Strength.LIGHT -> 0.002f
        Strength.MEDIUM -> 0.003f
        Strength.STRONG -> 0.005f
    }

    /** Gentlest floor that still reads as "clean" — never near-zero except STRONG. */
    private fun closedGain(level: Strength): Float = when (level) {
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

        // Pass 1: high-pass into scratch (normalised -1..1).
        var i = 0
        while (i < n) {
            val x = buffer[offset + i].toFloat() / 32768f
            val hp = hpAlpha * (hpPrevOutput + x - hpPrevInput)
            hpPrevInput = x
            hpPrevOutput = hp
            scratch[i] = hp
            i++
        }

        // AI stage: RNNoise runs on the high-passed frame FIRST (it wants
        // floats in PCM16 range). Any native failure drops us to DSP-only
        // for the rest of this session — never breaks the recording.
        val session = rn
        if (session != null) {
            aiFrame.fill(0f)
            i = 0
            while (i < n) {
                aiFrame[i] = scratch[i] * 32768f
                i++
            }
            if (session.processFrame(aiFrame)) {
                i = 0
                while (i < n) {
                    scratch[i] = aiFrame[i] / 32768f
                    i++
                }
            } else {
                session.close()
                rn = null
            }
        }

        // Frame RMS of the (possibly AI-cleaned) audio.
        var sumSq = 0f
        i = 0
        while (i < n) {
            sumSq += scratch[i] * scratch[i]
            i++
        }
        val rms = sqrt(sumSq / n.toFloat())

        val level = gateLevel()
        if (level == null) {
            // AI + LIGHT: no gate polish at all. Just the soft limiter.
            return applyGainRamp(buffer, offset, n, 1f, 1f)
        }

        // Noise floor learning: only from frames well below speech level,
        // and very slowly, so speech never teaches the floor to eat speech.
        if (rms < noiseFloor * 2.0f) {
            noiseFloor = 0.995f * noiseFloor + 0.005f * rms
            if (noiseFloor < minFloor(level)) noiseFloor = minFloor(level)
        }

        // Soft-knee target gain.
        val low = noiseFloor * thresholdMultiplier(level)      // below: noise
        val high = low * 2.5f                                  // above: speech
        val closed = closedGain(level)
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
        val peak = applyGainRamp(buffer, offset, n, currentGain, target)
        currentGain = target
        return peak
    }

    /** Write scratch back to [buffer] with gain ramped start->end + soft limiter. */
    private fun applyGainRamp(buffer: ShortArray, offset: Int, n: Int, startGain: Float, endGain: Float): Float {
        var peak = 0f
        var i = 0
        while (i < n) {
            val t = if (n > 1) i.toFloat() / (n - 1).toFloat() else 1f
            val gain = startGain + (endGain - startGain) * t
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
