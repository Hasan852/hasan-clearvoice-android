package com.hasan.clearvoice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Record screen: live AI noise cancellation while recording (10 ms
 * frames, same engine as v1), live waveform (grey = raw in,
 * blue = cleaned out), pause/resume, discard, stop & save with
 * loudness normalisation so playback is properly loud.
 */
class RecordActivity : Activity() {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val MAX_PCM_BYTES = 180 * 1024 * 1024 // ~31 min RAM cap
    }

    private enum class RecordState { IDLE, RECORDING, PAUSED }

    private lateinit var switchNoise: Switch
    private lateinit var tvTimer: TextView
    private lateinit var tvRecState: TextView
    private lateinit var tvRecHint: TextView
    private lateinit var waveform: WaveformView
    private lateinit var pbLevel: ProgressBar
    private lateinit var btnRecordMain: Button
    private lateinit var btnStopSave: Button
    private lateinit var btnDiscard: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var recordState = RecordState.IDLE
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var effects = mutableListOf<AudioEffect>()
    private var pcmOutput: ByteArrayOutputStream? = null

    @Volatile
    private var samplesWritten: Long = 0

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (recordState != RecordState.IDLE) {
                tvTimer.text = formatTime(samplesWritten / SAMPLE_RATE)
                mainHandler.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)
        bindViews()
        setupListeners()
        updateButtons()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timerRunnable)
        if (recordState != RecordState.IDLE) stopRecordingThread()
        super.onDestroy()
    }

    private fun bindViews() {
        switchNoise = findViewById(R.id.switchNoise)
        tvTimer = findViewById(R.id.tvTimer)
        tvRecState = findViewById(R.id.tvRecState)
        tvRecHint = findViewById(R.id.tvRecHint)
        waveform = findViewById(R.id.waveform)
        pbLevel = findViewById(R.id.pbLevel)
        btnRecordMain = findViewById(R.id.btnRecordMain)
        btnStopSave = findViewById(R.id.btnStopSave)
        btnDiscard = findViewById(R.id.btnDiscard)
    }

    private fun setupListeners() {
        switchNoise.isChecked = AppSettings.noiseOn(this)
        switchNoise.setOnCheckedChangeListener { _, checked -> AppSettings.setNoiseOn(this, checked) }
        waveform.visibility = if (AppSettings.waveformOn(this)) android.view.View.VISIBLE else android.view.View.INVISIBLE
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (recordState == RecordState.IDLE) finish() else toast("Age record stop koro")
        }
        btnRecordMain.setOnClickListener { onRecordMainClicked() }
        btnStopSave.setOnClickListener { onStopSaveClicked() }
        btnDiscard.setOnClickListener { onDiscardClicked() }
        ModeChips.bind(
            this,
            findViewById(R.id.chipAuto),
            findViewById(R.id.chipVoice),
            findViewById(R.id.chipDeep),
            findViewById(R.id.chipMusic)
        )
        UiNav.wire(
            this, UiNav.RECORD,
            findViewById(R.id.navHome),
            findViewById(R.id.navRecord),
            findViewById(R.id.navFiles),
            findViewById(R.id.navSettings)
        )
    }

    private fun onRecordMainClicked() {
        when (recordState) {
            RecordState.IDLE -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                    return
                }
                startRecording()
            }
            RecordState.RECORDING -> {
                recordState = RecordState.PAUSED
                tvRecState.text = "Paused"
                btnRecordMain.text = "▶"
                tvRecHint.text = "Resume korte boro button, save korte ■ chapao"
            }
            RecordState.PAUSED -> {
                recordState = RecordState.RECORDING
                tvRecState.text = recordingStateText()
                btnRecordMain.text = "❚❚"
                tvRecHint.text = "Recording hocche… ■ chaple save hobe"
            }
        }
        updateButtons()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                toast("Microphone permission lagbe record korte")
            }
        }
    }

    private fun recordingStateText(): String {
        val noiseOn = AppSettings.noiseOn(this)
        return when {
            !noiseOn -> "● Recording… RAW (noise cancel OFF)"
            else -> "● Recording with AI"
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val noiseOn = AppSettings.noiseOn(this)
        val aiSession = if (noiseOn) RnNoise.create() else null
        val processor = NoiseProcessor(
            SAMPLE_RATE,
            AppSettings.mode(this),
            AppSettings.level(this),
            noiseOn,
            aiSession,
            AppSettings.windOn(this),
            AppSettings.keyboardOn(this)
        )
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            toast("Ei phone 48kHz recording support kore na")
            processor.close()
            return
        }
        val frameBytes = NoiseProcessor.frameSamplesFor(SAMPLE_RATE) * 2
        val bufferSize = maxOf(minBuffer, frameBytes * 4)
        val recorder: AudioRecord
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            toast("Microphone permission denied")
            processor.close()
            return
        } catch (e: IllegalArgumentException) {
            toast("Recorder start hocche na: ${e.message}")
            processor.close()
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            toast("Recorder failed to initialise")
            recorder.release()
            processor.close()
            return
        }
        audioRecord = recorder
        applyPreferredMic(recorder)
        if (noiseOn) attachEffects(recorder.audioSessionId)

        pcmOutput = ByteArrayOutputStream()
        samplesWritten = 0
        recordState = RecordState.RECORDING
        waveform.clearAll()
        updateButtons()
        tvRecState.text = recordingStateText()
        tvRecHint.text = if (processor.usingAi) "AI noise cancel ON — live clean hocche" else "Noise cancel ON (DSP)"
        tvTimer.text = "00:00:00"
        btnRecordMain.text = "❚❚"
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.post(timerRunnable)

        recordingThread = Thread {
            val buffer = ShortArray(NoiseProcessor.frameSamplesFor(SAMPLE_RATE))
            val byteBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            val started = try {
                recorder.startRecording()
                true
            } catch (e: IllegalStateException) {
                false
            }
            var framesSinceUiUpdate = 0
            var limitNoticePosted = false
            while (started && recordState != RecordState.IDLE) {
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (e: IllegalStateException) {
                    -1
                }
                if (read <= 0) continue
                if (recordState == RecordState.PAUSED) continue // drain + discard = true pause
                var inPeak = 0f
                var i = 0
                while (i < read) {
                    val a = abs(buffer[i].toInt()) / 32768f
                    if (a > inPeak) inPeak = a
                    i++
                }
                val outPeak = processor.process(buffer, read)
                byteBuffer.clear()
                i = 0
                while (i < read) {
                    byteBuffer.putShort(buffer[i])
                    i++
                }
                synchronized(this) {
                    pcmOutput?.write(byteBuffer.array(), 0, read * 2)
                }
                samplesWritten += read
                if (AppSettings.waveformOn(this@RecordActivity)) {
                    waveform.push(inPeak, outPeak)
                }
                framesSinceUiUpdate++
                if (framesSinceUiUpdate >= 5) {
                    framesSinceUiUpdate = 0
                    val level = (outPeak * 100).roundToInt().coerceIn(0, 100)
                    mainHandler.post { if (recordState != RecordState.IDLE) pbLevel.progress = level }
                }
                if (!limitNoticePosted && (pcmOutput?.size() ?: 0) > MAX_PCM_BYTES) {
                    limitNoticePosted = true
                    mainHandler.post {
                        if (recordState != RecordState.IDLE) {
                            toast("Maximum length — auto save hocche")
                            onStopSaveClicked()
                        }
                    }
                }
            }
            try {
                recorder.stop()
            } catch (e: IllegalStateException) {
                // Already stopped.
            }
            processor.close()
        }
        recordingThread?.start()
    }

    private fun applyPreferredMic(recorder: AudioRecord) {
        val wantedId = AppSettings.micDeviceId(this)
        if (wantedId < 0) return
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val device = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == wantedId }
            if (device != null) recorder.setPreferredDevice(device)
        } catch (e: Exception) {
            // Preferred device is a hint — default mic still works.
        }
    }

    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { it.enabled = true; effects.add(it) }
            }
        } catch (e: Exception) { /* optional */ }
        try {
            if (AppSettings.echoOn(this) && AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; effects.add(it) }
            }
        } catch (e: Exception) { /* optional */ }
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let { it.enabled = true; effects.add(it) }
            }
        } catch (e: Exception) { /* optional */ }
    }

    private fun releaseEffects() {
        for (effect in effects) {
            try {
                effect.enabled = false
                effect.release()
            } catch (e: Exception) { /* ignore */ }
        }
        effects.clear()
    }

    private fun onStopSaveClicked() {
        if (recordState == RecordState.IDLE) return
        stopRecordingThread()
        recordState = RecordState.IDLE
        mainHandler.removeCallbacks(timerRunnable)
        pbLevel.progress = 0
        btnRecordMain.text = "●"

        val pcmBytes = synchronized(this) { pcmOutput?.toByteArray() } ?: ByteArray(0)
        pcmOutput = null
        if (pcmBytes.isEmpty()) {
            tvRecState.text = "Ready"
            tvRecHint.text = "Kichu record hoy ni"
            updateButtons()
            return
        }
        val modeTag = if (AppSettings.noiseOn(this)) "clean" else "raw"
        val fileName = "ClearVoice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_$modeTag.wav"
        val file = File(Storage.recordingsDir(this), fileName)
        try {
            // Normalised copy: playback will be properly loud.
            WavFile.writeWavBytes(file, WavFile.normalizedCopy(pcmBytes), SAMPLE_RATE, 1)
            tvRecState.text = "Saved"
            toast("Saved: ${file.name}")
            UiNav.open(this, UiNav.FILES)
        } catch (e: Exception) {
            tvRecState.text = "Ready"
            toast("Save failed: ${e.message}")
        }
        updateButtons()
    }

    private fun onDiscardClicked() {
        if (recordState == RecordState.IDLE) return
        AlertDialog.Builder(this)
            .setTitle("Discard recording?")
            .setMessage("Ei recording ta fele dewa hobe, save hobe na.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Discard") { _, _ ->
                stopRecordingThread()
                recordState = RecordState.IDLE
                mainHandler.removeCallbacks(timerRunnable)
                pcmOutput = null
                samplesWritten = 0
                pbLevel.progress = 0
                tvTimer.text = "00:00:00"
                tvRecState.text = "Ready"
                btnRecordMain.text = "●"
                waveform.clearAll()
                updateButtons()
            }
            .show()
    }

    private fun stopRecordingThread() {
        val thread = recordingThread
        recordState = RecordState.IDLE
        if (thread != null) {
            try {
                thread.join(800)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        recordingThread = null
        releaseEffects()
        audioRecord?.let {
            try {
                it.release()
            } catch (e: Exception) { /* ignore */ }
        }
        audioRecord = null
    }

    private fun updateButtons() {
        val active = recordState != RecordState.IDLE
        btnStopSave.isEnabled = active
        btnDiscard.isEnabled = active
    }

    private fun formatTime(totalSeconds: Long): String {
        val safe = if (totalSeconds < 0) 0 else totalSeconds
        return String.format(Locale.US, "%02d:%02d:%02d", safe / 3600, (safe % 3600) / 60, safe % 60)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
