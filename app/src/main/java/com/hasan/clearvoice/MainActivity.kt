package com.hasan.clearvoice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
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
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_IMPORT_WAV = 2002

        /** RAM cap for one recording's PCM buffer (~31 min) — see recording loop. */
        private const val MAX_PCM_BYTES = 180 * 1024 * 1024

        /** Import guard: refuse huge files instead of OOM-crashing while cleaning. */
        private const val MAX_IMPORT_BYTES = 120L * 1024 * 1024
    }

    private enum class RecordState { IDLE, RECORDING, PAUSED }

    // Views
    private lateinit var tvTimer: TextView
    private lateinit var tvRecordingState: TextView
    private lateinit var tvNoiseStatus: TextView
    private lateinit var tvNowPlaying: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var pbLevel: ProgressBar
    private lateinit var switchNoiseCancel: Switch
    private lateinit var rgStrength: RadioGroup
    private lateinit var btnRecord: Button
    private lateinit var btnPauseRecord: Button
    private lateinit var btnStopSave: Button
    private lateinit var btnPlay: Button
    private lateinit var btnPausePlayback: Button
    private lateinit var lvRecordings: ListView

    private val mainHandler = Handler(Looper.getMainLooper())

    // Recording
    @Volatile
    private var recordState = RecordState.IDLE
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var effects = mutableListOf<AudioEffect>()
    private var pcmOutput: ByteArrayOutputStream? = null

    @Volatile
    private var samplesWritten: Long = 0

    // Playback
    private var mediaPlayer: MediaPlayer? = null
    private var playingFile: File? = null
    private var playbackPaused = false
    private var selectedFile: File? = null

    private lateinit var adapter: RecordingAdapter

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
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        adapter = RecordingAdapter()
        lvRecordings.adapter = adapter
        refreshRecordings()
        updateRecordingButtons()
        updateNoiseStatus()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timerRunnable)
        if (recordState != RecordState.IDLE) stopRecordingThread()
        stopPlayback()
        super.onDestroy()
    }

    private fun bindViews() {
        tvTimer = findViewById(R.id.tvTimer)
        tvRecordingState = findViewById(R.id.tvRecordingState)
        tvNoiseStatus = findViewById(R.id.tvNoiseStatus)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        tvEmpty = findViewById(R.id.tvEmpty)
        pbLevel = findViewById(R.id.pbLevel)
        switchNoiseCancel = findViewById(R.id.switchNoiseCancel)
        rgStrength = findViewById(R.id.rgStrength)
        btnRecord = findViewById(R.id.btnRecord)
        btnPauseRecord = findViewById(R.id.btnPauseRecord)
        btnStopSave = findViewById(R.id.btnStopSave)
        btnPlay = findViewById(R.id.btnPlay)
        btnPausePlayback = findViewById(R.id.btnPausePlayback)
        // CRASH FIX (v1.2): lvRecordings was declared lateinit but NEVER
        // assigned here — setupListeners() then touched it during
        // onCreate, throwing UninitializedPropertyAccessException and
        // crashing the app instantly on every launch (v1.0 + v1.1).
        // scripts/check_lateinit.py now runs in CI to catch this class
        // of bug before any future build ships.
        lvRecordings = findViewById(R.id.lvRecordings)
    }

    private fun setupListeners() {
        switchNoiseCancel.setOnCheckedChangeListener { _, _ -> updateNoiseStatus() }
        btnRecord.setOnClickListener { onRecordClicked() }
        btnPauseRecord.setOnClickListener { onPauseRecordClicked() }
        btnStopSave.setOnClickListener { onStopSaveClicked() }
        btnPlay.setOnClickListener { selectedFile?.let { playFile(it) } ?: toast("Record something first") }
        btnPausePlayback.setOnClickListener { pausePlayback() }
        findViewById<Button>(R.id.btnStopPlayback).setOnClickListener { stopPlayback() }
        findViewById<Button>(R.id.btnShareLast).setOnClickListener {
            selectedFile?.let { shareFile(it) } ?: toast("No recording to share")
        }
        findViewById<Button>(R.id.btnDeleteLast).setOnClickListener {
            selectedFile?.let { confirmDelete(it) } ?: toast("No recording to delete")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener { openImportPicker() }
        lvRecordings.setOnItemClickListener { _, _, position, _ ->
            val file = adapter.getFile(position)
            selectedFile = file
            tvNowPlaying.text = "Selected: ${file.name}"
        }
    }

    private fun updateNoiseStatus() {
        if (switchNoiseCancel.isChecked) {
            tvNoiseStatus.text = getString(R.string.noise_on)
            tvNoiseStatus.setTextColor(getColor(R.color.accent))
            rgStrength.visibility = View.VISIBLE
        } else {
            tvNoiseStatus.text = getString(R.string.noise_off)
            tvNoiseStatus.setTextColor(getColor(R.color.warning))
            // Strength is meaningless in raw mode — dimming via visibility of the group
            // would shift layout, so keep it visible but the processor is bypassed.
            rgStrength.visibility = View.VISIBLE
        }
    }

    private fun currentStrength(): NoiseProcessor.Strength {
        return when (rgStrength.checkedRadioButtonId) {
            R.id.rbLight -> NoiseProcessor.Strength.LIGHT
            R.id.rbStrong -> NoiseProcessor.Strength.STRONG
            else -> NoiseProcessor.Strength.MEDIUM
        }
    }

    // ---------------- Recording ----------------

    private fun onRecordClicked() {
        if (recordState != RecordState.IDLE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startRecording()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                toast("Microphone permission is needed to record")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val noiseOn = switchNoiseCancel.isChecked
        // AI mode first: try to open an RNNoise session. If the native
        // library is unavailable on this device, RnNoise.create() returns
        // null and NoiseProcessor automatically runs DSP-only — the
        // recording never breaks because of the native library.
        val aiSession = if (noiseOn) RnNoise.create() else null
        val processor = NoiseProcessor(SAMPLE_RATE, currentStrength(), noiseOn, aiSession)
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            toast("This device does not support 48kHz mono recording")
            return
        }
        // Low latency: AudioRecord buffer = just 4 x 10ms frames (the
        // smallest the device allows wins if its minimum is larger).
        // Processing below reads and cleans exactly one 10ms frame at
        // a time, so the DSP never waits on a big buffer.
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
            return
        } catch (e: IllegalArgumentException) {
            toast("Could not start recorder: ${e.message}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            toast("Recorder failed to initialise")
            recorder.release()
            return
        }
        audioRecord = recorder
        if (noiseOn) attachEffects(recorder.audioSessionId)

        pcmOutput = ByteArrayOutputStream()
        samplesWritten = 0
        recordState = RecordState.RECORDING
        updateRecordingButtons()
        tvRecordingState.text = when {
            !noiseOn -> "Recording… RAW (noise cancellation OFF)"
            processor.usingAi -> "Recording… AI noise cancellation ON (RNNoise)"
            else -> "Recording… noise cancellation ON (DSP)"
        }
        tvTimer.text = "00:00"
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.post(timerRunnable)

        recordingThread = Thread {
            // One 10ms frame per read/process/write cycle.
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
                if (recordState == RecordState.PAUSED) {
                    // Keep draining the mic buffer, but discard samples and
                    // do not advance the timer — true pause behaviour.
                    continue
                }
                val peak = processor.process(buffer, read)
                byteBuffer.clear()
                var i = 0
                while (i < read) {
                    byteBuffer.putShort(buffer[i])
                    i++
                }
                synchronized(this) {
                    pcmOutput?.write(byteBuffer.array(), 0, read * 2)
                }
                samplesWritten += read
                // Smoothness: update the level meter every 5th frame
                // (~50 ms), not every 10 ms frame — posting to the UI
                // thread 100x/sec floods it and makes the app feel janky.
                framesSinceUiUpdate++
                if (framesSinceUiUpdate >= 5) {
                    framesSinceUiUpdate = 0
                    val level = (peak * 100).roundToInt().coerceIn(0, 100)
                    mainHandler.post { if (recordState != RecordState.IDLE) pbLevel.progress = level }
                }
                // Crash guard: PCM is buffered in RAM while recording.
                // Cap it (~180 MB ≈ 31 min at 48kHz mono) and auto-save
                // instead of running the phone out of memory (OOM crash
                // on very long recordings).
                if (!limitNoticePosted && (pcmOutput?.size() ?: 0) > MAX_PCM_BYTES) {
                    limitNoticePosted = true
                    mainHandler.post {
                        if (recordState != RecordState.IDLE) {
                            toast("Maximum recording length reached — saving")
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

    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        try {
            if (NoiseSuppressor.isAvailable()) {
                val ns = NoiseSuppressor.create(sessionId)
                if (ns != null) {
                    ns.enabled = true
                    effects.add(ns)
                }
            }
        } catch (e: Exception) {
            // Effect not available on this device — DSP still runs.
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                val aec = AcousticEchoCanceler.create(sessionId)
                if (aec != null) {
                    aec.enabled = true
                    effects.add(aec)
                }
            }
        } catch (e: Exception) {
            // Ignore — optional effect.
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                val agc = AutomaticGainControl.create(sessionId)
                if (agc != null) {
                    agc.enabled = true
                    effects.add(agc)
                }
            }
        } catch (e: Exception) {
            // Ignore — optional effect.
        }
    }

    private fun releaseEffects() {
        for (effect in effects) {
            try {
                effect.enabled = false
                effect.release()
            } catch (e: Exception) {
                // Ignore.
            }
        }
        effects.clear()
    }

    private fun onPauseRecordClicked() {
        when (recordState) {
            RecordState.RECORDING -> {
                recordState = RecordState.PAUSED
                tvRecordingState.text = "Paused — press Resume to continue"
                btnPauseRecord.text = getString(R.string.resume_record)
            }
            RecordState.PAUSED -> {
                recordState = RecordState.RECORDING
                tvRecordingState.text = "Recording…"
                btnPauseRecord.text = getString(R.string.pause_record)
            }
            RecordState.IDLE -> Unit
        }
        updateRecordingButtons()
    }

    private fun onStopSaveClicked() {
        if (recordState == RecordState.IDLE) return
        val wasPaused = recordState == RecordState.PAUSED
        stopRecordingThread()
        recordState = RecordState.IDLE
        mainHandler.removeCallbacks(timerRunnable)
        pbLevel.progress = 0

        val pcmBytes = synchronized(this) { pcmOutput?.toByteArray() } ?: ByteArray(0)
        pcmOutput = null
        if (pcmBytes.isEmpty()) {
            tvRecordingState.text = getString(R.string.ready)
            updateRecordingButtons()
            toast("Nothing was recorded")
            return
        }
        val modeTag = if (switchNoiseCancel.isChecked) "clean" else "raw"
        val fileName = "ClearVoice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_$modeTag.wav"
        val file = File(recordingsDir(), fileName)
        try {
            WavFile.writeWavBytes(file, pcmBytes, SAMPLE_RATE, 1)
            selectedFile = file
            tvNowPlaying.text = "Saved: ${file.name}"
            tvRecordingState.text = "Saved ${file.name}" + if (wasPaused) " (ended while paused)" else ""
            toast("Saved: ${file.name}")
        } catch (e: Exception) {
            tvRecordingState.text = getString(R.string.ready)
            toast("Save failed: ${e.message}")
        }
        updateRecordingButtons()
        refreshRecordings()
    }

    private fun stopRecordingThread() {
        val thread = recordingThread
        // Flip state first so the loop exits after its current read.
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
            } catch (e: Exception) {
                // Ignore.
            }
        }
        audioRecord = null
    }

    private fun updateRecordingButtons() {
        when (recordState) {
            RecordState.IDLE -> {
                btnRecord.isEnabled = true
                btnPauseRecord.isEnabled = false
                btnPauseRecord.text = getString(R.string.pause_record)
                btnStopSave.isEnabled = false
            }
            RecordState.RECORDING, RecordState.PAUSED -> {
                btnRecord.isEnabled = false
                btnPauseRecord.isEnabled = true
                btnStopSave.isEnabled = true
            }
        }
    }

    // ---------------- Playback ----------------

    private fun playFile(file: File) {
        if (!file.exists()) {
            toast("File no longer exists")
            refreshRecordings()
            return
        }
        if (mediaPlayer != null && playingFile == file && playbackPaused) {
            mediaPlayer?.start()
            playbackPaused = false
            tvNowPlaying.text = "Playing: ${file.name}"
            return
        }
        stopPlayback()
        selectedFile = file
        val player = MediaPlayer()
        mediaPlayer = player
        playingFile = file
        playbackPaused = false
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                tvNowPlaying.text = "Finished: ${file.name}"
                stopPlayback()
            }
            player.setOnErrorListener { _, _, _ ->
                toast("Playback error")
                stopPlayback()
                true
            }
            player.prepare()
            player.start()
            tvNowPlaying.text = "Playing: ${file.name}"
        } catch (e: Exception) {
            toast("Could not play: ${e.message}")
            stopPlayback()
        }
    }

    private fun pausePlayback() {
        val player = mediaPlayer
        if (player != null && player.isPlaying) {
            player.pause()
            playbackPaused = true
            playingFile?.let { tvNowPlaying.text = "Paused: ${it.name}" }
        } else {
            toast("Nothing is playing")
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
                // Ignore.
            }
            it.release()
        }
        mediaPlayer = null
        val finished = playingFile
        playingFile = null
        playbackPaused = false
        if (finished != null && tvNowPlaying.text.toString().startsWith("Playing")) {
            tvNowPlaying.text = "Stopped: ${finished.name}"
        }
    }

    // ---------------- Share / Delete ----------------

    private fun shareFile(file: File) {
        if (!file.exists()) {
            toast("File no longer exists")
            return
        }
        val uri: Uri = Uri.parse("content://com.hasan.clearvoice.fileprovider/${file.name}")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share recording"))
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage("Delete \"${file.name}\"? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteFileNow(file) }
            .show()
    }

    private fun deleteFileNow(file: File) {
        if (playingFile == file) stopPlayback()
        val deleted = try {
            file.exists() && file.delete()
        } catch (e: Exception) {
            false
        }
        if (selectedFile == file) {
            selectedFile = null
            tvNowPlaying.text = getString(R.string.now_playing_none)
        }
        toast(if (deleted) "Deleted ${file.name}" else "Could not delete ${file.name}")
        refreshRecordings()
    }

    // ---------------- Import & Clean (WAV only) ----------------

    private fun openImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMPORT_WAV)
        // Note: Activity.startActivityForResult is the framework API (not deprecated
        // in the framework itself) — using it keeps this project AndroidX-free.
    }

    @Deprecated("Framework startActivityForResult keeps us AndroidX-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_WAV || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        cleanImportedFile(uri)
    }

    private fun cleanImportedFile(uri: Uri) {
        toast("Cleaning imported file…")
        Thread {
            try {
                // Size guard first (when the provider reports a size):
                // loading a huge WAV fully into RAM would OOM-crash.
                val reportedSize = try {
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
                } catch (e: Exception) {
                    -1L
                }
                if (reportedSize > MAX_IMPORT_BYTES) {
                    mainHandler.post { toast("File is too large to clean (max ~120 MB)") }
                    return@Thread
                }
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read the selected file")
                if (bytes.size > MAX_IMPORT_BYTES) {
                    mainHandler.post { toast("File is too large to clean (max ~120 MB)") }
                    return@Thread
                }
                val parsed = WavFile.readWav(bytes)
                // Offline cleaning always applies processing (that is the point
                // of the button), using the currently selected strength.
                // AI first: an RNNoise session is used when the native library
                // is available AND the file is 48 kHz (RNNoise is 48 kHz only);
                // otherwise NoiseProcessor falls back to DSP-only on its own.
                // Channels are processed separately (RNNoise/gate are mono) and
                // chunks are aligned to the 10 ms frame so state carries across.
                val cleaned = parsed.samples.copyOf()
                val channels = parsed.channels.coerceAtLeast(1)
                val framesPerChannel = cleaned.size / channels
                val frame = NoiseProcessor.frameSamplesFor(parsed.sampleRate)
                for (ch in 0 until channels) {
                    val mono = ShortArray(framesPerChannel)
                    var i = 0
                    while (i < framesPerChannel) {
                        mono[i] = cleaned[i * channels + ch]
                        i++
                    }
                    val processor = NoiseProcessor(
                        parsed.sampleRate, currentStrength(), true, RnNoise.create()
                    )
                    var offset = 0
                    while (offset < mono.size) {
                        val chunkSize = minOf(frame, mono.size - offset)
                        val chunk = mono.copyOfRange(offset, offset + chunkSize)
                        processor.process(chunk, chunk.size)
                        System.arraycopy(chunk, 0, mono, offset, chunkSize)
                        offset += chunkSize
                    }
                    processor.close()
                    i = 0
                    while (i < framesPerChannel) {
                        cleaned[i * channels + ch] = mono[i]
                        i++
                    }
                }
                val outName = "Cleaned_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
                val outFile = File(recordingsDir(), outName)
                WavFile.writeWav(outFile, cleaned, parsed.sampleRate, parsed.channels)
                mainHandler.post {
                    selectedFile = outFile
                    tvNowPlaying.text = "Saved: ${outFile.name}"
                    toast("Cleaned file saved: ${outFile.name}")
                    refreshRecordings()
                }
            } catch (t: Throwable) {
                // Throwable, not just Exception: an OutOfMemoryError on a
                // borderline file must show a message, never crash the app.
                val msg = if (t is OutOfMemoryError) "File is too large to clean on this phone"
                else "Import failed: ${t.message}"
                mainHandler.post { toast(msg) }
            }
        }.start()
    }

    // ---------------- Recordings list ----------------

    private fun recordingsDir(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(filesDir, "Music")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun listRecordingFiles(): List<File> {
        val files = recordingsDir().listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
            ?: emptyArray()
        return files.sortedByDescending { it.lastModified() }
    }

    private fun refreshRecordings() {
        adapter.setFiles(listRecordingFiles())
        tvEmpty.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
        if (selectedFile == null && adapter.count > 0) {
            selectedFile = adapter.getFile(0)
            tvNowPlaying.text = "Selected: ${selectedFile?.name}"
        }
    }

    private inner class RecordingAdapter : BaseAdapter() {
        private var files: List<File> = emptyList()

        // Meta text (date/duration/size) is computed ONCE per refresh, not
        // on every getView() scroll pass — reading WAV headers from disk
        // inside getView causes visible scroll jank with many recordings.
        private var metas: List<String> = emptyList()

        fun setFiles(newFiles: List<File>) {
            files = newFiles
            metas = newFiles.map { metaText(it) }
            notifyDataSetChanged()
        }

        fun getFile(position: Int): File = files[position]

        override fun getCount(): Int = files.size
        override fun getItem(position: Int): Any = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_recording, parent, false)
            val file = files[position]
            view.findViewById<TextView>(R.id.tvItemName).text = file.name
            view.findViewById<TextView>(R.id.tvItemMeta).text =
                metas.getOrElse(position) { metaText(file) }
            view.findViewById<Button>(R.id.btnItemPlay).setOnClickListener { playFile(file) }
            view.findViewById<Button>(R.id.btnItemShare).setOnClickListener { shareFile(file) }
            view.findViewById<Button>(R.id.btnItemDelete).setOnClickListener { confirmDelete(file) }
            return view
        }
    }

    private fun metaText(file: File): String {
        val date = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(file.lastModified()))
        val size = formatSize(file.length())
        // readInfo can throw IOException if the file vanishes mid-read —
        // a broken list entry must never crash the app; show --:-- instead.
        val info = try {
            WavFile.readInfo(file)
        } catch (e: Exception) {
            null
        }
        val duration = if (info != null) formatTime(info.durationSeconds.toLong()) else "--:--"
        return "$date  ·  $duration  ·  $size"
    }

    // ---------------- Helpers ----------------

    private fun formatTime(totalSeconds: Long): String {
        val safe = if (totalSeconds < 0) 0 else totalSeconds
        return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
