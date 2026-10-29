cmake_minimum_required(VERSION 3.22.1)
project("clearvoice")

# clearvoice_rnnoise: JNI bridge + vendored RNNoise (see rnnoise/VENDORED.md).
# Only the generic C path is compiled (NEON is picked up automatically on
# ARM via rnnoise/src/vec.h); the x86 RTCD sources shipped in rnnoise/src/x86/
# are intentionally NOT built — generic C keeps one code path for
# arm64-v8a, armeabi-v7a and x86_64.
add_library(
        clearvoice_rnnoise
        SHARED
        clearvoice_jni.cpp
        rnnoise/src/celt_lpc.c
        rnnoise/src/denoise.c
        rnnoise/src/kiss_fft.c
        rnnoise/src/nnet.c
        rnnoise/src/nnet_default.c
        rnnoise/src/parse_lpcnet_weights.c
        rnnoise/src/pitch.c
        rnnoise/src/rnn.c
        rnnoise/src/rnnoise_data_little.c
        rnnoise/src/rnnoise_tables.c)

target_include_directories(
        clearvoice_rnnoise
        PRIVATE
        rnnoise/include
        rnnoise/src)

# -O2 keeps the ~15 MB model-data translation unit fast to compile;
# -w silences upstream warnings in vendored code only.
target_compile_options(clearvoice_rnnoise PRIVATE -O2 -w)

find_library(log-lib log)

target_link_libraries(
        clearvoice_rnnoise
        ${log-lib}
        m)
