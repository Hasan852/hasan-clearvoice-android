package com.hasan.clearvoice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File

/** Processing presets shown as mode chips across the app. */
enum class NoiseMode { AUTO, VOICE_FOCUS, DEEP_NOISE, MUSIC }

/**
 * App-wide settings, persisted with the framework SharedPreferences
 * (no AndroidX). Every toggle in the Settings screen maps to one value
 * here, and the audio engine reads the same values — nothing in the UI
 * is decorative.
 */
object AppSettings {
    private const val FILE = "clearvoice_settings"
    private const val K_NOISE = "noise_on"
    private const val K_MODE = "mode"
    private const val K_LEVEL = "level" // 0=Low 1=Medium 2=High 3=Max
    private const val K_ECHO = "echo"
    private const val K_KEYBOARD = "keyboard"
    private const val K_WIND = "wind"
    private const val K_WAVEFORM = "waveform"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun noiseOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_NOISE, true)
    fun setNoiseOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_NOISE, v).apply()

    fun mode(ctx: Context): NoiseMode {
        val name = prefs(ctx).getString(K_MODE, NoiseMode.AUTO.name)
        return try {
            NoiseMode.valueOf(name ?: NoiseMode.AUTO.name)
        } catch (e: Exception) {
            NoiseMode.AUTO
        }
    }

    fun setMode(ctx: Context, m: NoiseMode) = prefs(ctx).edit().putString(K_MODE, m.name).apply()

    fun level(ctx: Context): Int = prefs(ctx).getInt(K_LEVEL, 1).coerceIn(0, 3)
    fun setLevel(ctx: Context, v: Int) = prefs(ctx).edit().putInt(K_LEVEL, v.coerceIn(0, 3)).apply()

    fun echoOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ECHO, true)
    fun setEchoOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_ECHO, v).apply()

    fun keyboardOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_KEYBOARD, true)
    fun setKeyboardOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_KEYBOARD, v).apply()

    fun windOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_WIND, false)
    fun setWindOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_WIND, v).apply()

    fun waveformOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_WAVEFORM, true)
    fun setWaveformOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_WAVEFORM, v).apply()

    // Preferred audio devices (Settings screen). -1 = system default.
    private const val K_MIC_ID = "mic_device_id"
    private const val K_SPK_ID = "spk_device_id"

    fun micDeviceId(ctx: Context): Int = prefs(ctx).getInt(K_MIC_ID, -1)
    fun setMicDeviceId(ctx: Context, id: Int) = prefs(ctx).edit().putInt(K_MIC_ID, id).apply()
    fun speakerDeviceId(ctx: Context): Int = prefs(ctx).getInt(K_SPK_ID, -1)
    fun setSpeakerDeviceId(ctx: Context, id: Int) = prefs(ctx).edit().putInt(K_SPK_ID, id).apply()

    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()

    fun levelName(level: Int): String = when (level.coerceIn(0, 3)) {
        0 -> "Low"
        1 -> "Medium"
        2 -> "High"
        else -> "Max"
    }
}

/** Recordings storage (app-specific Music folder, same as v1). */
object Storage {
    fun recordingsDir(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "Music")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listRecordings(ctx: Context): List<File> {
        val files = recordingsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
            ?: emptyArray()
        return files.sortedByDescending { it.lastModified() }
    }
}

/**
 * Mode-chip wiring shared by Home / Record / Clean screens.
 * The four ToggleButtons act like radio buttons: tapping one selects
 * that mode in [AppSettings] and unchecks the others.
 */
object ModeChips {
    fun bind(
        activity: Activity,
        auto: android.widget.ToggleButton,
        voice: android.widget.ToggleButton,
        deep: android.widget.ToggleButton,
        music: android.widget.ToggleButton,
        onChanged: (() -> Unit)? = null
    ) {
        val chips = listOf(
            NoiseMode.AUTO to auto,
            NoiseMode.VOICE_FOCUS to voice,
            NoiseMode.DEEP_NOISE to deep,
            NoiseMode.MUSIC to music
        )
        fun reflect() {
            val current = AppSettings.mode(activity)
            for ((mode, chip) in chips) chip.isChecked = mode == current
        }
        for ((mode, chip) in chips) {
            chip.setOnClickListener {
                AppSettings.setMode(activity, mode)
                reflect()
                onChanged?.invoke()
            }
        }
        reflect()
    }
}

/** Bottom-tab navigation shared by all screens. */
object UiNav {
    const val HOME = "home"
    const val RECORD = "record"
    const val FILES = "files"
    const val SETTINGS = "settings"

    fun open(from: Activity, tab: String) {
        val target = when (tab) {
            RECORD -> RecordActivity::class.java
            FILES -> FilesActivity::class.java
            SETTINGS -> SettingsActivity::class.java
            else -> MainActivity::class.java
        }
        if (from.javaClass == target) return
        from.startActivity(Intent(from, target))
        from.finish()
    }

    /** Wire the four bottom-tab buttons of a screen; highlights [current]. */
    fun wire(
        from: Activity,
        current: String,
        home: android.widget.Button,
        record: android.widget.Button,
        files: android.widget.Button,
        settings: android.widget.Button
    ) {
        val activeColor = 0xFF38B6FF.toInt()
        val idleColor = 0xFF9DABB9.toInt()
        val tabs = listOf(HOME to home, RECORD to record, FILES to files, SETTINGS to settings)
        for ((tab, button) in tabs) {
            button.setTextColor(if (tab == current) activeColor else idleColor)
            if (tab == current) button.paint.isFakeBoldText = true
            button.setOnClickListener { open(from, tab) }
        }
    }
}
