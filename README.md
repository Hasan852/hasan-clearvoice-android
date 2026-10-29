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
- Android `NoiseSuppressor` + `AcousticEchoCanceler` + `AutomaticGainControl`
  effects attached when the device supports them
- Own DSP on top: ~80 Hz high-pass filter, adaptive noise gate, soft limiter
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
- Pick an existing **WAV** file, the same DSP cleans it offline, saved as a new
  `Cleaned_*.wav`. (WAV-only in v1 — decoding MP3/M4A is on the roadmap; keeping
  v1 decode-free keeps the build dependency-free and reliable.)

## How to build the APK (GitHub Actions — no PC setup needed)

1. Push this repo to GitHub (branch `main`).
2. Open the repo → **Actions** tab → the **Build ClearVoice APK** workflow runs
   automatically on push (or press **Run workflow** manually).
3. When it is green, open the run → **Artifacts** → download
   **`ClearVoice-debug-apk`** → unzip → you get `app-debug.apk`.

The workflow uses: Java 17 (Temurin), Gradle 8.7 (`gradle/actions/setup-gradle@v4`),
Android SDK (`android-actions/setup-android@v3`), AGP 8.5.2, Kotlin 1.9.24,
compileSdk 34, minSdk 26.

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
  NoiseProcessor.kt     High-pass + adaptive noise gate + soft limiter DSP
  WavFile.kt            PCM16 WAV read / write / header info
  FileShareProvider.kt  Framework-only share provider (no AndroidX needed)
app/src/main/res/       Dark XML UI, vector adaptive launcher icon
.github/workflows/      build-apk.yml — builds and uploads the debug APK
```

No Jetpack Compose, no AndroidX — framework-only keeps the app light and the
CI build reliable.

## Roadmap

- **v2:** Video recording (camera + clean audio in one take) for creators
- **v2:** MP3/M4A import decoding, waveform view, trim/silence-cut
- **v3:** Optional stronger AI model (RNNoise / DeepFilterNet via NDK), signed
  release build for Play Store
