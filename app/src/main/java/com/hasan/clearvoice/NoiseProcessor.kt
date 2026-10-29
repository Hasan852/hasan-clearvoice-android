package com.hasan.clearvoice

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * ClearVoice audio engine (v2) — real-time and offline processing.
 *
 * Pipeline per 10 ms frame (480 samples @ 48 kHz):
 *  1. High-pass filter (rumble / wind). Cutoff depends on mode;
 *     the Wind Filter setting raises it to ~180 Hz.
 *  2. AI noise suppression: RNNoise (open-source RNN model by Xiph.Org,
 *     also used by OBS) — skipped entirely in MUSIC mode, because an
 *     RNN speech model damages music; music gets the gentle DSP only.
 *  3. Keyboard/click suppression (when enabled): isolated short spikes
 *     far above their neighbours are pulled down — typing/click sounds.
 *  4. Adaptive soft gate whose strength comes from the Noise Reduction
 *     Level (Low/Medium/High/Max) adjusted per mode:
 *       AUTO        -> level as-is
 *       VOICE_FOCUS -> one step gentler (clearer speech, less pumping)
 *       DEEP_NOISE  -> one step stronger (maximum suppression)
 *       MUSIC       -> fixed very gentle, AI off
 *     The gate never slams: gain is decided once per frame and RAMPED
 *     across the frame, with a 200 ms hold so word tails survive —
 *     this is the anti-robotic design.
 *  5. tanh soft limiter above 0.85 — peaks never hard-clip.
 *
 * When [enabled] is false everything is a strict pass-through (RAW).
 * If the RNNoise session fails mid-stream, processing falls back to
 * DSP-only automatically — audio never breaks because of the AI stage.
 */
class NoiseProcessor(
    private val sampleRate: Int,
    private val mode: NoiseMode = NoiseMode.AUTO,
    private val level: Int = 1,
    var enabled: Boolean = true,
    aiSession: RnNoise? = null,
    private val windFilter: Boolean = false,
    private val keyboardFilter: Boolean = true
) {
    private val frameSamples: Int = (sampleRate / 100).coerceAtLeast(1)

    // High-pass state
    private var hpPrevInput = 0f
    private var hpPrevOutput = 0f
    private val hpAlpha: Float

    // Gate state
    private var noiseFloor = 0.004f
    private var currentGain = 1f
    private var holdFrames = 0

    private var scratch = FloatArray(frameSamples)

    // AI session (48 kHz only; never used in MUSIC mode)
    private var rn: RnNoise? = if (sampleRate == 48000 && mode != NoiseMode.MUSIC) aiSession else null

    val usingAi: Boolean
        get() = rn != null

    private val aiFrame = FloatArray(RnNoise.FRAME_SIZE)

    init {
        if (rn == null) aiSession?.close() // unusable here — don't leak native state
        val cutoff = when {
            windFilter -> 180f
            mode == NoiseMode.VOICE_FOCUS -> 120f
            mode == NoiseMode.DEEP_NOISE -> 100f
            mode == NoiseMode.MUSIC -> 40f
            else -> 80f
        }
        val rc = 1f / (2f * Math.PI.toFloat() * cutoff)
        val dt = 1f / sampleRate.toFloat()
        hpAlpha = rc / (rc + dt)
    }

    fun close() {
        rn?.close()
        rn = null
    }

    /** Effective gate step 0..3 after the per-mode adjustment. */
    private fun gateStep(): Int {
        if (mode == NoiseMode.MUSIC) return 0
        val adj = when (mode) {
            NoiseMode.VOICE_FOCUS -> level - 1
            NoiseMode.DEEP_NOISE -> level + 1
            else -> level
        }
        return adj.coerceIn(0, 3)
    }

    private fun thresholdMultiplier(step: Int): Float =
        floatArrayOf(2.2f, 3.0f, 3.8f, 4.6f)[step]

    private fun minFloor(step: Int): Float =
        floatArrayOf(0.0025f, 0.003f, 0.004f, 0.005f)[step]

    /** Gentlest floor that still reads as "clean" — never near-zero except Max. */
    private fun closedGain(step: Int): Float =
        if (mode == NoiseMode.MUSIC) 0.60f
        else floatArrayOf(0.40f, 0.26f, 0.15f, 0.06f)[step]

    /**
     * Process [count] samples of [buffer] in place, in 10 ms frames.
     * Returns the peak (0..1) of the processed audio.
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

        // 1) High-pass into scratch (normalised -1..1).
        var i = 0
        while (i < n) {
            val x = buffer[offset + i].toFloat() / 32768f
            val hp = hpAlpha * (hpPrevOutput + x - hpPrevInput)
            hpPrevInput = x
            hpPrevOutput = hp
            scratch[i] = hp
            i++
        }

        // 2) AI stage on the high-passed frame (PCM16-range floats).
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

        // 3) Keyboard/click suppression: lone spikes get pulled down.
        if (keyboardFilter) {
            i = 1
            while (i < n - 1) {
                val a = abs(scratch[i])
                if (a > 0.45f && a > 3.5f * abs(scratch[i - 1]) && a > 3.5f * abs(scratch[i + 1])) {
                    scratch[i] *= 0.25f
                }
                i++
            }
        }

        // Frame RMS after cleaning.
        var sumSq = 0f
        i = 0
        while (i < n) {
            sumSq += scratch[i] * scratch[i]
            i++
        }
        val rms = sqrt(sumSq / n.toFloat())
        val step = gateStep()

        // Noise floor learning: slow, and only from quiet frames.
        if (rms < noiseFloor * 2.0f) {
            noiseFloor = 0.995f * noiseFloor + 0.005f * rms
            if (noiseFloor < minFloor(step)) noiseFloor = minFloor(step)
        }

        // Soft-knee target gain.
        val low = noiseFloor * thresholdMultiplier(step)
        val high = low * 2.5f
        val closed = closedGain(step)
        var target = when {
            rms >= high -> 1f
            rms <= low -> closed
            else -> {
                val t = (rms - low) / (high - low)
                closed + (1f - closed) * t * t * (3f - 2f * t) // smoothstep
            }
        }

        // 200 ms hold: keep the gate open after speech.
        if (rms >= low) {
            holdFrames = 20
        } else if (holdFrames > 0) {
            holdFrames--
            if (target < currentGain) target = currentGain.coerceAtMost(1f)
            if (holdFrames == 0) target = closed
            else if (target < 1f && rms > low * 0.7f) target = 1f
        }

        val peak = applyGainRamp(buffer, offset, n, currentGain, target)
        currentGain = target
        return peak
    }

    /** Write scratch back with gain RAMPED start->end + soft limiter. */
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
    }
}
