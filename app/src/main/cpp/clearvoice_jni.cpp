// JNI bridge between Kotlin (com.hasan.clearvoice.RnNoise) and the
// vendored RNNoise library (see rnnoise/VENDORED.md).
//
// RNNoise facts this bridge relies on:
//  - 48 kHz only, mono, frames of exactly 480 samples (10 ms).
//  - rnnoise_process_frame() takes float samples in PCM16 range
//    (-32768..32767), NOT normalised -1..1, and may be run in-place
//    (out == in is allowed by upstream).
//  - rnnoise_create(NULL) uses the model compiled into the library
//    (the vendored "little" model, rnnoise_data_little.c).

#include <jni.h>
#include <cstdint>

extern "C" {
#include "rnnoise.h"
}

namespace {

DenoiseState* FromHandle(jlong handle) {
    return reinterpret_cast<DenoiseState*>(static_cast<intptr_t>(handle));
}

jlong ToHandle(DenoiseState* state) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_hasan_clearvoice_RnNoise_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    DenoiseState* state = rnnoise_create(nullptr);
    return state == nullptr ? 0 : ToHandle(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hasan_clearvoice_RnNoise_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle != 0) {
        rnnoise_destroy(FromHandle(handle));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hasan_clearvoice_RnNoise_nativeFrameSize(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(rnnoise_get_frame_size());
}

// Processes exactly one frame. `frame` must hold at least
// rnnoise_get_frame_size() (480) floats in PCM16 range; it is processed
// in place. Returns the RNNoise voice-activity probability (0..1),
// or -1 on a bad handle / short array.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_hasan_clearvoice_RnNoise_nativeProcessFrame(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                     jfloatArray frame) {
    if (handle == 0) return -1.0f;
    const int frameSize = rnnoise_get_frame_size();
    if (env->GetArrayLength(frame) < frameSize) return -1.0f;

    jfloat* samples = env->GetFloatArrayElements(frame, nullptr);
    if (samples == nullptr) return -1.0f;
    const float vad = rnnoise_process_frame(FromHandle(handle), samples, samples);
    // Mode 0: copy back (processed in place) and release.
    env->ReleaseFloatArrayElements(frame, samples, 0);
    return vad;
}
