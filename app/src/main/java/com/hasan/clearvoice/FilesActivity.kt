package com.hasan.clearvoice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Files screen: all recordings + cleaned files, each row with
 * play/pause toggle, share and delete. Playback uses the speaker
 * chosen in Settings and full media volume (recordings are
 * loudness-normalised on save, so they play back properly loud).
 */
class FilesActivity : Activity() {

    private lateinit var lvRecordings: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var adapter: RecordingAdapter

    private var mediaPlayer: MediaPlayer? = null
    private var playingFile: File? = null
    private var playbackPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        bindViews()
        adapter = RecordingAdapter()
        lvRecordings.adapter = adapter
        refreshRecordings()
        UiNav.wire(
            this, UiNav.FILES,
            findViewById(R.id.navHome),
            findViewById(R.id.navRecord),
            findViewById(R.id.navFiles),
            findViewById(R.id.navSettings)
        )
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun bindViews() {
        lvRecordings = findViewById(R.id.lvRecordings)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvCount = findViewById(R.id.tvCount)
    }

    // ---------------- Playback ----------------

    private fun togglePlay(file: File) {
        val player = mediaPlayer
        if (player != null && playingFile == file) {
            try {
                if (player.isPlaying) {
                    player.pause()
                    playbackPaused = true
                } else {
                    player.start()
                    playbackPaused = false
                }
            } catch (e: Exception) {
                stopPlayback()
                playFile(file)
            }
            adapter.notifyDataSetChanged()
            return
        }
        playFile(file)
    }

    private fun playFile(file: File) {
        if (!file.exists()) {
            toast("File ta ar nai")
            refreshRecordings()
            return
        }
        stopPlayback()
        val player = MediaPlayer()
        mediaPlayer = player
        playingFile = file
        playbackPaused = false
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setVolume(1f, 1f)
            applyPreferredSpeaker(player)
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                stopPlayback()
                adapter.notifyDataSetChanged()
            }
            player.setOnErrorListener { _, _, _ ->
                toast("Playback error")
                stopPlayback()
                adapter.notifyDataSetChanged()
                true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            toast("Play kora jacche na: ${e.message}")
            stopPlayback()
        }
        adapter.notifyDataSetChanged()
    }

    private fun applyPreferredSpeaker(player: MediaPlayer) {
        val wantedId = AppSettings.speakerDeviceId(this)
        if (wantedId < 0) return
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == wantedId }
            if (device != null) player.setPreferredDevice(device)
        } catch (e: Exception) { /* hint only */ }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) { /* ignore */ }
            it.release()
        }
        mediaPlayer = null
        playingFile = null
        playbackPaused = false
    }

    fun isPlayingFile(file: File): Boolean {
        val player = mediaPlayer ?: return false
        if (playingFile != file) return false
        return try {
            player.isPlaying
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- Share / Delete ----------------

    private fun shareFile(file: File) {
        if (!file.exists()) {
            toast("File ta ar nai")
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
            .setMessage("\"${file.name}\" delete hobe. Eta ar fera jabe na.")
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
        toast(if (deleted) "Deleted ${file.name}" else "Delete kora jay ni")
        refreshRecordings()
    }

    // ---------------- List ----------------

    private fun refreshRecordings() {
        val files = Storage.listRecordings(this)
        adapter.setFiles(files)
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        tvCount.text = when (files.size) {
            0 -> ""
            1 -> "1 file"
            else -> "${files.size} files"
        }
    }

    private inner class RecordingAdapter : BaseAdapter() {
        private var files: List<File> = emptyList()
        private var metas: List<String> = emptyList()

        fun setFiles(newFiles: List<File>) {
            files = newFiles
            metas = newFiles.map { metaText(it) }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = files.size
        override fun getItem(position: Int): Any = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@FilesActivity)
                .inflate(R.layout.item_recording, parent, false)
            val file = files[position]
            view.findViewById<TextView>(R.id.tvItemName).text = file.name
            view.findViewById<TextView>(R.id.tvItemMeta).text =
                metas.getOrElse(position) { metaText(file) }
            val playBtn = view.findViewById<Button>(R.id.btnItemPlay)
            playBtn.text = if (isPlayingFile(file)) "❚❚" else "▶"
            playBtn.setOnClickListener { togglePlay(file) }
            view.findViewById<Button>(R.id.btnItemShare).setOnClickListener { shareFile(file) }
            view.findViewById<Button>(R.id.btnItemDelete).setOnClickListener { confirmDelete(file) }
            return view
        }
    }

    private fun metaText(file: File): String {
        val date = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(file.lastModified()))
        val size = formatSize(file.length())
        val info = try {
            WavFile.readInfo(file)
        } catch (e: Exception) {
            null
        }
        val duration = if (info != null) formatTime(info.durationSeconds.toLong()) else "--:--"
        return "$date  ·  $duration  ·  $size"
    }

    private fun formatTime(totalSeconds: Long): String {
        val safe = if (totalSeconds < 0) 0 else totalSeconds
        return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
