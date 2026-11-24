package com.vagabundo.telemoto.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.vagabundo.telemoto.analysis.RideStats
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.Sample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Paylaşım şablonu: renk + yerleşim verisi. Yeni tema = yeni giriş. */
data class CardTemplate(
    val id: String,
    val label: String,
    val portrait: Boolean,
    val bg: Int, val panel: Int, val ink: Int, val mute: Int, val accent: Int,
    val left: Int, val right: Int, val neutral: Int, val red: Int, val green: Int,
    val topo: Boolean,
)

object ShareCards {
    val NIGHT = CardTemplate(
        "gece", "Gece · 9:16", true,
        bg = 0xFF07090B.toInt(), panel = 0xFF0E1317.toInt(), ink = 0xFFF2F5F8.toInt(), mute = 0xFF9AA6B2.toInt(), accent = 0xFFFFB020.toInt(),
        left = 0xFF6FB1FF.toInt(), right = 0xFFFF9A57.toInt(), neutral = 0xFF3A4652.toInt(), red = 0xFFFF6B6B.toInt(), green = 0xFF5BD18B.toInt(), topo = false,
    )
    val TOPO = CardTemplate(
        "topo", "Topo · 16:9", false,
        bg = 0xFFE9EDE7.toInt(), panel = 0xFFE9EDE7.toInt(), ink = 0xFF141A1F.toInt(), mute = 0xFF56615A.toInt(), accent = 0xFFB8470F.toInt(),
        left = 0xFF2563C9.toInt(), right = 0xFFE2611B.toInt(), neutral = 0xFF8C978F.toInt(), red = 0xFFB42318.toInt(), green = 0xFF1E7A45.toInt(), topo = true,
    )
    val NIGHT_WIDE = NIGHT.copy(id = "gece169", label = "Gece · 16:9", portrait = false)
    val TOPO_TALL = TOPO.copy(id = "topo916", label = "Topo · 9:16", portrait = true)
    val ALL = listOf(NIGHT, TOPO, NIGHT_WIDE, TOPO_TALL)

