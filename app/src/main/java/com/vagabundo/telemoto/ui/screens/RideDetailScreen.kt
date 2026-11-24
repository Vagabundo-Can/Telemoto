package com.vagabundo.telemoto.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.analysis.Corner
import com.vagabundo.telemoto.analysis.EventType
import com.vagabundo.telemoto.analysis.FuelModel
import com.vagabundo.telemoto.analysis.RideAnalyzer
import com.vagabundo.telemoto.analysis.RideStats
import com.vagabundo.telemoto.data.RideLogFormat
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.Sample
import com.vagabundo.telemoto.share.CardTemplate
import com.vagabundo.telemoto.share.ShareCards
import com.vagabundo.telemoto.ui.Amber
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.RideMap
import com.vagabundo.telemoto.ui.components.BigValue
import com.vagabundo.telemoto.ui.components.Ico
import com.vagabundo.telemoto.ui.components.IconBtn
import com.vagabundo.telemoto.ui.components.Metric
import com.vagabundo.telemoto.ui.components.ModeBadge
import com.vagabundo.telemoto.ui.components.Panel
import com.vagabundo.telemoto.ui.components.Segmented
import com.vagabundo.telemoto.ui.components.SmallLabel
import com.vagabundo.telemoto.ui.components.durationText
import com.vagabundo.telemoto.ui.components.ecoLabel
import com.vagabundo.telemoto.ui.components.f1
import com.vagabundo.telemoto.ui.components.f2
import com.vagabundo.telemoto.ui.components.linePlot
import com.vagabundo.telemoto.ui.components.signedG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max

private class Loaded(
    val parsed: RideLogFormat.Parsed,
    val stats: RideStats,
    /** Harita/grafik için seyreltilmiş indeksler (≈1 Hz, en çok 4000). */
    val idx: IntArray,
)

