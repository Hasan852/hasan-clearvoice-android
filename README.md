# ClearVoice — Creator Recorder

**by HASAN** · Package: `com.hasan.clearvoice` · Language: **Kotlin (native Android)**

Record clean voice on your phone — fan, traffic and room noise removed while you
record, so your Reels / Shorts / YouTube voiceovers are ready without extra editing.

> Language note: Hasan asked Kotlin vs Java vs TypeScript. The answer is **Kotlin** —
> it is the official native Android language and gives direct, low-latency access to
> `AudioRecord` and the Android audio-effects APIs that real-time noise cancellation
> needs. React Native / TypeScript cannot do this DSP reliably in real time.

## How this is different from Krisp (honest)

ClearVoice is **not** a copy of Krisp and does not use Krisp's code or model.

- Krisp on PC works as a *system-wide virtual microphone* for Zoom/Teams/Meet.
- **Android does not allow a system-wide virtual mic** (without root). That is why
  Krisp's own mobile app has **no noise cancellation at all** — it only records and
  transcribes meetings (Krisp's official help says so).
- ClearVoice does the thing Android *can* do really well, and Krisp mobile does not do:
  **noise cancellation inside a creator recorder** — press Record, get a clean WAV.

Where we aim to be better than Krisp: lightweight, generous/free, natural voice
(not robotic), one-tap, and built for creators and freelancers first.

## Features (v1)

**Recorder — full controls**
- ● **Record** — 48 kHz mono PCM16 via `AudioRecord`
- ⏸ **Pause / Resume recording** — paused time is truly discarded and does not
  count in the timer or the file
- ■ **Stop & Save** — saves a WAV in the app's Music folder
- Live timer (counts recorded samples, not wall-clock pauses) + live level meter

**Noise cancellation — dedicated big ON/OFF switch**
- **AI first — RNNoise:** the proven open-source RNN noise-suppression model
  by Xiph.Org (the same class of technology used by OBS Studio) is vendored
  in `app/src/main/cpp/rnnoise/` and runs natively per 10 ms frame, before
  everything else. This is an honest open-source model — **not** Krisp's
  proprietary model, and not a stub. See `app/src/main/cpp/rnnoise/VENDORED.md`
  for exact sources, the model tarball URL + SHA-256, and local test evidence.
  We ship upstream's smaller/faster **"little" model** variant (right for
  phones; ~2.8 MB compiled weights).
- Android `NoiseSuppressor` + `AcousticEchoCanceler` + `AutomaticGainControl`
  effects attached when the device supports them
- Own DSP as polish on top of the AI output: ~80 Hz high-pass filter,
  adaptive soft-knee noise gate, soft limiter. Strength mapping with AI on:
  **Light = RNNoise only · Medium = RNNoise + light gate · Strong =
  RNNoise + medium gate** — the lighter gate polish is what keeps the
  voice natural instead of over-processed.
- **Automatic fallback (honest):** if the native library cannot load on a
  device, or a file is not 48 kHz (RNNoise is 48 kHz only), processing
  falls back to the DSP-only path automatically. Recording never breaks
  because of the native library; the recording screen shows whether a
  take is running "(RNNoise)" AI or "(DSP)".
- **10 ms response:** audio is processed in 10 ms frames (480 samples @ 48 kHz —
  the same frame size Krisp/RNNoise use), read → clean → write one frame at a
  time, with the smallest AudioRecord buffer the device allows. The DSP adds
  one 10 ms frame of delay; total phone latency also depends on the device's
  own audio buffer, which no app can remove.
- **Natural voice, not robotic:** robotic sound comes from a gate slamming
  per-sample. ClearVoice decides gain once per frame and *ramps* it smoothly,
  uses a soft knee instead of a hard cut, keeps a 200 ms hold so word tails
  are never clipped, and never drops background to dead silence on
  Light/Medium.
