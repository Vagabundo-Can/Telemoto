package com.vagabundo.telemoto.wear

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Telefondan gelen son durum + sürüş sonu özeti + geçici mesaj. */
data class WatchUi(
    val state: WearProtocol.State = WearProtocol.State(),
    val stateAt: Long = 0L,
    val summary: List<String>? = null,
    val notice: String? = null,
    val noticeAt: Long = 0L,
) {
    val connected get() = stateAt != 0L && SystemClock.elapsedRealtime() - stateAt < 6000
    val recording get() = connected && state.phase == "RECORDING"
}

object WatchState {
    val ui = MutableStateFlow(WatchUi())
    val flow: StateFlow<WatchUi> = ui

    fun onState(ctx: Context, s: WearProtocol.State) {
        val prev = ui.value
        ui.value = prev.copy(state = s, stateAt = SystemClock.elapsedRealtime())
        if (prev.state.phase != s.phase) {
            ctx.getSharedPreferences("tile", Context.MODE_PRIVATE).edit()
                .putString("phase", s.phase).putString("mode", s.mode).apply()
            requestTile(ctx)
        }
    }

    fun onSummary(ctx: Context, lines: List<String>) {
        ui.value = ui.value.copy(summary = lines)
        ctx.getSharedPreferences("tile", Context.MODE_PRIVATE).edit().putString("last", lines.joinToString(" · ")).apply()
        requestTile(ctx)
    }

    fun notice(msg: String?) { ui.value = ui.value.copy(notice = msg, noticeAt = SystemClock.elapsedRealtime()) }
    fun clearSummary() { ui.value = ui.value.copy(summary = null) }

    private fun requestTile(ctx: Context) = runCatching { TileService.getUpdater(ctx).requestUpdate(TelemotoTile::class.java) }
}

/** Saat → telefon. */
object PhoneLink {
    @Volatile private var nodes: List<Node> = emptyList()
    @Volatile private var nodesAt = 0L

    private fun send(ctx: Context, path: String, payload: String) {
        val now = SystemClock.elapsedRealtime()
        if (nodes.isEmpty() || now - nodesAt > 20_000) {
            nodesAt = now
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { ns ->
                nodes = ns
                ns.forEach { n -> Wearable.getMessageClient(ctx).sendMessage(n.id, path, payload.toByteArray()) }
            }
            return
        }
        nodes.forEach { n -> Wearable.getMessageClient(ctx).sendMessage(n.id, path, payload.toByteArray()) }
    }

    fun cmd(ctx: Context, c: String) = runCatching { send(ctx, WearProtocol.PATH_CMD, c) }
    fun hr(ctx: Context, bpm: Int) = runCatching { send(ctx, WearProtocol.PATH_HR, bpm.toString()) }
}

/** Titreşim kalıpları: sürerken ekrana bakmadan geri bildirim. */
object Haptics {
    private fun vib(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(VibratorManager::class.java)).defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)

    private fun pattern(ctx: Context, vararg ms: Long) {
        val timings = LongArray(ms.size + 1); timings[0] = 0
        ms.forEachIndexed { i, v -> timings[i + 1] = v }
        runCatching { vib(ctx).vibrate(VibrationEffect.createWaveform(timings, -1)) }
    }

    fun short(ctx: Context) = pattern(ctx, 80)
    fun double(ctx: Context) = pattern(ctx, 70, 90, 70)
    fun triple(ctx: Context) = pattern(ctx, 70, 90, 70, 90, 70)
    fun long(ctx: Context) = pattern(ctx, 450)
    fun coach(ctx: Context) = pattern(ctx, 160)
    fun alarm(ctx: Context) = pattern(ctx, 700, 200, 700)

    fun forEvent(ctx: Context, evt: String) {
        when (evt.substringBefore('|')) {
            WearProtocol.EVT_CALIBRATED -> short(ctx)
            WearProtocol.EVT_STARTED -> double(ctx)
            WearProtocol.EVT_MARKED -> double(ctx)
            WearProtocol.EVT_STOP_ARMED -> triple(ctx)
            WearProtocol.EVT_STOPPED -> long(ctx)
            WearProtocol.EVT_ECO_HARSH -> coach(ctx)
            WearProtocol.EVT_GPS_LOST -> alarm(ctx)
            WearProtocol.EVT_CAL_FAIL -> triple(ctx)
            WearProtocol.EVT_NEED_APP -> alarm(ctx)
        }
    }
}

/** Telefondan gelen mesajlar (uygulama kapalıyken de çalışır). */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        val msg = String(e.data, Charsets.UTF_8)
        when (e.path) {
            WearProtocol.PATH_STATE -> WearProtocol.decode(msg)?.let { WatchState.onState(this, it) }
            WearProtocol.PATH_EVT -> {
                Haptics.forEvent(this, msg)
                when (msg.substringBefore('|')) {
                    WearProtocol.EVT_STOPPED -> {
                        val p = msg.split('|')
                        val mode = if (p.getOrNull(1) == "ECO") "Ekonomi" else "Performans"
                        WatchState.onSummary(this, listOf("Sürüş bitti · $mode", "${p.getOrNull(2) ?: "?"} km", p.getOrNull(3) ?: "", p.getOrNull(4) ?: ""))
                    }
                    WearProtocol.EVT_NEED_APP -> WatchState.notice("Telefonda Telemoto'yu aç")
                    WearProtocol.EVT_GPS_LOST -> WatchState.notice("GPS kayboldu")
                    WearProtocol.EVT_CAL_FAIL -> WatchState.notice("Kalibrasyon başarısız")
                    WearProtocol.EVT_MARKED -> WatchState.notice("İşaretlendi")
                }
            }
        }
    }
}
