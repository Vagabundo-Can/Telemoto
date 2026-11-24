package com.vagabundo.telemoto.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.service.LiveState
import com.vagabundo.telemoto.service.Phase
import com.vagabundo.telemoto.service.RecordingService
import com.vagabundo.telemoto.service.RideSession
import com.vagabundo.telemoto.ui.Amber
import com.vagabundo.telemoto.ui.Condensed
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.components.AccelBar
import com.vagabundo.telemoto.ui.components.BigValue
import com.vagabundo.telemoto.ui.components.Dot
import com.vagabundo.telemoto.ui.components.GCircle
import com.vagabundo.telemoto.ui.components.GhostButton
import com.vagabundo.telemoto.ui.components.HoldButton
import com.vagabundo.telemoto.ui.components.Ico
import com.vagabundo.telemoto.ui.components.LeanGauge
import com.vagabundo.telemoto.ui.components.Panel
import com.vagabundo.telemoto.ui.components.PrimaryButton
import com.vagabundo.telemoto.ui.components.ScoreRing
import com.vagabundo.telemoto.ui.components.SmallLabel
import com.vagabundo.telemoto.ui.components.TmIcon
import com.vagabundo.telemoto.ui.components.ecoLabel
import com.vagabundo.telemoto.ui.components.f1
import com.vagabundo.telemoto.ui.components.f2
import com.vagabundo.telemoto.ui.components.hms
import com.vagabundo.telemoto.ui.components.signedG
import kotlin.math.abs

@Composable
fun LiveScreen(onExit: () -> Unit, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val view = LocalView.current
    val s by RideSession.flow.collectAsState()
    var dim by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    LaunchedEffect(dim) {
        view.keepScreenOn = !dim
        activity?.window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    LaunchedEffect(s.phase, s.lastSavedId) {
        if (s.phase == Phase.IDLE) {
            val id = s.lastSavedId
            if (id != null) onSaved(id) else onExit()
        }
    }
    BackHandler(enabled = s.phase != Phase.RECORDING) {
        RecordingService.send(ctx, RecordingService.ACTION_CANCEL)
        onExit()
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        when (s.phase) {
            Phase.RECORDING -> RecordingView(s, onDim = { dim = true })
            Phase.SAVING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Kaydediliyor ve analiz ediliyor…", color = LocalTm.current.ink, fontSize = 18.sp)
            }
            else -> CalibrationView(s)
        }
    }
    if (dim) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable { dim = false },
            contentAlignment = Alignment.Center,
        ) {
            Text("Kayıt sürüyor · dokun", color = Color(0xFF3A434C), fontSize = 14.sp)
        }
    }
}

// ---------- Kalibrasyon ----------

