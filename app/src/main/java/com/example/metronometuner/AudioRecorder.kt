package com.example.metronometuner

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.* // Importa Coroutines

import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchProcessor


class AudioRecorder(private val listener: (Float) -> Unit) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default) // Scope per l'acquisizione in background

    // Parametri standard di registrazione
    private val sampleRate = 22050
    private val bufferSize = 1024
    private val overLap = 0

    private var dispatcher: AudioDispatcher? = null
    var isProcessing = false

    fun startRecording() {
        if (isProcessing) return
        isProcessing = true

        // 1. Creazione del Dispatcher (Gestore di AudioRecord TarsosDSP)
        try {
            // AudioDispatcherFactory gestisce l'inizializzazione di AudioRecord
            dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(
                sampleRate,
                bufferSize,
                overLap
            )
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Errore inizializzazione Microfono: ${e.message}")
            isProcessing = false
            return
        }

        // 2. Definizione dell'Handler di Rilevazione del Pitch
        val pitchHandler = PitchDetectionHandler { result, _ ->
            val detectedPitch = result.pitch

            // TarsosDSP a volte restituisce PITCH_DETECTION_RESULT_NOT_YET_AVAILABLE
            if (detectedPitch != -1f) {
                // 3. Invia la frequenza reale all'Activity tramite il listener
                listener(detectedPitch)
            }
        }

        // 4. Aggiunta del Processore FFT (YIN è un algoritmo efficiente per Pitch Detection)
        val pitchProcessor = PitchProcessor(
            PitchProcessor.PitchEstimationAlgorithm.YIN,
            sampleRate.toFloat(),
            bufferSize,
            pitchHandler
        )
        dispatcher?.addAudioProcessor(pitchProcessor)

        // 5. Avvia l'elaborazione del segnale in un thread separato gestito da Tarsos
        // Usiamo la coroutine per avviare il dispatcher in background
        coroutineScope.launch {
            Log.i("AudioRecorder", "Analisi Pitch avviata.")
            dispatcher?.run()
            // Questo blocco di codice verrà eseguito solo quando il dispatcher viene fermato
            isProcessing = false
        }
    }

    fun stopRecording() {
        if (!isProcessing) return

        // 1. Arresta il Dispatcher (che ferma l'AudioRecord)
        dispatcher?.stop()

        // 2. Cancella lo scope della coroutine
        coroutineScope.cancel()

        isProcessing = false
        Log.i("AudioRecorder", "Analisi Pitch fermata.")
    }
}