@Composable
fun RideDetailScreen(id: String, onBack: () -> Unit) {
    val t = LocalTm.current
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TelemotoApp
    var data by remember { mutableStateOf<Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }

    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            runCatching {
                val p = RideLogFormat.read(app.store.logFile(id))
                require(p.samples.size > 1) { "kayıtta veri yok" }
                val st = RideAnalyzer.analyze(p.samples, p.events.map { it.t }, app.prefs.value.th)
                val step = max(1, p.samples.size / 4000)
                val idx = (p.samples.indices step step).toList().toIntArray()
                Loaded(p, st, idx)
            }.onSuccess { data = it; title = it.parsed.header.name }
                .onFailure { error = "Kayıt açılamadı: ${it.message}" }
        }
    }

    val d = data
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Yükleniyor…", color = t.mute)
        }
        return
    }

    val samples = d.parsed.samples
    var tab by remember { mutableStateOf(d.parsed.header.mode) }
    var pos by remember { mutableIntStateOf(0) }
    var axisKm by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val si = d.idx[pos.coerceIn(0, d.idx.size - 1)]
    val cur = samples.getOrNull(si)
    val mapPts = remember(d) { d.idx.map { samples[it] } }
    val heading = remember(si) {
        val a = samples.getOrNull((si - 12).coerceAtLeast(0)); val b = samples.getOrNull((si + 12).coerceAtMost(samples.size - 1))
        if (a != null && b != null) Math.toDegrees(atan2((b.lon - a.lon) * cos(Math.toRadians(a.lat)), b.lat - a.lat)).toFloat() else 0f
    }

    Column(Modifier.fillMaxSize()) {
        // Başlık
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBtn(Ico.BACK, "Geri", onBack)
            Column(Modifier.weight(1f).clickable { showRename = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, color = t.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                    ModeBadge(d.parsed.header.mode)
                }
                Text(
                    SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR")).format(Date(d.parsed.header.startEpochMs)) +
                        " · ${f1(d.stats.distanceM / 1000)} km · ${durationText(d.stats.durationS)}",
                    color = t.mute, fontSize = 12.sp,
                )
            }
            Box {
                IconBtn(Ico.LIST, "Menü") { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Adını değiştir") }, onClick = { menu = false; showRename = true })
                    DropdownMenuItem(text = { Text(".ridelog paylaş") }, onClick = { menu = false; ShareCards.shareFile(ctx, app.store.logFile(id), "text/plain") })
                    DropdownMenuItem(text = { Text("GPX dışa aktar") }, onClick = {
                        menu = false
                        scope.launch { val f = withContext(Dispatchers.IO) { app.store.exportGpx(d.parsed) }; ShareCards.shareFile(ctx, f, "application/gpx+xml") }
                    })
                    DropdownMenuItem(text = { Text("CSV dışa aktar") }, onClick = {
                        menu = false
                        scope.launch { val f = withContext(Dispatchers.IO) { app.store.exportCsv(d.parsed) }; ShareCards.shareFile(ctx, f, "text/csv") }
                    })
                    DropdownMenuItem(text = { Text("Sil", color = t.red) }, onClick = { menu = false; showDelete = true })
                }
            }
            IconBtn(Ico.SHARE, "Paylaş") { showShare = true }
        }

        Segmented(listOf(RideMode.PERF to "Performans", RideMode.ECO to "Ekonomi"), tab, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { tab = it }

        Box(Modifier.fillMaxWidth().height(250.dp).padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp))) {
            RideMap(mapPts, d.stats.events, tab == RideMode.ECO, cur, heading, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tab == RideMode.PERF) { Chip("● Sol (L)", t.left); Chip("● Sağ (S)", t.right); Chip("○ Fren", t.red) }
                else { Chip("● Duruş", t.mute); Chip("▲ Ani hızl.", t.accInk); Chip("▼ Ani fren", t.red) }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Anlık değerler
            if (cur != null) {
                val leanC = if (abs(cur.lean) < 5) t.ink else if (cur.lean > 0) t.right else t.left
                val gC = if (cur.gLon < -0.3f) t.red else if (cur.gLon > 0.2f) t.green else t.ink
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Panel(Modifier.weight(1f), 8.dp) { Metric("Hız", "${(cur.speed * 3.6f).toInt()}", size = 24.sp) }
                    Panel(Modifier.weight(1f), 8.dp) { Metric("Yatış", "${abs(cur.lean).toInt()}° ${if (abs(cur.lean) < 5) "" else if (cur.lean > 0) "S" else "L"}", valueColor = leanC, size = 24.sp) }
                    Panel(Modifier.weight(1f), 8.dp) { Metric("Boyuna", signedG(cur.gLon), valueColor = gC, size = 24.sp) }
                    Panel(Modifier.weight(1f), 8.dp) {
                        if (axisKm) Metric("Km", f1(d.stats.cumDist[si] / 1000), size = 24.sp)
                        else Metric("Süre", clock(cur.t), size = 24.sp)
                    }
                }
            }

            // Grafikler + kaydırıcı
            Panel(Modifier.fillMaxWidth()) {
                Row { SmallLabel(if (tab == RideMode.PERF) "Yatış (°)" else "Boyuna ivme (g)"); Spacer(Modifier.weight(1f)); SmallLabel("Hız (km/h)") }
                Charts(d, samples, si, tab)
            }
            Slider(
                value = pos.toFloat(), onValueChange = { pos = it.toInt() },
                valueRange = 0f..(d.idx.size - 1).coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (axisKm) "0 km" else "00:00", color = t.mute, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Segmented(listOf(true to "km", false to "zaman"), axisKm, Modifier.width(160.dp)) { axisKm = it }
                Spacer(Modifier.weight(1f))
                Text(if (axisKm) "${f1(d.stats.distanceM / 1000)} km" else clock(samples.lastOrNull()?.t ?: 0), color = t.mute, fontSize = 11.sp)
            }

            if (tab == RideMode.PERF) PerfSection(d.stats, si) else EcoSection(d.stats, app.prefs.baseConsumption())
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Sürüş adı") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ app.store.rename(id, name.trim().ifEmpty { title }); title = name.trim().ifEmpty { title }; showRename = false }) { Text("Kaydet") } },
            dismissButton = { TextButton({ showRename = false }) { Text("Vazgeç") } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Sürüş silinsin mi?") },
            text = { Text("Bu kayıt ve dışa aktarımları kalıcı olarak silinir.") },
            confirmButton = { TextButton({ app.store.delete(id); showDelete = false; onBack() }) { Text("Sil", color = t.red) } },
            dismissButton = { TextButton({ showDelete = false }) { Text("Vazgeç") } },
        )
    }
    if (showShare) {
        var tpl by remember { mutableStateOf<CardTemplate>(if (tab == RideMode.ECO) ShareCards.TOPO else ShareCards.NIGHT) }
        var cardTitle by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { showShare = false },
            title = { Text("Paylaşım görseli") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(cardTitle, { cardTitle = it }, label = { Text("Başlık") }, singleLine = true)
                    Text("Tema ve yerleşim", color = t.mute, fontSize = 12.sp)
                    ShareCards.ALL.chunked(2).forEach { row ->
                        Segmented(row.map { it to it.label }, tpl, Modifier.fillMaxWidth()) { tpl = it }
                    }
                    Text("İçerik: ${if (tab == RideMode.ECO) "ekonomi" else "performans"} özeti", color = t.mute, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton({
                    showShare = false
                    scope.launch {
                        val bmp = withContext(Dispatchers.Default) {
                            ShareCards.render(tpl, cardTitle.ifBlank { title }, tab, d.parsed.header.startEpochMs, mapPts, d.stats)
                        }
                        ShareCards.saveAndShare(ctx, bmp, "telemoto-$id-${tpl.id}")
                    }
                }) { Text("Oluştur ve paylaş") }
            },
            dismissButton = { TextButton({ showShare = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    val t = LocalTm.current
    Text(text, color = color, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(t.bg.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 4.dp))
}

private fun clock(ms: Long): String { val m = ms / 60000; return "%02d:%02d".format(m / 60, m % 60) }

@Composable
private fun Charts(d: Loaded, samples: List<Sample>, si: Int, tab: RideMode) {
    val t = LocalTm.current
    val n = d.idx.size
    val xs = remember(d) { FloatArray(n) { it / (n - 1f).coerceAtLeast(1f) } }
    val top = remember(d, tab) { FloatArray(n) { val s = samples[d.idx[it]]; if (tab == RideMode.PERF) s.lean else s.gLon } }
    val spd = remember(d) { FloatArray(n) { samples[d.idx[it]].speed * 3.6f } }
    val vmax = remember(d) { max(60f, spd.maxOrNull() ?: 60f) }
    val brakes = remember(d, tab) {
        d.stats.events.filter { if (tab == RideMode.PERF) it.type == EventType.BRAKE || it.type == EventType.HARD_BRAKE else it.type == EventType.STOP }
            .map { it.idx.toFloat() / (samples.size - 1).coerceAtLeast(1) }
    }
    val cursorX = si.toFloat() / (samples.size - 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val h1 = size.height * 0.45f
        val h2 = size.height * 0.45f
        val top2 = size.height * 0.55f
        drawLine(t.line, Offset(0f, h1 / 2), Offset(size.width, h1 / 2), 1f)
        brakes.forEach { x -> drawRect((if (tab == RideMode.PERF) t.red else t.mute).copy(alpha = 0.18f), Offset(x * size.width - 3, top2), Size(6f, h2)) }
        if (tab == RideMode.PERF) linePlot(xs, top, -55f, 55f, t.ink, 0f, h1, 1.6.dp.toPx())
        else linePlot(xs, top, -0.5f, 0.5f, t.ink, 0f, h1, 1.6.dp.toPx())
        linePlot(xs, spd, 0f, vmax, Amber, top2, h2, 1.6.dp.toPx())
        drawLine(t.accInk, Offset(cursorX * size.width, 0f), Offset(cursorX * size.width, size.height), 2.dp.toPx())
    }
}

@Composable
private fun PerfSection(st: RideStats, si: Int) {
    val t = LocalTm.current
    val corner: Corner? = st.corners.firstOrNull { si in it.startIdx..it.endIdx }
    Panel(Modifier.fillMaxWidth()) {
        if (corner != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigValue("#${corner.no}", 22.sp, if (corner.right) t.right else t.left)
                Column {
                    Text("Bu viraj · ${if (corner.right) "sağ" else "sol"}${if (corner.braked) " · frenli" else ""}", color = t.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Giriş ${(corner.vIn * 3.6f).toInt()} · apex ${(corner.vApex * 3.6f).toInt()} · çıkış ${(corner.vOut * 3.6f).toInt()} km/h · maks ${corner.maxLean.toInt()}°", color = t.mute, fontSize = 12.sp)
                }
            }
        } else Text("Kaydırıcıyı bir virajın üstüne getir: giriş/apex/çıkış hızları burada görünür.", color = t.mute, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(Modifier.weight(1f)) { Metric("Maks sol", "${st.maxLeanLeft.toInt()}°", valueColor = t.left) }
        Panel(Modifier.weight(1f)) { Metric("Maks sağ", "${st.maxLeanRight.toInt()}°", valueColor = t.right) }
        Panel(Modifier.weight(1f)) { Metric("Viraj", "${st.cornerCount}") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(Modifier.weight(1f)) { Metric("Maks hız", "${(st.maxSpeed * 3.6f).toInt()}", sub = "km/h") }
        Panel(Modifier.weight(1f)) { Metric("Maks fren", "${signedG(st.maxBrakeG)}", valueColor = t.red, sub = "${st.perfBrakeCount} fren · ${st.hardBrakeCount} sert") }
        Panel(Modifier.weight(1f)) { Metric("Maks gaz", "${signedG(st.maxAccelG)}", valueColor = t.green, sub = "${st.perfAccelCount} ani gaz") }
    }
    if (st.corners.isNotEmpty()) {
        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("En derin virajlar")
            st.corners.sortedByDescending { it.maxLean }.take(5).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("#${c.no} · km ${f1(c.startDistM / 1000)}", color = if (c.right) t.right else t.left, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${c.maxLean.toInt()}° · ${(c.vIn * 3.6f).toInt()} → ${(c.vApex * 3.6f).toInt()} → ${(c.vOut * 3.6f).toInt()} km/h", color = t.mute, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun EcoSection(st: RideStats, base: Double?) {
    val t = LocalTm.current
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                BigValue(st.ecoScore?.toString() ?: "—", 58.sp, t.green)
                Text(ecoLabel(st.ecoScore), color = t.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SmallLabel("Puan kaybı")
                LossBar("Ani hızlanma", st.ecoLoss.accel, t.accInk)
                LossBar("Ani fren", st.ecoLoss.brake, t.red)
                LossBar("Dur-kalk", st.ecoLoss.stops, t.mute)
                LossBar("Hız dalgalanması", st.ecoLoss.variation, t.mute)
            }
        }
    }
    val km = st.distanceM / 1000.0
    val movingS = (st.durationS - st.stoppedS).coerceAtLeast(1.0)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(Modifier.weight(1f), 8.dp) { Metric("Dur-kalk", "${st.stops}", sub = if (km > 0.1) "${f2(st.stops / km)} / km" else null, size = 24.sp) }
        Panel(Modifier.weight(1f), 8.dp) { Metric("Duruş", "${(st.stoppedS / 60).toInt()}:${"%02d".format((st.stoppedS % 60).toInt())}", sub = "sürenin %${(st.stoppedS / st.durationS.coerceAtLeast(1.0) * 100).toInt()}’i", size = 24.sp) }
        Panel(Modifier.weight(1f), 8.dp) { Metric("Hareketli ort.", "${(st.distanceM / movingS * 3.6).toInt()}", sub = "km/h", size = 24.sp) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(Modifier.weight(1f), 8.dp) { Metric("▲ Ani hızl.", "${st.ecoAccelCount}", valueColor = t.accInk, size = 24.sp) }
        Panel(Modifier.weight(1f), 8.dp) { Metric("▼ Ani fren", "${st.ecoBrakeCount}", valueColor = t.red, size = 24.sp) }
        Panel(Modifier.weight(1f), 8.dp) { Metric("PKE", f2(st.pke), sub = "m/s²", size = 24.sp) }
    }
    val fuel = FuelModel.estimate(base, st.pke, st.stoppedS / st.durationS.coerceAtLeast(1.0))
    Panel(Modifier.fillMaxWidth()) {
        SmallLabel("Tahmini yakıt")
        if (fuel != null) {
            BigValue("${f1(fuel)} L/100 km · ${f2(fuel * km / 100)} L", 24.sp)
            Text("Depo kayıtlarından kalibre edilmiş tahmin; ölçüm değildir.", color = t.mute, fontSize = 12.sp)
        } else Text("Özet › Yakıt kalibrasyonu'ndan en az bir depo (litre + km) ekle.", color = t.mute, fontSize = 13.sp)
    }
}

@Composable
private fun LossBar(name: String, v: Double, color: Color) {
    val t = LocalTm.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = t.ink, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.well)) {
            Box(Modifier.fillMaxWidth((v / 30.0).toFloat().coerceIn(0f, 1f)).height(8.dp).background(color))
        }
        Text("−${v.toInt()}", color = t.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
    }
}