@Composable
private fun CalibrationView(s: LiveState) {
    val t = LocalTm.current
    val ctx = LocalContext.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.fillMaxHeight().aspectRatio(0.95f), padding = 14.dp) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
                    val r = size.minDimension / 2 * 0.94f
                    val c = center
                    drawCircle(t.well, r, c)
                    drawCircle(t.line, r, c, style = Stroke(2.dp.toPx()))
                    drawCircle(t.line, r * 0.66f, c, style = Stroke(1.dp.toPx()))
                    drawCircle(t.green, r * 0.2f, c, style = Stroke(2.dp.toPx()))
                    drawLine(t.line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
                    drawLine(t.line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
                    // Kabarcık: yan eğim yatay eksende (±12°), dikey eksen sabit
                    val bx = c.x + (s.bubbleRollDeg / 12f).coerceIn(-1f, 1f) * r * 0.8f
                    val ok = abs(s.bubbleRollDeg) < 3f
                    drawCircle(if (ok) t.green else Amber, r * 0.15f, Offset(bx, c.y))
                    if (s.phase == Phase.CALIBRATING) {
                        drawArc(Amber, -90f, 360f * s.calibProgress, false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(5.dp.toPx()))
                    }
                }
            }
            Text(
                "Yan eğim ${f1(s.bubbleRollDeg)}° · mount ${f1(s.bubblePitchDeg)}°",
                color = t.mute, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Kalibrasyon · ${s.mode.label}", fontFamily = Condensed, fontSize = 32.sp, color = t.ink)
            Text("Motoru dik tut (yan sehpada değil), gidonu düz bırak, 2 saniye hareket ettirme.", color = t.mute, fontSize = 14.sp)
            Check(abs(s.bubbleRollDeg) < 3f, "Dik (±3°)")
            Check(s.stationary, "Durağan")
            Check(s.gpsFix, if (s.gpsFix) "GPS fix (±${s.gpsAccuracyM.toInt()} m)" else "GPS bekleniyor (kayıt yine başlar)")
            val msgColor = if (s.calib != null) t.green else if (s.phase == Phase.CALIBRATING) t.accInk else t.ink
            Text(s.calibMessage, color = msgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("İptal", Modifier.width(140.dp)) { RecordingService.send(ctx, RecordingService.ACTION_CANCEL) }
                PrimaryButton(
                    if (s.phase == Phase.READY) "Başlat" else "Kalibre et ve başlat",
                    Modifier.weight(1f), enabled = s.phase != Phase.CALIBRATING,
                ) { RecordingService.send(ctx, RecordingService.ACTION_CALIBRATE_AND_START) }
            }
            Text("Sesli: “kalibre et” · “kaydı başlat” · “olay işaretle” · “kaydı durdur”", color = t.mute, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Check(ok: Boolean, text: String) {
    val t = LocalTm.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(16.dp)) {
            if (ok) {
                drawLine(t.green, Offset(size.width * 0.15f, size.height * 0.55f), Offset(size.width * 0.42f, size.height * 0.8f), 2.5.dp.toPx())
                drawLine(t.green, Offset(size.width * 0.42f, size.height * 0.8f), Offset(size.width * 0.88f, size.height * 0.22f), 2.5.dp.toPx())
            } else drawCircle(Amber, size.minDimension / 2.4f, style = Stroke(2.dp.toPx()))
        }
        Text(text, color = t.ink, fontSize = 14.sp)
    }
}

// ---------- Kayıt ----------

@Composable
private fun RecordingView(s: LiveState, onDim: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TelemotoApp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopBar(s)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.mode == RideMode.PERF) PerfPanels(s) else EcoPanels(s, app.prefs.value.th.ecoAccelG)
        }
        Row(Modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Olay işaretle", Modifier.weight(1f)) { RecordingService.send(ctx, RecordingService.ACTION_MARK) }
            GhostButton("Ekranı karart", Modifier.weight(1f), onClick = onDim)
            HoldButton("Durdur (basılı tut)", Modifier.weight(1.4f)) { RecordingService.send(ctx, RecordingService.ACTION_STOP) }
        }
    }
}

