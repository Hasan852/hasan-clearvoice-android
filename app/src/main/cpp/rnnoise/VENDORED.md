# Vendored RNNoise — provenance

RNNoise is **not** written by us and is **not** Krisp's model. It is the
open-source RNN noise-suppression library by Jean-Marc Valin / Xiph.Org
Foundation (BSD-style license, see `COPYING` in this folder) — the same
class of technology used by OBS Studio and others.

## What was vendored, and from where

- **Source:** official GitHub mirror `https://github.com/xiph/rnnoise`,
  tag `v0.2` (commit `904a876`, "Add little model").
  - `include/rnnoise.h`
  - `src/*.c` / `src/*.h` needed for the generic + NEON build
    (`denoise.c`, `rnn.c`, `pitch.c`, `kiss_fft.c`, `celt_lpc.c`,
    `nnet.c`, `nnet_default.c`, `parse_lpcnet_weights.c`,
    `rnnoise_tables.c` and their headers, plus `src/x86/` headers,
    which `vec.h` includes when building for x86_64).
  - The x86 runtime-CPU-dispatch sources (`nnet_sse4_1.c`, `nnet_avx2.c`,
    `x86cpu.c`, `x86_dnn_map.c`) are present for completeness of the
    vendored tree but are **not** compiled: the build uses the generic C
    path everywhere (NEON is used automatically on ARM via `vec.h`).
- **Model data:** upstream does not keep model weights in git. The file
  `src/rnnoise_data_little.c` (+ `src/rnnoise_data.h`) comes from the
  official model tarball that upstream's own `download_model.sh`
  fetches:
  - URL: `https://media.xiph.org/rnnoise/models/rnnoise_data-0b50c45.tar.gz`
  - Tarball SHA-256: `4ac81c5c0884ec4bd5907026aaae16209b7b76cd9d7f71af582094a2f98f4b43`
  - We ship the **"little" model** variant from that tarball — the
    smaller/faster model upstream added for lower-power devices. It is
    the right fit for phones: ~2.8 MB of compiled weights instead of
    ~4× that for the full model, same 48 kHz / 10 ms (480-sample) frames.

## Local verification (before vendoring)

All vendored C sources were compiled on the build host (gcc, x86_64,
`-O2 -fPIC`), linked into a shared library, and a test program ran
`rnnoise_create(NULL)` + `rnnoise_process_frame()` successfully
(frame size 480, VAD probability returned). The Android build compiles
the exact same files via `../CMakeLists.txt`.
