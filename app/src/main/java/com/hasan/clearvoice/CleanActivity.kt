package com.hasan.clearvoice

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clean Audio screen: pick ANY audio file (MP3, WAV, M4A, AAC, FLAC,
 * OGG...), choose mode + noise reduction level, and the [AudioCleaner]
 * engine writes a cleaned, loudness-normalised WAV into the Files list.
 */
class CleanActivity : Activity() {

    companion object {
        private const val REQUEST_PICK_AUDIO = 2002
    }

    private lateinit var uploadBox: View
    private lateinit var fileRow: View
    private lateinit var tvFileName: TextView
    private lateinit var tvFileMeta: TextView
    private lateinit var switchNoise: Switch
    private lateinit var tvLevelVal: TextView
    private lateinit var sbLevel: SeekBar
    private lateinit var pbClean: ProgressBar
    private lateinit var tvCleanState: TextView
    private lateinit var btnProcess: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pickedUri: Uri? = null

    @Volatile
    private var cleaning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clean)
        bindViews()
        setupListeners()
    }

    private fun bindViews() {
        uploadBox = findViewById(R.id.uploadBox)
        fileRow = findViewById(R.id.fileRow)
        tvFileName = findViewById(R.id.tvFileName)
        tvFileMeta = findViewById(R.id.tvFileMeta)
        switchNoise = findViewById(R.id.switchNoise)
        tvLevelVal = findViewById(R.id.tvLevelVal)
        sbLevel = findViewById(R.id.sbLevel)
        pbClean = findViewById(R.id.pbClean)
        tvCleanState = findViewById(R.id.tvCleanState)
        btnProcess = findViewById(R.id.btnProcess)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (!cleaning) finish() else toast("Cleaning cholche, ektu wait koro")
        }
        switchNoise.isChecked = AppSettings.noiseOn(this)
        switchNoise.setOnCheckedChangeListener { _, checked -> AppSettings.setNoiseOn(this, checked) }
        uploadBox.setOnClickListener { openPicker() }
        findViewById<Button>(R.id.btnRemoveFile).setOnClickListener {
            pickedUri = null
            fileRow.visibility = View.GONE
            tvCleanState.text = ""
        }
        sbLevel.progress = AppSettings.level(this)
        tvLevelVal.text = AppSettings.levelName(sbLevel.progress)
        sbLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvLevelVal.text = AppSettings.levelName(progress)
                if (fromUser) AppSettings.setLevel(this@CleanActivity, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        ModeChips.bind(
            this,
            findViewById(R.id.chipAuto),
            findViewById(R.id.chipVoice),
            findViewById(R.id.chipDeep),
            findViewById(R.id.chipMusic)
        )
        btnProcess.setOnClickListener { startCleaning() }
        UiNav.wire(
            this, UiNav.HOME,
            findViewById(R.id.navHome),
            findViewById(R.id.navRecord),
            findViewById(R.id.navFiles),
            findViewById(R.id.navSettings)
        )
    }

    private fun openPicker() {
        if (cleaning) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/*", "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/flac", "audio/ogg")
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_AUDIO)
    }

    @Deprecated("Framework startActivityForResult keeps us AndroidX-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_AUDIO || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        pickedUri = uri
        showPickedFile(uri)
    }

    private fun showPickedFile(uri: Uri) {
        var name = "audio file"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        } catch (e: Exception) { /* name stays generic */ }
        var durationMs = -1L
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = d?.toLongOrNull() ?: -1L
            retriever.release()
        } catch (e: Exception) { /* duration optional */ }
        tvFileName.text = name
        val parts = mutableListOf<String>()
        if (size >= 0) parts.add(formatSize(size))
        if (durationMs > 0) parts.add(formatDuration(durationMs / 1000))
        tvFileMeta.text = parts.joinToString("  ·  ")
        fileRow.visibility = View.VISIBLE
        tvCleanState.text = "File ready — Start Processing chapao"
    }

    private fun startCleaning() {
        val uri = pickedUri
        if (uri == null) {
            toast("Age ekta audio file select koro")
            return
        }
        if (cleaning) return
        cleaning = true
        btnProcess.isEnabled = false
        uploadBox.isEnabled = false
        pbClean.visibility = View.VISIBLE
        pbClean.progress = 0
        tvCleanState.text = "Cleaning… 0%"
        val mode = AppSettings.mode(this)
        val level = AppSettings.level(this)
        val noiseOn = AppSettings.noiseOn(this)
        val wind = AppSettings.windOn(this)
        val keyboard = AppSettings.keyboardOn(this)
        Thread {
            try {
                val outName = "Cleaned_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
                val outFile = File(Storage.recordingsDir(this), outName)
                AudioCleaner.clean(this, uri, mode, level, wind, keyboard, noiseOn, outFile) { pct ->
                    mainHandler.post {
                        pbClean.progress = pct
                        tvCleanState.text = "Cleaning… $pct%"
                    }
                }
                mainHandler.post {
                    cleaning = false
                    btnProcess.isEnabled = true
                    uploadBox.isEnabled = true
                    tvCleanState.text = "Done! Cleaned file Files e save hoyeche"
                    toast("Cleaned file saved: ${outFile.name}")
                    UiNav.open(this, UiNav.FILES)
                }
            } catch (e: AudioCleaner.CleanException) {
                mainHandler.post {
                    cleaning = false
                    btnProcess.isEnabled = true
                    uploadBox.isEnabled = true
                    pbClean.visibility = View.GONE
                    tvCleanState.text = ""
                    toast(e.message ?: "Clean kora jay ni")
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    cleaning = false
                    btnProcess.isEnabled = true
                    uploadBox.isEnabled = true
                    pbClean.visibility = View.GONE
                    tvCleanState.text = ""
                    toast("Problem hoyeche: ${t.message}")
                }
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatDuration(totalSeconds: Long): String =
        String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
