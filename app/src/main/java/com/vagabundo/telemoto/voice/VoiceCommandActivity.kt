package com.vagabundo.telemoto.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.service.Phase
import com.vagabundo.telemoto.service.RecordingService
import com.vagabundo.telemoto.service.RideSession

/**
 * Sena / Bluetooth kulaklığın "sesli asistan" tuşu → Android VOICE_COMMAND → burası.
 * Telemoto aktif değilse istek hemen Gemini'ye aktarılır. Aktifse komut dinlenir:
 *   "kalibre et", "kaydı başlat", "olay işaretle", "kaydı durdur"
 * Tanınmayan her şey Gemini'ye aktarılır.
 */
class VoiceCommandActivity : Activity() {

    private val app get() = application as TelemotoApp
    private var recognizer: SpeechRecognizer? = null
    private var headset: BluetoothHeadset? = null
    private var headsetDevice: BluetoothDevice? = null
    private val main = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = RideSession.flow.value.phase != Phase.IDLE
        val canListen = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            SpeechRecognizer.isRecognitionAvailable(this)
        if (!app.prefs.value.voiceCommands || !active || !canListen) {
            forwardToAssistant()
            return
        }
        openBluetoothMic()
        // SCO bağlantısı için kısa bekleme
        main.postDelayed({ listen() }, 600)
        main.postDelayed({ done() }, 12_000)
    }

    private fun listen() {
        if (finished) return
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                handle(list)
            }
            override fun onError(error: Int) {
                // Cihaz-içi tanıyıcı Türkçeyi bilmiyorsa sunucu tanıyıcıya bir kez düş
                if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && !triedCloud) {
                    triedCloud = true
                    recognizer?.destroy()
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this@VoiceCommandActivity).also {
                        it.setRecognitionListener(this); it.startListening(intentTr())
                    }
                    return
                }
                app.speaker.say("Anlayamadım")
                done()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intentTr())
    }

    private var triedCloud = false

    private fun intentTr() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }

    private fun handle(results: List<String>) {
        val cmd = results.asSequence().map { VoiceParser.parse(it) }.firstOrNull { it != null }
        when (cmd) {
            VoiceParser.Cmd.CALIBRATE -> RecordingService.send(this, RecordingService.ACTION_CALIBRATE)
            VoiceParser.Cmd.START -> RecordingService.send(this, RecordingService.ACTION_CALIBRATE_AND_START)
            VoiceParser.Cmd.MARK -> RecordingService.send(this, RecordingService.ACTION_MARK)
            VoiceParser.Cmd.STOP -> RecordingService.send(this, RecordingService.ACTION_STOP)
            null -> { closeBluetoothMic(); forwardToAssistant(); return }
        }
        done()
    }


    // ---------- Gemini'ye aktarım ----------

    private fun forwardToAssistant() {
        val candidates = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage("com.google.android.apps.bard"),
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_ASSIST).setPackage("com.google.android.apps.bard"),
            Intent(Intent.ACTION_ASSIST).setPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_ASSIST),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val cn = i.resolveActivity(packageManager)
            if (cn != null && cn.packageName != packageName) {
                runCatching { startActivity(i) }.onSuccess { finished = true; finish(); return }
            }
        }
        finished = true
        finish()
    }

    // ---------- Bluetooth mikrofon ----------

    @SuppressLint("MissingPermission")
    private fun openBluetoothMic() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?.let { runCatching { am.setCommunicationDevice(it) } }
        }
        val hasBt = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!hasBt) return
        val adapter: BluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val hs = proxy as BluetoothHeadset
                headset = hs
                headsetDevice = hs.connectedDevices.firstOrNull()
                headsetDevice?.let { runCatching { hs.startVoiceRecognition(it) } }
            }
            override fun onServiceDisconnected(profile: Int) { headset = null }
        }, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun closeBluetoothMic() {
        runCatching { headsetDevice?.let { headset?.stopVoiceRecognition(it) } }
        runCatching {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            headset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        }
        headset = null
        if (Build.VERSION.SDK_INT >= 31) runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).clearCommunicationDevice() }
    }

    private fun done() {
        if (finished) return
        finished = true
        recognizer?.destroy()
        recognizer = null
        closeBluetoothMic()
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (!finished) { recognizer?.destroy(); closeBluetoothMic() }
        super.onDestroy()
    }
}