- **3 strength levels:** Light / Medium / Strong
- **OFF = raw recording.** Effects and DSP are fully bypassed so you can record
  the same line twice and compare clean vs raw yourself.

**Playback & files — full controls**
- ▶ **Play**, ⏸ **Pause playback**, ■ **Stop playback**
- **Share** (WAV via secure content URI), **Delete with confirmation dialog**
- Saved recordings list with **name, date, duration and file size**
- Play / Share / Delete buttons on every recording in the list

**Import & Clean**
- Pick an existing **WAV** file, the same pipeline (RNNoise AI when the file
  is 48 kHz + gate polish, per channel) cleans it offline, saved as a new
  `Cleaned_*.wav`. (WAV-only in v1 — decoding MP3/M4A is on the roadmap;
  keeping v1 decode-free keeps the build dependency-free and reliable.)

**Premium UI**
- Dark cinematic theme: deep-charcoal gradient background, glass cards
  (24 dp corners, hairline strokes), emerald accent with sparing gold
  highlights, an emerald hero card for the Noise Cancellation switch,
  strength as segmented pills, a big circular record button with a ring,
  a rounded emerald level meter, and recordings as clean cards.

## How to build the APK (GitHub Actions — no PC setup needed)

1. Push this repo to GitHub (branch `main`).
2. Open the repo → **Actions** tab → the **Build ClearVoice APK** workflow runs
   automatically on push (or press **Run workflow** manually).
3. When it is green, open the run → **Artifacts** → download
   **`ClearVoice-debug-apk`** → unzip → you get `app-debug.apk`.

The workflow uses: Java 17 (Temurin), Gradle 8.7 (`gradle/actions/setup-gradle@v4`),
Android SDK (`android-actions/setup-android@v3`), AGP 8.5.2, Kotlin 1.9.24,
compileSdk 34, minSdk 26 — plus, for the RNNoise native library, NDK
**26.1.10909125** and CMake **3.22.1** installed via `sdkmanager` in the
workflow (versions pinned identically in `app/build.gradle.kts`).
ABIs built: `arm64-v8a`, `armeabi-v7a`, `x86_64`.

## How to install on a phone

1. Copy `app-debug.apk` to the phone.
2. Tap it → allow **Install unknown apps** for your Files/browser app if asked.
3. Open **ClearVoice**, allow the microphone permission, press **Record**.

This is a *debug* APK (for testing). A Play Store release needs a signed release
build — that is a later step.

## Project structure

```
app/src/main/java/com/hasan/clearvoice/
  MainActivity.kt       UI, recording (Record/Pause/Stop&Save), playback
                        (Play/Pause/Stop), share, delete, import
  NoiseProcessor.kt     RNNoise AI stage + high-pass + adaptive gate polish
                        + soft limiter; automatic DSP-only fallback
  RnNoise.kt            Kotlin wrapper for the native RNNoise library
  WavFile.kt            PCM16 WAV read / write / header info
  FileShareProvider.kt  Framework-only share provider (no AndroidX needed)
app/src/main/cpp/
  CMakeLists.txt        Native build (NDK 26.1.10909125, CMake 3.22.1)
  clearvoice_jni.cpp    JNI bridge: create / destroy / process 480-float frame
  rnnoise/              Vendored RNNoise v0.2 sources + "little" model data
                        (provenance + license: rnnoise/VENDORED.md, COPYING)
app/src/main/res/       Premium dark XML UI, vector adaptive launcher icon
.github/workflows/      build-apk.yml — builds and uploads the debug APK
```

No Jetpack Compose, no AndroidX — framework-only keeps the app light and the
CI build reliable.

## Roadmap

- **v2:** Video recording (camera + clean audio in one take) for creators
- **v2:** MP3/M4A import decoding, waveform view, trim/silence-cut
- **v3:** Evaluate DeepFilterNet as an alternative AI model, signed
  release build for Play Store
- ~~**v3:** RNNoise AI via NDK~~ — **done** (see AI section above)
