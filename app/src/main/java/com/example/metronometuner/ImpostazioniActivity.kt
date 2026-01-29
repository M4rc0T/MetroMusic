package com.example.metronometuner

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat // IMPORT FONDAMENTALE

class ImpostazioniActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences

    private val prefName = "AppPreferences"
    private val prefDarkMode = "dark_mode_enabled"
    private val prefDefaultBPM = "default_bpm"
    private val defaultBPMValue = 120

    // Usiamo esplicitamente SwitchCompat per evitare il crash
    private lateinit var darkModeSwitch: SwitchCompat
    private lateinit var bpmInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Applica il tema PRIMA di super.onCreate
        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean(prefDarkMode, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_impostazioni)

        sharedPrefs = prefs

        // 2. Inizializzazione componenti con ID precisi
        darkModeSwitch = findViewById(R.id.switch_dark_mode)
        bpmInput = findViewById(R.id.input_default_bpm)
        val saveButton: Button = findViewById(R.id.btn_save_settings)

        // 3. Configura navigazione
        setupNavigation()

        // 4. Carica dati
        darkModeSwitch.isChecked = isDark
        bpmInput.setText(sharedPrefs.getInt(prefDefaultBPM, defaultBPMValue).toString())

        // 5. Listener
        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            applyTheme(isChecked)
        }

        saveButton.setOnClickListener {
            saveBpmSetting()
        }
    }

    private fun applyTheme(isDarkModeEnabled: Boolean) {
        sharedPrefs.edit().putBoolean(prefDarkMode, isDarkModeEnabled).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        // Non serve recreate() qui perché setDefaultNightMode lo fa già internamente
    }

    private fun setupNavigation() {
        findViewById<Button>(R.id.btn_to_metronome).setOnClickListener {
            val intent = Intent(this, MetronomoActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
            finish()
        }
        findViewById<Button>(R.id.btn_to_tuner).setOnClickListener {
            val intent = Intent(this, AccordatoreActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
            finish()
        }
    }

    private fun saveBpmSetting() {
        val inputStr = bpmInput.text.toString()
        val newBpm = inputStr.toIntOrNull()

        if (newBpm != null && newBpm in 40..240) {
            sharedPrefs.edit().putInt(prefDefaultBPM, newBpm).apply()

            // Invia l'aggiornamento al Service con la chiave "BPM"
            val updateIntent = Intent(this, MetronomeService::class.java).apply {
                action = MetronomeService.ACTION_UPDATE_SETTINGS
                putExtra("BPM", newBpm) // Deve essere "BPM" per combaciare col Service
            }
            startService(updateIntent)

            Toast.makeText(this, "Salvato!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "BPM non valido", Toast.LENGTH_SHORT).show()
        }
    }
}