@Composable
private fun TopBar(s: LiveState) {
    val t = LocalTm.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("TELEMOTO", fontFamily = Condensed, fontSize = 20.sp, color = t.accInk, letterSpacing = 2.sp)
        val (bg, fg) = if (s.mode == RideMode.ECO) t.ecoBg to t.green else t.perfBg to t.accInk
        Text(s.mode.label.uppercase(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(t.redBg).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(t.red)
            Text("KAYIT ${hms(s.elapsedMs)}", color = t.red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Text("${f1(s.distanceM / 1000)} km", color = t.mute, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TmIcon(Ico.GPS, if (s.gpsFix) t.green else t.red, 14.dp)
            Text(if (s.gpsFix) "±${s.gpsAccuracyM.toInt()} m" else "GPS yok", color = t.mute, fontSize = 12.sp)
        }
        if (s.marks > 0) Text("İşaret ${s.marks}", color = t.mute, fontSize = 12.sp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PerfPanels(s: LiveState) {
    val t = LocalTm.current
    Panel(Modifier.weight(1.25f).fillMaxHeight(), padding = 10.dp) {
        Row(Modifier.fillMaxWidth()) {
            SmallLabel("◀ Sol maks ${s.maxLeanL.toInt()}°", t.left)
            Spacer(Modifier.weight(1f))
            SmallLabel("Sağ maks ${s.maxLeanR.toInt()}° ▶", t.right)
        }
        LeanGauge(s.leanDeg, s.maxLeanL, s.maxLeanR, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigValue("${abs(s.leanDeg).toInt()}°", 54.sp)
            Text(if (abs(s.leanDeg) < 1) "DİK" else if (s.leanDeg > 0) "SAĞ" else "SOL",
                color = if (s.leanDeg >= 0) t.right else t.left, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        }
    }
    Panel(Modifier.weight(1f).fillMaxHeight()) {
        SmallLabel("Hız", modifier = Modifier.align(Alignment.CenterHorizontally))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { BigValue("${s.speedKmh.toInt()}", 100.sp) }
        Text("km/h", color = t.mute, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("Maks ${s.maxSpeedKmh.toInt()}", color = t.mute, fontSize = 12.sp)
            Text("Ort ${s.avgSpeedKmh.toInt()}", color = t.mute, fontSize = 12.sp)
            Text("Viraj ${s.corners}", color = t.mute, fontSize = 12.sp)
        }
    }
    Panel(Modifier.weight(1f).fillMaxHeight(), padding = 10.dp) {
        SmallLabel("G-kuvveti", modifier = Modifier.align(Alignment.CenterHorizontally))
        GCircle(s.gLon, s.gLat, Modifier.weight(1f).fillMaxWidth())
        val c = when { s.gLon <= -0.3f -> t.red; s.gLon >= 0.2f -> t.green; else -> t.ink }
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigValue("${signedG(s.gLon)} g", 30.sp, c)
            Text(if (s.gLon < -0.05f) "FREN" else if (s.gLon > 0.05f) "GAZ" else "", color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text("Yanal ${f2(abs(s.gLat))} g · maks fren ${signedG(s.maxBrakeG)}", color = t.mute, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.EcoPanels(s: LiveState, softG: Float) {
    val t = LocalTm.current
    Panel(Modifier.weight(0.9f).fillMaxHeight()) {
        SmallLabel("Akıcılık skoru", modifier = Modifier.align(Alignment.CenterHorizontally))
        ScoreRing(s.ecoScore, ecoLabel(s.ecoScore), Modifier.weight(1f).fillMaxWidth().padding(6.dp))
    }
    Panel(Modifier.weight(1.1f).fillMaxHeight(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigValue("${s.speedKmh.toInt()}", 80.sp)
            Text("km/h", color = t.mute, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            SmallLabel("Fren"); Spacer(Modifier.weight(1f)); SmallLabel("İvme (boyuna)"); Spacer(Modifier.weight(1f)); SmallLabel("Gaz")
        }
        AccelBar(s.gLon, softG, Modifier.fillMaxWidth().height(30.dp).padding(top = 4.dp))
        Text("yumuşak bölge ±${f2(softG)} g", color = t.mute, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.weight(1f))
        val soft = abs(s.gLon) <= softG
        Text("${signedG(s.gLon)} g · ${if (soft) "yumuşak" else if (s.gLon > 0) "sert hızlanma" else "sert fren"}",
            color = if (soft) t.green else if (s.gLon > 0) t.accInk else t.red, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EcoTile("Dur-kalk", "${s.stops}", Modifier.weight(1f))
            EcoTile("Duruş", "${(s.stoppedS / 60).toInt()}:${"%02d".format((s.stoppedS % 60).toInt())}", Modifier.weight(1f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EcoTile("▲ Ani hızl.", "${s.ecoAccel}", Modifier.weight(1f), t.accInk)
            EcoTile("▼ Ani fren", "${s.ecoBrake}", Modifier.weight(1f), t.red)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EcoTile("PKE m/s²", f2(s.pke), Modifier.weight(1f))
            EcoTile("Tah. L/100", s.fuelLPer100?.let { f1(it) } ?: "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun EcoTile(label: String, value: String, modifier: Modifier, color: Color = LocalTm.current.ink) {
    Panel(modifier.fillMaxHeight(), padding = 8.dp) {
        SmallLabel(label)
        BigValue(value, 26.sp, color)
    }
}
