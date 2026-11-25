package com.vagabundo.telemoto.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.RideSummary
import com.vagabundo.telemoto.service.RecordingService
import com.vagabundo.telemoto.ui.Condensed
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.components.BigValue
import com.vagabundo.telemoto.ui.components.ModeBadge
import com.vagabundo.telemoto.ui.components.Panel
import com.vagabundo.telemoto.ui.components.PrimaryButton
import com.vagabundo.telemoto.ui.components.SmallLabel
import com.vagabundo.telemoto.ui.components.durationText
import com.vagabundo.telemoto.ui.components.f1
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TR = Locale("tr", "TR")

fun requiredPermissions(): Array<String> {
    val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
    return p.toTypedArray()
}

@Composable
fun HomeScreen(onStartLive: () -> Unit, onOpenRide: (String) -> Unit) {
    val t = LocalTm.current
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TelemotoApp
    val settings by app.prefs.flow.collectAsState()
    var mode by remember { mutableStateOf(settings.lastMode) }
    val rides = remember { app.store.list() }
    var permMsg by remember { mutableStateOf<String?>(null) }
    val live by com.vagabundo.telemoto.service.RideSession.flow.collectAsState()
    val active = live.phase != com.vagabundo.telemoto.service.Phase.IDLE

    fun start() {
        app.prefs.update { it.copy(lastMode = mode) }
        RecordingService.send(ctx, RecordingService.ACTION_PREPARE, mode)
        onStartLive()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) { permMsg = null; start() }
        else permMsg = "Konum izni olmadan hız ve rota kaydedilemez."
    }

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val weekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY
    val commuteHint = weekday && (hour in 7..9 || hour in 17..19)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TELE", fontFamily = Condensed, fontSize = 26.sp, color = t.ink, letterSpacing = 2.sp)
                Text("MOTO", fontFamily = Condensed, fontSize = 26.sp, color = t.accInk, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(SimpleDateFormat("EEEE · HH:mm", TR).format(Date()), color = t.mute, fontSize = 12.sp)
            }
        }
        item { SmallLabel("Sürüş modu") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard("Performans", "Viraj başına yatış, giriş hızı, fren ve gaz g", RideMode.PERF, mode == RideMode.PERF, Modifier.weight(1f)) { mode = RideMode.PERF }
                ModeCard("Ekonomi", "Akıcılık, dur-kalk, ani ivmelenme, tahmini yakıt", RideMode.ECO, mode == RideMode.ECO, Modifier.weight(1f)) { mode = RideMode.ECO }
            }
        }
        if (commuteHint && mode != RideMode.ECO) item {
            Text("İş saatleri — Ekonomi modu önerilir", color = t.mute, fontSize = 12.sp)
        }
        if (active) item {
            PrimaryButton("Kayıt ekranına dön", Modifier.fillMaxWidth(), height = 64.dp, color = t.redFill, textColor = androidx.compose.ui.graphics.Color.White) { onStartLive() }
        } else item {
            PrimaryButton("Kalibre et ve başlat · ${mode.label}", Modifier.fillMaxWidth(), height = 64.dp) {
                val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) start() else launcher.launch(missing.toTypedArray())
            }
        }
        permMsg?.let { m -> item { Text(m, color = t.red, fontSize = 13.sp) } }
        item { WeekCard(rides) }
        item { SmallLabel("Son sürüşler") }
        if (rides.isEmpty()) item {
            Text("Henüz kayıt yok. Telefonu mount'a yatay tak, motoru dik tut ve başlat.", color = t.mute, fontSize = 14.sp)
        }
        items(rides.take(6), key = { it.id }) { r -> RideRow(r) { onOpenRide(r.id) } }
        item { Spacer(Modifier.heightIn(min = 12.dp)) }
    }
}

@Composable
private fun ModeCard(title: String, desc: String, mode: RideMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTm.current
    val col = if (mode == RideMode.ECO) t.green else t.accInk
    Column(
        modifier.heightIn(min = 118.dp).clip(RoundedCornerShape(16.dp))
            .background(t.surf)
            .border(2.dp, if (selected) col else t.line, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontFamily = Condensed, fontSize = 24.sp, color = col)
        Text(desc, fontSize = 12.sp, color = t.mute, lineHeight = 16.sp)
    }
}

@Composable
private fun WeekCard(rides: List<RideSummary>) {
    val t = LocalTm.current
    val app = LocalContext.current.applicationContext as TelemotoApp
    val weekStart = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }.timeInMillis
    val wk = rides.filter { it.startEpochMs >= weekStart }
    val km = wk.sumOf { it.distanceM } / 1000.0
    val eco = wk.filter { it.mode == RideMode.ECO && it.ecoScore != null }
    val ecoAvg = if (eco.isEmpty()) null else (eco.sumOf { it.ecoScore!! * it.distanceM } / eco.sumOf { it.distanceM }).toInt()
    val maxLean = wk.maxOfOrNull { maxOf(it.maxLeanL, it.maxLeanR) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallLabel("Bu hafta")
            Spacer(Modifier.weight(1f))
            Text("${wk.size} sürüş · ${f1(km)} km", color = t.mute, fontSize = 12.sp)
        }
        Row(Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) { SmallLabel("Akıcılık"); BigValue(ecoAvg?.toString() ?: "—", 28.sp, t.green) }
            Column(Modifier.weight(1f)) { SmallLabel("Tah. yakıt"); BigValue(app.prefs.baseConsumption()?.let { f1(it) } ?: "—", 28.sp) }
            Column(Modifier.weight(1f)) { SmallLabel("Maks yatış"); BigValue(maxLean?.let { "${it.toInt()}°" } ?: "—", 28.sp) }
        }
    }
}

@Composable
fun RideRow(r: RideSummary, onClick: () -> Unit) {
    val t = LocalTm.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(t.surf)
            .border(1.dp, t.line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeBadge(r.mode)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, color = t.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(
                SimpleDateFormat("d MMM HH:mm", TR).format(Date(r.startEpochMs)) + " · ${f1(r.distanceM / 1000)} km · ${durationText(r.durationS)}",
                color = t.mute, fontSize = 12.sp,
            )
        }
        val kpi = if (r.mode == RideMode.ECO) (r.ecoScore?.toString() ?: "—") else "${maxOf(r.maxLeanL, r.maxLeanR).toInt()}°"
        BigValue(kpi, 22.sp)
    }
}

@Composable
fun HistoryScreen(onOpenRide: (String) -> Unit) {
    val t = LocalTm.current
    val app = LocalContext.current.applicationContext as TelemotoApp
    val rides = remember { app.store.list() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Geçmiş", color = t.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
        if (rides.isEmpty()) item { Text("Kayıt yok.", color = t.mute) }
        items(rides, key = { it.id }) { r -> RideRow(r) { onOpenRide(r.id) } }
    }
}
