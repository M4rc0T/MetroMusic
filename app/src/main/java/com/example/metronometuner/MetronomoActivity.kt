package com.example.metronometuner

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

class MetronomoActivity : AppCompatActivity() {

    // Componenti UI
    private lateinit var bpmDisplay: TextView
    private lateinit var bpmSeekBar: SeekBar
    private lateinit var startStopButton: Button
    private lateinit var timeSignatureSpinner: Spinner

    // Altri elementi di navigazione
    private lateinit var tunerButton: Button
    private lateinit var settingsButton: Button

    // Stato del metronomo
    private var isMetronomeRunning = false
    private var currentBPM = 120
    private var timeSignature = "4/4"

    // Chiavi SharedPreferences
    private val PREF_NAME = "AppPreferences"
    private val PREF_KEY_DEFAULT_BPM = "default_bpm"

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode_enabled", false)
        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_metronomo)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }

        bpmDisplay = findViewById(R.id.bpm_display)
        bpmSeekBar = findViewById(R.id.bpm_seekbar)
        startStopButton = findViewById(R.id.btn_start_stop)
        timeSignatureSpinner = findViewById(R.id.time_signature_spinner)

        tunerButton = findViewById(R.id.btn_to_tuner)
        settingsButton = findViewById(R.id.btn_to_settings)

        loadInitialBPM()
        setupTimeSignatureSpinner()
        setupListeners()
    }

    private fun navigateToTuner() {
        val intent = Intent(this, AccordatoreActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
        finish()
    }

    private fun navigateToSettings() {
        val intent = Intent(this, ImpostazioniActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
        finish()
    }


    private fun loadInitialBPM() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        currentBPM = prefs.getInt(PREF_KEY_DEFAULT_BPM, 120)
        bpmDisplay.text = currentBPM.toString()
        bpmSeekBar.progress = currentBPM
    }

    private fun setupTimeSignatureSpinner() {
        val timeSignatures = arrayOf("4/4", "3/4", "6/8", "2/4")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timeSignatures)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        timeSignatureSpinner.adapter = adapter

        timeSignatureSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                timeSignature = parent.getItemAtPosition(position).toString()
                if (isMetronomeRunning) {
                    sendUpdateToService()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        timeSignature = timeSignatures[0]
    }

    private fun setupListeners() {
        tunerButton.setOnClickListener { navigateToTuner() }
        settingsButton.setOnClickListener { navigateToSettings() }

        bpmSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBPM = progress
                bpmDisplay.text = currentBPM.toString()
                if (isMetronomeRunning) {
                    sendUpdateToService()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        startStopButton.setOnClickListener {
            toggleMetronomeService()
        }
    }

    // MODIFICATA: Per usare lo stop totale e le costanti del Service
    private fun toggleMetronomeService() {
        val serviceIntent = Intent(this, MetronomeService::class.java)

        if (isMetronomeRunning) {
            // Invece di stopService, mandiamo l'azione di STOP totale definita nel Service
            serviceIntent.action = MetronomeService.ACTION_STOP_METRONOME
            startService(serviceIntent)

            isMetronomeRunning = false
            startStopButton.text = getString(R.string.btn_start_metronome)
            startStopButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        } else {
            serviceIntent.action = MetronomeService.ACTION_START_METRONOME
            serviceIntent.putExtra("BPM", currentBPM)
            val parts = timeSignature.split("/")
            val beats = parts.getOrElse(0) { "4" }.toIntOrNull() ?: 4
            serviceIntent.putExtra("BEATS", beats)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }

            isMetronomeRunning = true
            startStopButton.text = getString(R.string.btn_stop_metronome)
            startStopButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        }
    }

    // MODIFICATA: Per usare le costanti del Service
    private fun sendUpdateToService() {
        val updateIntent = Intent(this, MetronomeService::class.java).apply {
            action = MetronomeService.ACTION_UPDATE_SETTINGS
            putExtra("BPM", currentBPM)
            val parts = timeSignature.split("/")
            val beats = parts.getOrElse(0) { "4" }.toIntOrNull() ?: 4
            putExtra("BEATS", beats)
        }
        startService(updateIntent)
    }

    override fun onResume() {
        super.onResume()

        // 1. CARICAMENTO BPM AGGIORNATO
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        currentBPM = prefs.getInt(PREF_KEY_DEFAULT_BPM, 120)

        // 2. AGGIORNAMENTO UI
        bpmDisplay.text = currentBPM.toString()
        bpmSeekBar.progress = currentBPM

        // 3. Controllo stato globale del Service per il bottone
        isMetronomeRunning = MetronomeService.isRunning

        if (isMetronomeRunning) {
            startStopButton.text = getString(R.string.btn_stop_metronome)
            startStopButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        } else {
            startStopButton.text = getString(R.string.btn_start_metronome)
            startStopButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        }
    }
}