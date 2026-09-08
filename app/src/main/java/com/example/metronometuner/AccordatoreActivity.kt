package com.example.metronometuner

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.log
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI
import kotlin.concurrent.thread

class AccordatoreActivity : AppCompatActivity() {
    private var audioRecorder: AudioRecorder? = null
    private lateinit var frequencyTextView: TextView
    private lateinit var rootLayout: View
    private lateinit var a4RefDisplay: TextView
    private val PREF_KEY_A4_FREQ = "a4_reference_frequency"
    private val DEFAULT_A4_FREQ = 440.0f
    private var referenceFrequency = DEFAULT_A4_FREQ.toDouble()
    private val RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO
    private lateinit var noteDisplay: TextView
    private lateinit var tuningBar: ProgressBar
    private val PREF_NAME = "AppPreferences"

    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlayingReference = false
    private var currentThread: Thread? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startTunerFunctionality()
            else Toast.makeText(this, getString(R.string.error_permission_denied), Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode_enabled", false)
        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_accordatore)

        setupNavigation()

        rootLayout = findViewById(R.id.root_accordatore)
        frequencyTextView = findViewById(R.id.frequency_display)
        noteDisplay = findViewById(R.id.note_display)
        tuningBar = findViewById(R.id.tuning_bar)
        a4RefDisplay = findViewById(R.id.a4_ref_display)
        val changeA4Button: Button = findViewById(R.id.btn_change_a4)
        val noteChooserButton: Button = findViewById(R.id.btn_note_chooser)

        referenceFrequency = prefs.getFloat(PREF_KEY_A4_FREQ, DEFAULT_A4_FREQ).toDouble()
        a4RefDisplay.text = getString(R.string.a4_display_format, referenceFrequency)

        changeA4Button.setOnClickListener { showA4ChangeDialog() }
        noteChooserButton.setOnClickListener { showNoteChooserDialog() }

        checkMicrophonePermission()
    }

    private fun setupNavigation() {
        findViewById<Button>(R.id.btn_to_metronome).setOnClickListener {
            val intent = Intent(this, MetronomoActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
            finish()
        }
        findViewById<Button>(R.id.btn_to_tuner).setOnClickListener {
            // Già qui, non fare nulla o rinfresca
        }
        findViewById<Button>(R.id.btn_to_settings).setOnClickListener {
            val intent = Intent(this, ImpostazioniActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
            finish()
        }
    }

    /*MODIFICA RICHIESTA PERMESSO MICROFONO*/
    private fun checkMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(this, RECORD_AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED -> {
                startTunerFunctionality()
            }
            // CASO 1: L'utente ha già negato una volta, spieghiamo perché serve
            shouldShowRequestPermissionRationale(RECORD_AUDIO_PERMISSION) -> {
                showPermissionRationaleDialog()
            }
            // CASO 2: Prima volta o negazione definitiva
            else -> {
                requestPermissionLauncher.launch(RECORD_AUDIO_PERMISSION)
            }
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accesso al Microfono")
            .setMessage("L'accordatore necessita del microfono per analizzare le frequenze del tuo strumento. Senza questo permesso, l'app non può funzionare.")
            .setPositiveButton("Riprova") { _, _ -> requestPermissionLauncher.launch(RECORD_AUDIO_PERMISSION) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun startTunerFunctionality() {
        if (audioRecorder != null && audioRecorder?.isProcessing == true) return
        audioRecorder = AudioRecorder { detectedFrequency ->
            val (noteName, octave, cents) = frequencyToNote(detectedFrequency)
            runOnUiThread {
                frequencyTextView.text = String.format("%.1f Hz", detectedFrequency)

                // Manteniamo solo il nome della nota e l'ottava, senza simboli extra
                noteDisplay.text = "$noteName$octave"

                val progress = (cents + 50f).toInt().coerceIn(0, 100)
                tuningBar.progress = progress

                // Tolleranza standard
                val isTuned = cents in -8f..8f

                if (isTuned) {
                    // Feedback visivo solo tramite colori
                    rootLayout.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    tuningBar.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                } else {
                    // Ripristino sfondo neutro
                    rootLayout.setBackgroundResource(android.R.color.transparent)
                    tuningBar.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
            }
        }
        audioRecorder?.startRecording()
    }

    // --- DIALOG PERSISTENTE ---
    private fun showNoteChooserDialog() {
        val notes = arrayOf("Do (C)", "Do# (C#)", "Re (D)", "Re# (D#)", "Mi (E)", "Fa (F)", "Fa# (F#)", "Sol (G)", "Sol# (G#)", "La (A)", "La# (A#)", "Si (B)")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Scegli nota di riferimento")
        builder.setSingleChoiceItems(notes, -1) { _, which ->
            val freq = referenceFrequency * 2.0.pow((which - 9) / 12.0)
            playReferenceTone(freq)
        }
        builder.setNeutralButton("STOP SUONO") { _, _ -> stopReferenceTone() }
        builder.setPositiveButton("CHIUDI") { dialog, _ ->
            stopReferenceTone()
            dialog.dismiss()
        }
        val dialog = builder.create()
        dialog.setOnCancelListener { stopReferenceTone() }
        dialog.show()
    }

    private fun playReferenceTone(frequency: Double) {
        stopReferenceTone()
        isPlayingReference = true
        val sampleRate = 44100
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        currentThread = thread(start = true) {
            val buffer = ShortArray(minBufferSize)
            var angle = 0.0
            while (isPlayingReference) {
                for (i in buffer.indices) {
                    buffer[i] = (sin(angle) * Short.MAX_VALUE).toInt().toShort()
                    angle += 2.0 * PI * frequency / sampleRate
                    if (angle > 2.0 * PI) angle -= 2.0 * PI
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }
    }

    private fun stopReferenceTone() {
        isPlayingReference = false
        currentThread?.interrupt()
        currentThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) { }
        audioTrack = null
    }

    override fun onPause() {
        super.onPause()
        releaseResources()
    }

    override fun onStop() {
        super.onStop()
        releaseResources()
    }

    private fun releaseResources() {
        audioRecorder?.stopRecording()
        audioRecorder = null
        stopReferenceTone()
    }

    val NOTES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun frequencyToNote(frequency: Float): Triple<String, Int, Float> {
        if (frequency <= 0) return Triple("--", 0, 0f)
        val n = 12 * (log(frequency.toDouble() / referenceFrequency, 2.0)).toFloat()
        val semitoneIndex = Math.round(n).toInt()
        val cents = (n - semitoneIndex) * 100
        val noteIndex = ((semitoneIndex + 9) % 12 + 12) % 12
        val octave = 4 + (semitoneIndex + 9) / 12
        return Triple(NOTES[noteIndex], octave, cents)
    }

    private fun showA4ChangeDialog() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val currentFreq = prefs.getFloat(PREF_KEY_A4_FREQ, DEFAULT_A4_FREQ)
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(currentFreq.toString())
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.a4_dialog_title))
            .setMessage(getString(R.string.a4_dialog_message))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newFreq = input.text.toString().toFloatOrNull()
                if (newFreq != null && newFreq >= 400f && newFreq <= 460f) {
                    prefs.edit().putFloat(PREF_KEY_A4_FREQ, newFreq).apply()
                    referenceFrequency = newFreq.toDouble()
                    a4RefDisplay.text = getString(R.string.a4_display_format, referenceFrequency)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}