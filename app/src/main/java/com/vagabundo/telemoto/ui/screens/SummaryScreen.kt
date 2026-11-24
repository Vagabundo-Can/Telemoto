package com.vagabundo.telemoto.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.data.FuelFill
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.RideSummary
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.components.BarChart
import com.vagabundo.telemoto.ui.components.BigValue
import com.vagabundo.telemoto.ui.components.Metric
import com.vagabundo.telemoto.ui.components.ModeBadge
import com.vagabundo.telemoto.ui.components.Panel
import com.vagabundo.telemoto.ui.components.PrimaryButton
import com.vagabundo.telemoto.ui.components.Segmented
import com.vagabundo.telemoto.ui.components.SmallLabel
import com.vagabundo.telemoto.ui.components.f1
import com.vagabundo.telemoto.ui.components.f2
import com.vagabundo.telemoto.ui.components.signedG
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Period { DAY, WEEK, MONTH }

private class Bucket(val label: String, val from: Long, val to: Long)

@Composable
fun SummaryScreen() {
    val t = LocalTm.current
    val app = LocalContext.current.applicationContext as TelemotoApp
    var period by remember { mutableStateOf(Period.WEEK) }
    var refresh by remember { mutableIntStateOf(0) }
    val all = remember(refresh) { app.store.list() }
    var showFuel by remember { mutableStateOf(false) }
    val tr = Locale("tr", "TR")

    val now = Calendar.getInstance()
    fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val buckets: List<Bucket>
    val rangeText: String
    when (period) {
        Period.DAY -> {
            val d0 = startOfDay(now).timeInMillis
            buckets = listOf(0 to 6, 6 to 9, 9 to 12, 12 to 15, 15 to 17, 17 to 20, 20 to 24).map { (a, b) ->
                Bucket("%02d".format(a), d0 + a * 3_600_000L, d0 + b * 3_600_000L)
            }
            rangeText = "Bugün · " + SimpleDateFormat("EEEE d MMMM", tr).format(Date())
        }
        Period.WEEK -> {
            val mon = startOfDay(now).apply { firstDayOfWeek = Calendar.MONDAY; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
            val names = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
            buckets = (0 until 7).map { i -> Bucket(names[i], mon.timeInMillis + i * 86_400_000L, mon.timeInMillis + (i + 1) * 86_400_000L) }
            rangeText = SimpleDateFormat("d MMM", tr).format(Date(buckets.first().from)) + " – " + SimpleDateFormat("d MMM yyyy", tr).format(Date(buckets.last().from))
        }
        Period.MONTH -> {
            val m0 = startOfDay(now).apply { set(Calendar.DAY_OF_MONTH, 1) }
            val days = m0.getActualMaximum(Calendar.DAY_OF_MONTH)
            buckets = (0 until 5).mapNotNull { w ->
                val a = w * 7; if (a >= days) null else Bucket("H${w + 1}", m0.timeInMillis + a * 86_400_000L, m0.timeInMillis + minOf(days, a + 7) * 86_400_000L)
            }
            rangeText = SimpleDateFormat("MMMM yyyy", tr).format(Date())
        }
    }
    val from = buckets.first().from
    val to = buckets.last().to
    val inRange = all.filter { it.startEpochMs in from until to }
    val eco = inRange.filter { it.ecoScore != null }
    fun wavg(xs: List<RideSummary>): Int? = if (xs.isEmpty() || xs.sumOf { it.distanceM } < 1) null else (xs.sumOf { it.ecoScore!! * it.distanceM } / xs.sumOf { it.distanceM }).toInt()
    val bars = buckets.map { b -> wavg(eco.filter { it.startEpochMs in b.from until b.to }) ?: 0 }
    val perf = inRange.filter { it.mode == RideMode.PERF }
    val ecoKm = inRange.filter { it.mode == RideMode.ECO }.sumOf { it.distanceM } / 1000
    val base = app.prefs.baseConsumption()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Özet", color = t.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Segmented(listOf(Period.DAY to "Gün", Period.WEEK to "Hafta", Period.MONTH to "Ay"), period, Modifier.width(220.dp)) { period = it }
        }
        Text(rangeText, color = t.mute, fontSize = 12.sp)

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeBadge(RideMode.ECO)
                Spacer(Modifier.weight(1f))
                Text("${inRange.count { it.mode == RideMode.ECO }} sürüş · ${f1(ecoKm)} km", color = t.mute, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                Column { SmallLabel("Ort. akıcılık"); BigValue(wavg(eco)?.toString() ?: "—", 50.sp, t.green) }
            }
            BarChart(bars, buckets.map { it.label }, t.green, t.ecoBg, Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
            val stops = eco.sumOf { it.stops }
            val ekm = eco.sumOf { it.distanceM } / 1000
            Row(Modifier.padding(top = 8.dp)) {
                Metric("Dur-kalk/km", if (ekm > 0.1) f2(stops / ekm) else "—", Modifier.weight(1f), size = 22.sp)
                Metric("Ani hızl.", "${eco.sumOf { it.ecoAccel }}", Modifier.weight(1f), size = 22.sp)
                Metric("Ani fren", "${eco.sumOf { it.ecoBrake }}", Modifier.weight(1f), size = 22.sp)
                Metric("Tah. yakıt", base?.let { "${f1(it * inRange.sumOf { r -> r.distanceM } / 100_000)} L" } ?: "—", Modifier.weight(1f), size = 22.sp)
            }
        }

        // Rota karşılaştırma: aynı ada sahip en sık ekonomi sürüşü
        val byName = eco.groupBy { it.name }.filter { it.value.size >= 2 }.maxByOrNull { it.value.size }
        if (byName != null) {
            Panel(Modifier.fillMaxWidth()) {
                SmallLabel("Rota karşılaştırma · ${byName.key}")
                val rs = byName.value
                Row(Modifier.padding(top = 6.dp)) {
                    Metric("Ort. süre", "${(rs.map { it.durationS }.average() / 60).toInt()} dk", Modifier.weight(1f), size = 22.sp)
                    Metric("En iyi", "${(rs.minOf { it.durationS } / 60).toInt()} dk", Modifier.weight(1f), size = 22.sp)
                    Metric("Ort. akıcılık", "${wavg(rs) ?: "—"}", Modifier.weight(1f), size = 22.sp)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeBadge(RideMode.PERF)
                Spacer(Modifier.weight(1f))
                Text(if (perf.isEmpty()) "bu dönemde performans sürüşü yok" else "${perf.size} sürüş · ${f1(perf.sumOf { it.distanceM } / 1000)} km", color = t.mute, fontSize = 12.sp)
            }
            Row(Modifier.padding(top = 6.dp)) {
                Metric("Maks sol", perf.maxOfOrNull { it.maxLeanL }?.let { "${it.toInt()}°" } ?: "—", Modifier.weight(1f), valueColor = t.left, size = 24.sp)
                Metric("Maks sağ", perf.maxOfOrNull { it.maxLeanR }?.let { "${it.toInt()}°" } ?: "—", Modifier.weight(1f), valueColor = t.right, size = 24.sp)
                Metric("Viraj", if (perf.isEmpty()) "—" else "${perf.sumOf { it.corners }}", Modifier.weight(1f), size = 24.sp)
                Metric("Maks fren", perf.minOfOrNull { it.maxBrakeG }?.let { signedG(it) } ?: "—", Modifier.weight(1f), valueColor = t.red, size = 24.sp)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SmallLabel("Yakıt kalibrasyonu")
                    val last = app.prefs.fuelFills().lastOrNull()
                    Text(
                        if (last == null) "Henüz depo kaydı yok" else "Son depo: ${f1(last.liters)} L · ${last.km.toInt()} km · ${base?.let { f1(it) } ?: "—"} L/100",
                        color = t.ink, fontSize = 13.sp,
                    )
                }
                PrimaryButton("Depo ekle", Modifier.width(120.dp), height = 44.dp) { showFuel = true }
            }
        }
    }

    if (showFuel) {
        var liters by remember { mutableStateOf("") }
        var km by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFuel = false },
            title = { Text("Depo ekle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Depoyu fulle, son fullemeden beri gidilen km'yi ve alınan litreyi gir.", fontSize = 13.sp)
                    OutlinedTextField(liters, { liters = it }, label = { Text("Litre") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(km, { km = it }, label = { Text("Km (son dolumdan beri)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton({
                    val l = liters.replace(',', '.').toDoubleOrNull()
                    val k = km.replace(',', '.').toDoubleOrNull()
                    if (l != null && k != null && l > 0 && k > 0) {
                        app.prefs.addFuel(FuelFill(System.currentTimeMillis(), l, k))
                        refresh++
                        showFuel = false
                    }
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton({ showFuel = false }) { Text("Vazgeç") } },
        )
    }
}
