package com.hasan.clearvoice

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton

/**
 * Home screen (v2 design chosen by Hasan): hero + Record / Clean Audio
 * cards + Noise Cancellation switch + big power circle + mode chips +
 * stats. The switch, the power circle and the chips all write the
 * same [AppSettings], so every screen stays in sync.
 */
class MainActivity : Activity() {

    private lateinit var switchNoise: Switch
    private lateinit var btnPower: Button
    private lateinit var tvPowerState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        // Deep-link hook used by the CI emulator screenshot job (the other
        // activities are not exported, so adb cannot start them directly):
        //   adb shell am start -n com.hasan.clearvoice/.MainActivity --es open record
        when (intent?.getStringExtra("open")) {
            "record" -> startActivity(android.content.Intent(this, RecordActivity::class.java))
            "clean" -> startActivity(android.content.Intent(this, CleanActivity::class.java))
            "files" -> startActivity(android.content.Intent(this, FilesActivity::class.java))
            "settings" -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reflectNoiseUi()
    }

    private fun bindViews() {
        switchNoise = findViewById(R.id.switchNoise)
        btnPower = findViewById(R.id.btnPower)
        tvPowerState = findViewById(R.id.tvPowerState)
    }

    private fun setupListeners() {
        switchNoise.setOnCheckedChangeListener { _, checked ->
            AppSettings.setNoiseOn(this, checked)
            reflectNoiseUi()
        }
        btnPower.setOnClickListener {
            val next = !AppSettings.noiseOn(this)
            AppSettings.setNoiseOn(this, next)
            reflectNoiseUi()
        }
        findViewById<View>(R.id.cardRecord).setOnClickListener { UiNav.open(this, UiNav.RECORD) }
        findViewById<View>(R.id.cardClean).setOnClickListener {
            startActivity(android.content.Intent(this, CleanActivity::class.java))
        }
        findViewById<View>(R.id.btnSettingsGear).setOnClickListener { UiNav.open(this, UiNav.SETTINGS) }
        ModeChips.bind(
            this,
            findViewById(R.id.chipAuto),
            findViewById(R.id.chipVoice),
            findViewById(R.id.chipDeep),
            findViewById(R.id.chipMusic)
        )
        UiNav.wire(
            this, UiNav.HOME,
            findViewById(R.id.navHome),
            findViewById(R.id.navRecord),
            findViewById(R.id.navFiles),
            findViewById(R.id.navSettings)
        )
    }

    private fun reflectNoiseUi() {
        val on = AppSettings.noiseOn(this)
        if (switchNoise.isChecked != on) switchNoise.isChecked = on
        btnPower.setBackgroundResource(if (on) R.drawable.circle_power_on else R.drawable.circle_power_off)
        btnPower.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF5F6D7C.toInt())
        tvPowerState.text = if (on) "AI Noise Cancellation is ON" else "AI Noise Cancellation is OFF"
    }
}
