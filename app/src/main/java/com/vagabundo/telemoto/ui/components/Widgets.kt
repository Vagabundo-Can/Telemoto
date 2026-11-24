package com.vagabundo.telemoto.ui.components

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.ui.Amber
import com.vagabundo.telemoto.ui.Condensed
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.OnAmber
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------- Biçimlendirme ----------

private val TR = Locale("tr", "TR")
fun f1(v: Double) = String.format(TR, "%.1f", v)
fun f1(v: Float) = String.format(TR, "%.1f", v)
fun f2(v: Double) = String.format(TR, "%.2f", v)
fun f2(v: Float) = String.format(TR, "%.2f", v)
fun signedG(v: Float) = (if (v >= 0) "+" else "−") + String.format(TR, "%.2f", abs(v))
fun hms(ms: Long): String { val s = ms / 1000; return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) }
fun durationText(sec: Double): String { val m = (sec / 60).toInt(); return if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk" }

// ---------- Temel parçalar ----------

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTm.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(t.surf)
            .border(1.dp, t.line, RoundedCornerShape(16.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun SmallLabel(text: String, color: Color = LocalTm.current.mute, modifier: Modifier = Modifier) {
    Text(text.uppercase(TR), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
fun BigValue(text: String, size: TextUnit = 28.sp, color: Color = LocalTm.current.ink, modifier: Modifier = Modifier) {
    Text(text, fontFamily = Condensed, fontSize = size, color = color, modifier = modifier, lineHeight = size)
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LocalTm.current.ink, sub: String? = null, size: TextUnit = 26.sp) {
    Column(modifier) {
        SmallLabel(label)
        BigValue(value, size, valueColor)
        if (sub != null) Text(sub, color = LocalTm.current.mute, fontSize = 11.sp)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, height: Dp = 60.dp, enabled: Boolean = true, color: Color = Amber, textColor: Color = OnAmber, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = textColor, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, height: Dp = 60.dp, onClick: () -> Unit) {
    val t = LocalTm.current
    Box(
        modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(14.dp))
            .background(t.surf)
            .border(1.dp, t.line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center) }
}

/** Basılı tutunca dolan durdurma butonu (yanlışlıkla durdurmayı önler). */
@Composable
fun HoldButton(text: String, modifier: Modifier = Modifier, holdMs: Int = 1200, onDone: () -> Unit) {
    val t = LocalTm.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(t.redFill)
            .semantics { role = Role.Button; contentDescription = "$text — basılı tut" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        onDone()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(150)) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.White.copy(alpha = 0.25f), size = Size(size.width * progress.value, size.height))
            }
        }
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun ModeBadge(mode: RideMode) {
    val t = LocalTm.current
    val (bg, fg) = if (mode == RideMode.ECO) t.ecoBg to t.green else t.perfBg to t.accInk
    Text(
        if (mode == RideMode.ECO) "EKO" else "PERF",
        color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Yan yana seçim butonları (segmented). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val t = LocalTm.current
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(t.well).border(1.dp, t.line, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) Amber else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) OnAmber else t.mute, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        }
    }
}

// ---------- İkonlar ----------

enum class Ico { BACK, SHARE, RIDE, LIST, CHART, GEAR, MIC, GPS, PHONE }

@Composable
fun TmIcon(kind: Ico, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(width = 2f * s, cap = StrokeCap.Round)
        fun p(vararg pts: Float): Path = Path().apply { moveTo(pts[0] * s, pts[1] * s); var i = 2; while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 } }
        when (kind) {
            Ico.BACK -> drawPath(p(15f, 5f, 8f, 12f, 15f, 19f), color, style = st)
            Ico.SHARE -> { drawPath(p(12f, 15f, 12f, 3f), color, style = st); drawPath(p(7f, 8f, 12f, 3f, 17f, 8f), color, style = st); drawPath(p(5f, 14f, 5f, 20f, 19f, 20f, 19f, 14f), color, style = st) }
            Ico.RIDE -> { drawCircle(color, 8f * s, center, style = st); drawCircle(color, 3f * s, center) }
            Ico.LIST -> { drawPath(p(4f, 6f, 20f, 6f), color, style = st); drawPath(p(4f, 12f, 20f, 12f), color, style = st); drawPath(p(4f, 18f, 14f, 18f), color, style = st) }
            Ico.CHART -> { drawPath(p(5f, 20f, 5f, 10f), color, style = st); drawPath(p(12f, 20f, 12f, 4f), color, style = st); drawPath(p(19f, 20f, 19f, 13f), color, style = st) }
            Ico.GEAR -> { drawCircle(color, 3f * s, center, style = st); for (k in 0 until 8) { val a = k * Math.PI / 4; drawLine(color, Offset(12f * s + (6f * s * cos(a)).toFloat(), 12f * s + (6f * s * sin(a)).toFloat()), Offset(12f * s + (9.5f * s * cos(a)).toFloat(), 12f * s + (9.5f * s * sin(a)).toFloat()), 2f * s, StrokeCap.Round) } }
            Ico.MIC -> { drawRoundRect(color, Offset(9f * s, 3f * s), Size(6f * s, 12f * s), androidx.compose.ui.geometry.CornerRadius(3f * s), style = st); drawPath(p(5f, 11f, 5f, 12f), color, style = st); drawArc(color, 0f, 180f, false, Offset(5f * s, 5f * s), Size(14f * s, 13f * s), style = st); drawPath(p(12f, 18f, 12f, 21f), color, style = st) }
            Ico.GPS -> { drawCircle(color, 3f * s, center, style = st); drawPath(p(12f, 2f, 12f, 5f), color, style = st); drawPath(p(12f, 19f, 12f, 22f), color, style = st); drawPath(p(2f, 12f, 5f, 12f), color, style = st); drawPath(p(19f, 12f, 22f, 12f), color, style = st) }
            Ico.PHONE -> { drawRoundRect(color, Offset(7f * s, 2f * s), Size(10f * s, 20f * s), androidx.compose.ui.geometry.CornerRadius(2f * s), style = st); drawPath(p(11f, 18f, 13f, 18f), color, style = st) }
        }
    }
}

