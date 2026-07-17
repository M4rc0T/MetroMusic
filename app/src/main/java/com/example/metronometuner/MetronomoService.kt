package com.example.metronometuner

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import androidx.core.content.ContextCompat
import android.os.VibrationEffect

class MetronomeService : Service() {

    companion object {
        const val ACTION_START_METRONOME = "ACTION_START_METRONOME"
        const val ACTION_UPDATE_SETTINGS = "ACTION_UPDATE_SETTINGS"
        const val ACTION_STOP_METRONOME = "ACTION_STOP_METRONOME"
        @Volatile var isRunning = false // Nuova variabile per lo stato globale
    }

    private val NOTIFICATION_CHANNEL_ID = "metronome_channel_id"
    private val NOTIFICATION_ID = 101

    @Volatile private var isTicking = false
    private var bpm = 120
    private var beatsPerMeasure = 4
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var tickingJob: Job? = null
    private var currentBeat = 0

    private lateinit var toneGenerator: ToneGenerator
    private lateinit var vibrator: Vibrator
    private lateinit var audioManager: AudioManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        Log.i("MetronomeService", "Servizio inizializzato.")
    }

    @SuppressLint("ForegroundServiceType")
    @Suppress("DEPRECATION", "MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_STOP_METRONOME -> {
                stopTickingLoop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_METRONOME, ACTION_UPDATE_SETTINGS -> {
                // USIAMO LE CHIAVI CHE ARRIVANO DALLA ACTIVITY
                // Se la chiave "BPM" è vuota, mantiene il valore attuale (bpm)
                bpm = intent.getIntExtra("BPM", bpm)
                beatsPerMeasure = intent.getIntExtra("BEATS", beatsPerMeasure)

                // Aggiorna/Avvia il foreground
                startForeground(NOTIFICATION_ID, createNotification())

                // Notifica al sistema che la notifica è cambiata (per aggiornare il testo dei BPM)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, createNotification())

                if (action == ACTION_START_METRONOME) {
                    if (!isTicking) {
                        requestAudioFocusAndStart()
                    }
                }
                // NOTA: Abbiamo tolto il reset di currentBeat = 0.
                // Così se cambi BPM mentre suoni, il tempo non "salta".
            }
        }
        return START_STICKY
    }

    private fun requestAudioFocusAndStart() {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA) // CORRETTO: compatibile con tutto
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        stopTickingLoop()
                    }
                }
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            startTickingLoop()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        stopTickingLoop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        toneGenerator.release()
    }

    private fun startTickingLoop() {
        if (isTicking) return
        isTicking = true
        MetronomeService.isRunning = true // COMUNICA ALL'APP: Il metronomo è partito
        currentBeat = 0

        tickingJob = serviceScope.launch {
            while (isActive && isTicking) {
                val timePerBeat = (60000L / bpm).toLong()
                currentBeat = (currentBeat % beatsPerMeasure) + 1

                val toneType = if (currentBeat == 1) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK
                toneGenerator.startTone(toneType, 50)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }

                delay(timePerBeat)
            }
        }
    }

    private fun stopTickingLoop() {
        isTicking = false
        MetronomeService.isRunning = false // COMUNICA ALL'APP: Il metronomo si è fermato
        tickingJob?.cancel()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Riproduzione Metronomo",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Intent per riaprire l'app cliccando sulla notifica
        val notificationIntent = Intent(this, MetronomoActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // NUOVO: Intent per il tasto STOP direttamente nella notifica
        val stopIntent = Intent(this, MetronomeService::class.java).apply {
            action = ACTION_STOP_METRONOME
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_content, bpm))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Aggiunge il tasto STOP nella notifica
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPendingIntent)
            .build()
    }
}