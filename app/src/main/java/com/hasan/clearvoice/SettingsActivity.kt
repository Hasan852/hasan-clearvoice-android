package com.hasan.clearvoice

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Settings screen. Every control writes [AppSettings] and the audio
 * engine reads the same values:
 *  - Microphone / Speaker rows open a real device picker (the chosen
 *    device is applied via setPreferredDevice in Record / Files).
 *  - Noise Cancellation / Echo / Keyboard / Wind toggles switch real
 *    stages of the processing pipeline.
 *  - Noise Reduction Level is the gate-strength slider (0..3).
 *  - Show Live Waveform toggles the Record screen waveform.
 */
class SettingsActivity : Activity() {

    private lateinit var tvMicInfo: TextView
    private lateinit var tvSpeakerInfo: TextView
    private lateinit var switchNoise: Switch
    private lateinit var switchEcho: Switch
    private lateinit var switchKeyboard: Switch
    private lateinit var switchWind: Switch
    private lateinit var switchWaveform: Switch
    private lateinit var tvLevelVal: TextView
    private lateinit var sbLevel: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        setupListeners()
        reflectAll()
    }

    private fun bindViews() {
        tvMicInfo = findViewById(R.id.tvMicInfo)
        tvSpeakerInfo = findViewById(R.id.tvSpeakerInfo)
        switchNoise = findViewById(R.id.switchNoise)
        switchEcho = findViewById(R.id.switchEcho)
        switchKeyboard = findViewById(R.id.switchKeyboard)
        switchWind = findViewById(R.id.switchWind)
        switchWaveform = findViewById(R.id.switchWaveform)
        tvLevelVal = findViewById(R.id.tvLevelVal)
        sbLevel = findViewById(R.id.sbLevel)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AppSettings.reset(this)
            reflectAll()
            toast("Settings reset hoye geche")
        }
        findViewById<android.view.View>(R.id.rowMic).setOnClickListener { pickDevice(input = true) }
        findViewById<android.view.View>(R.id.rowSpeaker).setOnClickListener { pickDevice(input = false) }
        switchNoise.setOnCheckedChangeListener { _, v -> AppSettings.setNoiseOn(this, v) }
        switchEcho.setOnCheckedChangeListener { _, v -> AppSettings.setEchoOn(this, v) }
        switchKeyboard.setOnCheckedChangeListener { _, v -> AppSettings.setKeyboardOn(this, v) }
        switchWind.setOnCheckedChangeListener { _, v -> AppSettings.setWindOn(this, v) }
        switchWaveform.setOnCheckedChangeListener { _, v -> AppSettings.setWaveformOn(this, v) }
        sbLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvLevelVal.text = AppSettings.levelName(progress)
                if (fromUser) AppSettings.setLevel(this@SettingsActivity, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        UiNav.wire(
            this, UiNav.SETTINGS,
            findViewById(R.id.navHome),
            findViewById(R.id.navRecord),
            findViewById(R.id.navFiles),
            findViewById(R.id.navSettings)
        )
    }

    private fun reflectAll() {
        switchNoise.isChecked = AppSettings.noiseOn(this)
        switchEcho.isChecked = AppSettings.echoOn(this)
        switchKeyboard.isChecked = AppSettings.keyboardOn(this)
        switchWind.isChecked = AppSettings.windOn(this)
        switchWaveform.isChecked = AppSettings.waveformOn(this)
        sbLevel.progress = AppSettings.level(this)
        tvLevelVal.text = AppSettings.levelName(AppSettings.level(this))
        tvMicInfo.text = deviceLabel(input = true)
        tvSpeakerInfo.text = deviceLabel(input = false)
    }

    private fun audioManager(): AudioManager = getSystemService(AUDIO_SERVICE) as AudioManager

    private fun devices(input: Boolean): List<AudioDeviceInfo> {
        return try {
            audioManager().getDevices(
                if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
            ).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun deviceLabel(input: Boolean): String {
        val wantedId = if (input) AppSettings.micDeviceId(this) else AppSettings.speakerDeviceId(this)
        if (wantedId < 0) return if (input) "Built-in Mic (Default)" else "Built-in Speaker (Default)"
        val device = devices(input).firstOrNull { it.id == wantedId }
        return device?.productName?.toString() ?: if (input) "Built-in Mic (Default)" else "Built-in Speaker (Default)"
    }

    private fun pickDevice(input: Boolean) {
        val list = devices(input)
        val names = mutableListOf(if (input) "Built-in Mic (Default)" else "Built-in Speaker (Default)")
        for (d in list) {
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Device ${d.id}"
            if (!names.contains(name)) names.add(name)
        }
        AlertDialog.Builder(this)
            .setTitle(if (input) "Microphone" else "Speaker")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    if (input) AppSettings.setMicDeviceId(this, -1) else AppSettings.setSpeakerDeviceId(this, -1)
                } else {
                    val chosenName = names[which]
                    val device = list.firstOrNull { (it.productName?.toString() ?: "") == chosenName }
                    if (device != null) {
                        if (input) AppSettings.setMicDeviceId(this, device.id)
                        else AppSettings.setSpeakerDeviceId(this, device.id)
                    }
                }
                if (input) tvMicInfo.text = deviceLabel(true) else tvSpeakerInfo.text = deviceLabel(false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