    private val TR = Locale("tr", "TR")
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun render(tpl: CardTemplate, title: String, mode: RideMode, startMs: Long, route: List<Sample>, st: RideStats): Bitmap {
        val w = if (tpl.portrait) 1080 else 1920
        val h = if (tpl.portrait) 1920 else 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(tpl.bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val mapRect = if (tpl.portrait) RectF(64f, 520f, w - 64f, 1080f) else RectF(0f, 0f, 1100f, h.toFloat())
        if (tpl.topo) drawContours(c, mapRect, tpl) else {
            p.color = tpl.panel; c.drawRoundRect(mapRect, 44f, 44f, p)
        }
        drawRoute(c, RectF(mapRect.left + 40, mapRect.top + 40, mapRect.right - 40, mapRect.bottom - 40), route, tpl)

        val x0 = if (tpl.portrait) 64f else 1160f
        var y = if (tpl.portrait) 130f else 120f
        val dateStr = SimpleDateFormat("d MMMM yyyy", TR).format(Date(startMs)).uppercase(TR)
        text(c, "$dateStr · ${fmt1(st.distanceM / 1000)} KM · ${dur(st.durationS)}", x0, y, 30f, tpl.accent, bold = true, spacing = 0.12f)
        y += 30f
        val big = if (tpl.portrait) 132f else 104f
        val maxW = (if (tpl.portrait) w - 128f else w - x0 - 60f)
        wrap(title.uppercase(TR), big, maxW).take(2).forEach { line -> y += big; text(c, line, x0, y, big, tpl.ink, cond = true) }

        y = if (tpl.portrait) 1170f else y + 70f
        if (mode == RideMode.PERF) {
            y = statPair(c, x0, y, maxW, "MAKS SOL YATIŞ", "${st.maxLeanLeft.toInt()}°", tpl.left, "MAKS SAĞ YATIŞ", "${st.maxLeanRight.toInt()}°", tpl.right, tpl)
            y = statRow(c, x0, y + 40, maxW, listOf(
                "MAKS HIZ" to "${(st.maxSpeed * 3.6f).toInt()}" to tpl.ink,
                "MAKS FREN" to "${gStr(st.maxBrakeG)}g" to tpl.red,
                "MAKS GAZ" to "${gStr(st.maxAccelG)}g" to tpl.green,
                "VİRAJ" to "${st.cornerCount}" to tpl.ink,
            ), tpl)
            if (tpl.portrait) {
                y += 50
                text(c, "EN DERİN 3 VİRAJ", x0, y, 26f, tpl.mute, bold = true, spacing = 0.1f)
                st.corners.sortedByDescending { it.maxLean }.take(3).forEach { cr ->
                    y += 56
                    text(c, "#${cr.no} · km ${fmt1(cr.startDistM / 1000)}", x0, y, 34f, if (cr.right) tpl.right else tpl.left, bold = true)
                    val txt = "${cr.maxLean.toInt()}° · ${(cr.vIn * 3.6f).toInt()} → ${(cr.vApex * 3.6f).toInt()} → ${(cr.vOut * 3.6f).toInt()} km/h"
                    textRight(c, txt, x0 + maxW, y, 34f, tpl.mute)
                }
            }
        } else {
            y = statPair(c, x0, y, maxW, "AKICILIK SKORU", st.ecoScore?.toString() ?: "—", tpl.green, "DUR-KALK", "${st.stops}", tpl.ink, tpl)
            y = statRow(c, x0, y + 40, maxW, listOf(
                "▲ ANİ HIZL." to "${st.ecoAccelCount}" to tpl.accent,
                "▼ ANİ FREN" to "${st.ecoBrakeCount}" to tpl.red,
                "DURUŞ" to dur(st.stoppedS) to tpl.ink,
                "PKE" to fmt2(st.pke) to tpl.ink,
            ), tpl)
        }

        // alt bilgi
        text(c, "TELE", x0, h - 70f, 46f, tpl.ink, cond = true, spacing = 0.08f)
        val tw = Paint().apply { textSize = 46f; typeface = condensed; letterSpacing = 0.08f }.measureText("TELE")
        text(c, "MOTO", x0 + tw, h - 70f, 46f, tpl.accent, cond = true, spacing = 0.08f)
        textRight(c, "sürüş telemetrisi", if (tpl.portrait) w - 64f else w - 60f, h - 72f, 28f, tpl.mute)
        return bmp
    }

    private fun statPair(c: Canvas, x: Float, y0: Float, w: Float, l1: String, v1: String, c1: Int, l2: String, v2: String, c2: Int, tpl: CardTemplate): Float {
        val half = w / 2
        text(c, l1, x, y0, 26f, c1, bold = true, spacing = 0.1f)
        text(c, l2, x + half, y0, 26f, c2, bold = true, spacing = 0.1f)
        text(c, v1, x, y0 + 130, 130f, tpl.ink, cond = true)
        text(c, v2, x + half, y0 + 130, 130f, tpl.ink, cond = true)
        return y0 + 150
    }

    private fun statRow(c: Canvas, x: Float, y0: Float, w: Float, items: List<Pair<Pair<String, String>, Int>>, tpl: CardTemplate): Float {
        val col = w / items.size
        items.forEachIndexed { i, (lv, color) ->
            text(c, lv.first, x + i * col, y0, 22f, tpl.mute, bold = true, spacing = 0.08f)
            text(c, lv.second, x + i * col, y0 + 70, 64f, color, cond = true)
        }
        return y0 + 80
    }

    private fun drawRoute(c: Canvas, r: RectF, pts: List<Sample>, tpl: CardTemplate) {
        val g = pts.filter { it.lat != 0.0 }
        if (g.size < 2) return
        val lat0 = g.map { it.lat }.average()
        val k = cos(Math.toRadians(lat0))
        val minX = g.minOf { it.lon * k }; val maxX = g.maxOf { it.lon * k }
        val minY = g.minOf { it.lat }; val maxY = g.maxOf { it.lat }
        val sx = r.width() / max(1e-9, maxX - minX); val sy = r.height() / max(1e-9, maxY - minY)
        val s = minOf(sx, sy)
        val ox = r.left + (r.width() - (maxX - minX) * s).toFloat() / 2
        val oy = r.top + (r.height() - (maxY - minY) * s).toFloat() / 2
        fun px(p: Sample) = (ox + ((p.lon * k - minX) * s)).toFloat()
        fun py(p: Sample) = (oy + ((maxY - p.lat) * s)).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        paint.color = tpl.neutral; paint.strokeWidth = 18f; paint.alpha = 90
        val all = Path().apply { moveTo(px(g[0]), py(g[0])); g.drop(1).forEach { lineTo(px(it), py(it)) } }
        c.drawPath(all, paint)
        paint.alpha = 255
        for (i in 1 until g.size) {
            val a = g[i - 1]; val b = g[i]
            val lean = b.lean
            paint.strokeWidth = 7f + 7f * (abs(lean) / 45f).coerceIn(0f, 1f)
            paint.color = if (abs(lean) < 5) tpl.neutral else if (lean > 0) tpl.right else tpl.left
            c.drawLine(px(a), py(a), px(b), py(b), paint)
        }
        val f = Paint(Paint.ANTI_ALIAS_FLAG)
        f.color = tpl.ink; c.drawCircle(px(g.first()), py(g.first()), 16f, f)
        f.color = tpl.accent; c.drawCircle(px(g.last()), py(g.last()), 16f, f)
        val apex = g.maxByOrNull { abs(it.lean) } ?: return
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = tpl.accent }
        c.drawCircle(px(apex), py(apex), 30f, ring)
        text(c, "${abs(apex.lean).toInt()}° maks", px(apex) + 40, py(apex) + 12, 32f, tpl.accent, bold = true)
    }

