package com.hasan.clearvoice

/**
 * Kotlin wrapper around the vendored RNNoise AI noise-suppression model,
 * reached through the JNI bridge in `cpp/clearvoice_jni.cpp`.
 *
 * RNNoise is the open-source RNN model by Xiph.Org (also used by OBS) —
 * it is NOT Krisp's proprietary model. It works at 48 kHz in 10 ms
 * frames of 480 float samples in PCM16 range.
 *
 * Failure policy (important): if the native library cannot be loaded on
 * a device (wrong ABI, packaging problem, ...), [isAvailable] is false,
 * [create] returns null, and callers fall back to the pure-Kotlin DSP
 * path in [NoiseProcessor]. Recording must never break because of the
 * native library.
 */
class RnNoise private constructor(private var handle: Long) {

    /** Most recent voice-activity probability from RNNoise (0..1), -1 if unknown. */
    var lastVad: Float = -1f
        private set

    /**
     * Process one 10 ms frame in place. [frame] must contain at least
     * [FRAME_SIZE] floats in PCM16 range (-32768..32767).
     * Returns false if the native call failed (caller should fall back).
     */
    fun processFrame(frame: FloatArray): Boolean {
        if (handle == 0L || frame.size < FRAME_SIZE) return false
        val vad = try {
            nativeProcessFrame(handle, frame)
        } catch (t: Throwable) {
            -1f
        }
        if (vad < 0f) return false
        lastVad = vad
        return true
    }

    fun close() {
        if (handle != 0L) {
            try {
                nativeDestroy(handle)
            } catch (t: Throwable) {
                // Ignore — destroying must never crash the app.
            }
            handle = 0L
        }
    }

    // Instance external methods (not companion) so the JNI names are the
    // simple Java_com_hasan_clearvoice_RnNoise_* forms.
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeFrameSize(): Int
    private external fun nativeProcessFrame(handle: Long, frame: FloatArray): Float

    companion object {
        /** RNNoise frame size: 480 samples = 10 ms @ 48 kHz. */
        const val FRAME_SIZE = 480

        /** True only if the native library loaded AND a state was created once. */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("clearvoice_rnnoise")
                true
            } catch (t: Throwable) {
                false
            }
        }

        /**
         * Create a new RNNoise session, or null when the AI path is not
         * usable on this device (caller then uses DSP-only processing).
         */
        fun create(): RnNoise? {
            if (!isAvailable) return null
            return try {
                val probe = RnNoise(0L)
                val handle = probe.nativeCreate()
                if (handle == 0L || probe.nativeFrameSize() != FRAME_SIZE) {
                    if (handle != 0L) probe.nativeDestroy(handle)
                    null
                } else {
                    RnNoise(handle)
                }
            } catch (t: Throwable) {
                null
            }
        }
    }
}
