package com.vagabundo.telemoto.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private val Amber = Color(0xFFFFB020)
private val Ink = Color(0xFFE8EDF2)
private val Mute = Color(0xFF9AA6B2)
private val Red = Color(0xFFFF6B6B)
private val RedFill = Color(0xFFD62D2D)
private val Green = Color(0xFF5BD18B)
private val Left = Color(0xFF6FB1FF)
private val Right = Color(0xFFFF9A57)
private val Surf = Color(0xFF1A2027)
private val Cond = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchApp() } }
    }
}

@Composable
fun WatchApp() {
    val ctx = LocalContext.current
    val ui by WatchState.flow.collectAsState()
    var tick by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf("LAST") }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val need = listOf(Manifest.permission.BODY_SENSORS, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }
    // Ekran açıkken telefona 2 sn'de bir ping: bağlantı durumu ve güncel veri
    LaunchedEffect(Unit) {
        while (true) {
            PhoneLink.cmd(ctx, WearProtocol.CMD_PING)
            tick++
            delay(2000)
        }
    }
    // Kayıt sürüyorsa nabız servisini başlat
    LaunchedEffect(ui.recording) { if (ui.recording) HrService.startIfAllowed(ctx) }
    // Saatin Geri tuşu: kayıt sırasında olay işaretler
    BackHandler(enabled = ui.recording) {
        PhoneLink.cmd(ctx, WearProtocol.CMD_MARK)
        Haptics.short(ctx)
    }

    val notice = ui.notice?.takeIf { SystemClock.elapsedRealtime() - ui.noticeAt < 5000 }
    if (tick < 0) return // tick okunur: bağlantı durumu 2 sn'de bir yeniden değerlendirilir

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        TimeText()
        when {
            ui.summary != null && !ui.recording -> SummaryView(ui.summary!!) { WatchState.clearSummary() }
            !ui.connected -> Disconnected()
            ui.recording -> RecordingView(ui.state)
            else -> ReadyView(ui.state, mode, onMode = { mode = it }) {
                val c = when (mode) { "PERF" -> WearProtocol.CMD_START_PERF; "ECO" -> WearProtocol.CMD_START_ECO; else -> WearProtocol.CMD_START }
                PhoneLink.cmd(ctx, c)
                Haptics.short(ctx)
            }
        }
        if (notice != null) {
            Text(
                notice, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).clip(RoundedCornerShape(50)).background(Amber).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Disconnected() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
        Text("TELEMOTO", color = Amber, fontFamily = Cond, fontSize = 20.sp, letterSpacing = 2.sp)
        Text("Telefona ulaşılamıyor", color = Ink, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text("Bluetooth açık mı, telefonda Telemoto kurulu mu?", color = Mute, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ReadyView(s: WearProtocol.State, mode: String, onMode: (String) -> Unit, onStart: () -> Unit) {
    val warming = s.phase != "IDLE"
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (warming) s.message.ifBlank { "Hazırlanıyor" } else "Motoru dik tut",
            color = Mute, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.width(150.dp).padding(top = 18.dp),
        )
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(Amber)
                .semantics { role = Role.Button; contentDescription = "Kalibre et ve başlat" }
                .clickable(onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BAŞLAT", color = Color.Black, fontFamily = Cond, fontSize = 24.sp)
                Text(if (s.phase == "READY") "kalibre ✓" else "kalibre + kayıt", color = Color.Black, fontSize = 10.sp)
            }
        }
        if (!warming) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("PERF" to "Perf", "ECO" to "Eko", "LAST" to "Son").forEach { (id, label) ->
                    val on = id == mode
                    Text(
                        label, color = if (on) Color.Black else Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Amber else Surf)
                            .clickable(role = Role.RadioButton) { onMode(id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingView(s: WearProtocol.State) {
    val ctx = LocalContext.current
    val eco = s.mode == "ECO"
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(top = 26.dp, bottom = 10.dp)) {
        val sec = s.elapsedMs / 1000
        Text(
            String.format(Locale.US, "● %02d:%02d:%02d · %.1f km", sec / 3600, (sec / 60) % 60, sec % 60, s.distanceM / 1000),
            color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        // Büyük dokunma alanı: olay işaretle
        Box(
            Modifier.padding(top = 4.dp).size(118.dp).clip(CircleShape).border(3.dp, Amber, CircleShape).background(Surf)
                .semantics { role = Role.Button; contentDescription = "Olay işaretle" }
                .clickable { PhoneLink.cmd(ctx, WearProtocol.CMD_MARK); Haptics.short(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (eco) {
                    Text(if (s.ecoScore >= 0) "${s.ecoScore}" else "—", color = Green, fontFamily = Cond, fontSize = 40.sp)
                    Text("akıcılık · ${s.speedKmh} km/h", color = Mute, fontSize = 10.sp)
                } else {
                    val c = if (abs(s.lean) < 3) Ink else if (s.lean > 0) Right else Left
                    Text("${abs(s.lean)}°", color = c, fontFamily = Cond, fontSize = 40.sp)
                    Text("${s.speedKmh} km/h · L${s.maxL} S${s.maxR}", color = Mute, fontSize = 10.sp)
                }
                Text("OLAY İŞARETLE", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (s.hr > 0) Text("♥ ${s.hr}", color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        HoldToStop(Modifier.padding(top = 4.dp).width(120.dp).height(34.dp)) {
            PhoneLink.cmd(ctx, WearProtocol.CMD_STOP)
            Haptics.long(ctx)
        }
    }
}

@Composable
private fun HoldToStop(modifier: Modifier, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.clip(RoundedCornerShape(50)).background(RedFill)
            .semantics { role = Role.Button; contentDescription = "Durdur, basılı tut" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(1500, easing = LinearEasing))
                        onDone()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(150)) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.White.copy(alpha = 0.3f), size = Size(size.width * progress.value, size.height)) }
        Text("DURDUR (bas-tut)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SummaryView(lines: List<String>, onDismiss: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(24.dp).clickable(onClick = onDismiss),
    ) {
        Text(lines.getOrElse(0) { "" }, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(lines.getOrElse(1) { "" }, color = Ink, fontFamily = Cond, fontSize = 34.sp)
        Text(lines.getOrElse(2) { "" }, color = Ink, fontSize = 15.sp, textAlign = TextAlign.Center)
        Text(lines.getOrElse(3) { "" }, color = Mute, fontSize = 12.sp, textAlign = TextAlign.Center)
        Text("kapatmak için dokun", color = Mute, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