    private fun drawContours(c: Canvas, r: RectF, tpl: CardTemplate) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = 0xFFC9D1C4.toInt() }
        var y = r.top - 60
        var i = 0
        while (y < r.bottom + 60) {
            val path = Path()
            var x = r.left
            path.moveTo(x, y)
            while (x <= r.right) {
                path.lineTo(x, y + 26f * sin((x / 180f + i * 0.7f).toDouble()).toFloat() - 18f * cos((x / 97f + i).toDouble()).toFloat())
                x += 12f
            }
            c.drawPath(path, p)
            y += 54f; i++
        }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, cond: Boolean = false, spacing: Float = 0f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; letterSpacing = spacing
            typeface = if (cond) condensed else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        c.drawText(s, x, y, p)
    }

    private fun textRight(c: Canvas, s: String, xr: Float, y: Float, size: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; textAlign = Paint.Align.RIGHT }
        c.drawText(s, xr, y, p)
    }

    private fun wrap(s: String, size: Float, maxW: Float): List<String> {
        val p = Paint().apply { textSize = size; typeface = condensed }
        val out = ArrayList<String>()
        var line = ""
        s.split(' ').forEach { word ->
            val cand = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(cand) <= maxW || line.isEmpty()) line = cand else { out += line; line = word }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    private fun fmt1(v: Double) = String.format(TR, "%.1f", v)
    private fun fmt2(v: Double) = String.format(TR, "%.2f", v)
    private fun gStr(v: Float) = (if (v >= 0) "+" else "−") + String.format(TR, "%.2f", abs(v))
    private fun dur(sec: Double): String { val m = (sec / 60).toInt(); return if (m >= 60) "${m / 60} SA ${m % 60} DK" else "$m DK" }

    fun saveAndShare(ctx: Context, bmp: Bitmap, name: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(ctx: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Dışa aktar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
