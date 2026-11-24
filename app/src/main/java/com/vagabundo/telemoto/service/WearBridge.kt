package com.vagabundo.telemoto.service

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.WearProtocol

/** Telefon → saat: durum (~1 Hz) ve titreşim olayları. Saat yoksa sessizce hiçbir şey yapmaz. */
object WearBridge {
    @Volatile private var nodes: List<Node> = emptyList()
    @Volatile private var nodesAt = 0L
    @Volatile var lastHr: Int = 0; private set
    @Volatile var lastHrAt: Long = 0; private set

    private fun refreshNodes(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - nodesAt < 30_000 && nodesAt != 0L) return
        nodesAt = now
        runCatching {
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes = it }
        }
    }

    private fun send(ctx: Context, path: String, payload: String) {
        refreshNodes(ctx)
        val mc = runCatching { Wearable.getMessageClient(ctx) }.getOrNull() ?: return
        nodes.forEach { n -> runCatching { mc.sendMessage(n.id, path, payload.toByteArray(Charsets.UTF_8)) } }
    }

    fun sendState(ctx: Context, s: LiveState) {
        val ws = WearProtocol.State(
            phase = s.phase.name, mode = s.mode.code, elapsedMs = s.elapsedMs, distanceM = s.distanceM,
            speedKmh = s.speedKmh.toInt(), lean = s.leanDeg.toInt(), ecoScore = s.ecoScore ?: -1,
            maxL = s.maxLeanL.toInt(), maxR = s.maxLeanR.toInt(), hr = currentHr(), stopArmed = s.stopArmed,
            message = s.calibMessage,
        )
        send(ctx, WearProtocol.PATH_STATE, WearProtocol.encode(ws))
    }

    fun sendEvent(ctx: Context, evt: String) = send(ctx, WearProtocol.PATH_EVT, evt)

    fun onHr(bpm: Int) { lastHr = bpm; lastHrAt = SystemClock.elapsedRealtime() }

    /** Son 10 sn içinde gelen nabız, yoksa 0. */
    fun currentHr(): Int = if (SystemClock.elapsedRealtime() - lastHrAt < 10_000) lastHr else 0
}

/** Saat → telefon komutları. */
class PhoneWearService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        val msg = String(e.data, Charsets.UTF_8)
        when (e.path) {
            WearProtocol.PATH_HR -> msg.toIntOrNull()?.let { WearBridge.onHr(it) }
            WearProtocol.PATH_CMD -> handleCmd(msg)
        }
    }

    private fun handleCmd(cmd: String) {
        val app = application as TelemotoApp
        val phase = RideSession.flow.value.phase
        if (cmd == WearProtocol.CMD_PING) { WearBridge.sendState(this, RideSession.flow.value); return }
        val (action, mode) = when (cmd) {
            WearProtocol.CMD_START -> RecordingService.ACTION_CALIBRATE_AND_START to (if (phase == Phase.IDLE) app.prefs.value.lastMode else null)
            WearProtocol.CMD_START_PERF -> RecordingService.ACTION_CALIBRATE_AND_START to RideMode.PERF
            WearProtocol.CMD_START_ECO -> RecordingService.ACTION_CALIBRATE_AND_START to RideMode.ECO
            WearProtocol.CMD_MARK -> RecordingService.ACTION_MARK to null
            WearProtocol.CMD_STOP -> RecordingService.ACTION_STOP to null
            else -> return
        }
        try {
            RecordingService.send(this, action, if (phase == Phase.IDLE) mode else null)
        } catch (e: Exception) {
            // Android 12+: uygulama arka plandayken ön plan servisi başlatılamaz → telefonda uygulamayı aç
            WearBridge.sendEvent(this, WearProtocol.EVT_NEED_APP)
        }
    }
}