@Composable
fun IconBtn(kind: Ico, desc: String, onClick: () -> Unit) {
    val t = LocalTm.current
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(t.surf).border(1.dp, t.line, RoundedCornerShape(12.dp))
            .semantics { contentDescription = desc }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TmIcon(kind, t.ink, 20.dp) }
}

// ---------- Göstergeler ----------

/** Yatış göstergesi: −60°…+60° yay, ibre, maks işaretleri. */
@Composable
fun LeanGauge(lean: Float, maxL: Float, maxR: Float, modifier: Modifier = Modifier) {
    val t = LocalTm.current
    Canvas(modifier.semantics { contentDescription = "Yatış ${abs(lean).toInt()} derece ${if (lean >= 0) "sağ" else "sol"}" }) {
        val w = size.width; val h = size.height
        val r = min(w / 2f, h) * 0.86f
        val c = Offset(w / 2f, h * 0.98f)
        val sw = r * 0.12f
        fun arc(fromDeg: Float, sweep: Float, col: Color) =
            drawArc(col, -90f + fromDeg, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
        arc(-60f, 60f, t.leftTrack)
        arc(0f, 60f, t.rightTrack)
        val l = lean.coerceIn(-60f, 60f)
        if (abs(l) > 0.5f) arc(min(0f, l), abs(l), if (l >= 0) t.right else t.left)
        for (d in -60..60 step 15) {
            rotate(d.toFloat(), c) { drawLine(t.tick, Offset(c.x, c.y - r + sw * 0.9f), Offset(c.x, c.y - r + sw * 1.8f), 2.dp.toPx()) }
        }
        fun mark(deg: Float, col: Color) {
            val a = Math.toRadians(deg.toDouble())
            drawCircle(col, sw * 0.42f, Offset(c.x + (r * sin(a)).toFloat(), c.y - (r * cos(a)).toFloat()))
        }
        if (maxL > 1) mark(-maxL.coerceAtMost(60f), t.left)
        if (maxR > 1) mark(maxR.coerceAtMost(60f), t.right)
        rotate(l, c) { drawLine(t.ink, c, Offset(c.x, c.y - r * 0.92f), 4.dp.toPx(), StrokeCap.Round) }
        drawCircle(t.ink, 9.dp.toPx(), c)
    }
}

/** G dairesi: üst = fren, alt = gaz, yanlar = yanal. */
@Composable
fun GCircle(gLon: Float, gLat: Float, modifier: Modifier = Modifier) {
    val t = LocalTm.current
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2f * 0.9f
        val c = center
        drawCircle(t.line, r, c, style = Stroke(2.dp.toPx()))
        drawCircle(t.line, r / 2, c, style = Stroke(1.dp.toPx()))
        drawLine(t.line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(t.line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        val x = c.x + gLat.coerceIn(-1f, 1f) * r
        val y = c.y + gLon.coerceIn(-1f, 1f) * r      // fren (−) yukarı
        val col = when { gLon <= -0.3f -> t.red; gLon >= 0.2f -> t.green; else -> t.ink }
        drawCircle(col, 8.dp.toPx(), Offset(x, y))
    }
}

@Composable
fun ScoreRing(score: Int?, label: String, modifier: Modifier = Modifier) {
    val t = LocalTm.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 12.dp.toPx()
            val d = min(size.width, size.height) - sw
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(t.line, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
            if (score != null) drawArc(t.green, -90f, 360f * score / 100f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigValue(score?.toString() ?: "—", 60.sp)
            Text(label, color = t.green, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

fun ecoLabel(score: Int?) = when {
    score == null -> "1 km sonra"
    score >= 85 -> "Çok akıcı"
    score >= 70 -> "Akıcı"
    score >= 50 -> "Dalgalı"
    else -> "Sert"
}

/** Boyuna ivme çubuğu: ±0,40 g, ortada yumuşak bölge. */
@Composable
fun AccelBar(g: Float, softG: Float, modifier: Modifier = Modifier) {
    val t = LocalTm.current
    Canvas(modifier.clip(RoundedCornerShape(8.dp))) {
        drawRect(t.well)
        val w = size.width
        val zone = softG / 0.4f * w / 2
        drawRect(t.ecoBg, Offset(w / 2 - zone, 0f), Size(zone * 2, size.height))
        drawLine(t.line, Offset(w / 2, 0f), Offset(w / 2, size.height), 2.dp.toPx())
        val x = w / 2 + (g / 0.4f).coerceIn(-1f, 1f) * w / 2
        val col = when { abs(g) <= softG -> t.green; g > 0 -> t.accInk; else -> t.red }
        drawCircle(col, size.height * 0.4f, Offset(x, size.height / 2))
    }
}

/** Basit çizgi grafiği; xs 0..1 normalize, imleç dahil. */
fun DrawScope.linePlot(xs: FloatArray, ys: FloatArray, yMin: Float, yMax: Float, color: Color, top: Float, height: Float, strokePx: Float) {
    if (xs.size < 2) return
    val path = Path()
    for (i in xs.indices) {
        val x = xs[i] * size.width
        val y = top + height - ((ys[i] - yMin) / (yMax - yMin)).coerceIn(0f, 1f) * height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(strokePx))
}

/** Değerli çubuk grafik (özet ekranı). */
@Composable
fun BarChart(values: List<Int>, labels: List<String>, highlight: Color, dim: Color, modifier: Modifier = Modifier) {
    val t = LocalTm.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            val mx = max(1, values.maxOrNull() ?: 1)
            values.forEach { v ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (v > 0) Text("$v", color = t.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Box(
                        Modifier.fillMaxWidth().height((70f * v / 100f).coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (v > 0 && v == mx) highlight else dim)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach { Text(it, color = t.mute, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) { Box(Modifier.size(size).clip(CircleShape).background(color)) }

@Composable
fun RowScope.Spacer1() { Box(Modifier.weight(1f)) }
