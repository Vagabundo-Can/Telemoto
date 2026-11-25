package com.vagabundo.telemoto.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.vagabundo.telemoto.analysis.DriveEvent
import com.vagabundo.telemoto.analysis.EventType
import com.vagabundo.telemoto.data.Sample
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.abs

/** Konum imleci + olay işaretleri. */
private class MarkerOverlay(private val tm: TmColors) : Overlay() {
    var pos: GeoPoint? = null
    var heading = 0f
    var events: List<DriveEvent> = emptyList()
    var showEvents = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val pt = android.graphics.Point()

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mv.projection
        if (showEvents) {
            events.forEach { e ->
                proj.toPixels(GeoPoint(e.lat, e.lon), pt)
                val x = pt.x.toFloat(); val y = pt.y.toFloat()
                when (e.type) {
                    EventType.ECO_ACCEL -> { fill.color = 0xFFFFB020.toInt(); c.drawPath(tri(x, y, true), fill) }
                    EventType.ECO_BRAKE -> { fill.color = tm.red.toArgb(); c.drawPath(tri(x, y, false), fill) }
                    EventType.STOP -> { fill.color = tm.mute.toArgb(); c.drawCircle(x, y, 12f, fill) }
                    EventType.MARK -> { fill.color = tm.ink.toArgb(); c.drawRect(x - 9, y - 9, x + 9, y + 9, fill) }
                    else -> {}
                }
            }
        } else {
            events.filter { it.type == EventType.BRAKE || it.type == EventType.HARD_BRAKE || it.type == EventType.MARK }.forEach { e ->
                proj.toPixels(GeoPoint(e.lat, e.lon), pt)
                stroke.color = if (e.type == EventType.MARK) tm.ink.toArgb() else tm.red.toArgb()
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 13f, stroke)
            }
        }
        val p = pos ?: return
        proj.toPixels(p, pt)
        fill.color = 0x40FFB020
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 34f, fill)
        c.save()
        c.rotate(heading, pt.x.toFloat(), pt.y.toFloat())
        val arrow = Path().apply {
            moveTo(pt.x.toFloat(), pt.y - 26f); lineTo(pt.x + 18f, pt.y + 18f); lineTo(pt.x.toFloat(), pt.y + 8f); lineTo(pt.x - 18f, pt.y + 18f); close()
        }
        fill.color = 0xFFFFB020.toInt()
        c.drawPath(arrow, fill)
        stroke.color = 0xFF0B0E11.toInt()
        c.drawPath(arrow, stroke)
        c.restore()
    }

    private fun tri(x: Float, y: Float, up: Boolean) = Path().apply {
        if (up) { moveTo(x, y - 14); lineTo(x + 13, y + 10); lineTo(x - 13, y + 10) } else { moveTo(x, y + 14); lineTo(x + 13, y - 10); lineTo(x - 13, y - 10) }
        close()
    }
}

private class MapHolder(val view: MapView, val marker: MarkerOverlay)

/**
 * Rota haritası. Performans: yatışa göre renkli çizgi (mavi sol, turuncu sağ). Ekonomi: olay işaretleri.
 * pts: harita için seyreltilmiş örnekler (≈1 Hz).
 */
@Composable
fun RideMap(
    pts: List<Sample>,
    events: List<DriveEvent>,
    ecoMode: Boolean,
    cursor: Sample?,
    cursorHeading: Float,
    modifier: Modifier = Modifier,
) {
    val tm = LocalTm.current
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        // Önizleme / ekran görüntüsü testi: harita karosu yok, rota çizimi
        RoutePreview(pts, events, ecoMode, cursor, modifier)
        return
    }
    val holder = remember(tm.light) { arrayOfNulls<MapHolder>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val mv = MapView(ctx)
            mv.setTileSource(TileSourceFactory.MAPNIK)
            mv.setMultiTouchControls(true)
            mv.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            if (!tm.light) mv.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            val marker = MarkerOverlay(tm)
            holder[0] = MapHolder(mv, marker)
            buildRoute(mv, pts, tm)
            mv.overlays.add(marker)
            val good = pts.filter { it.lat != 0.0 }
            if (good.size > 1) {
                val box = BoundingBox.fromGeoPoints(good.map { GeoPoint(it.lat, it.lon) })
                mv.addOnFirstLayoutListener { _, _, _, _, _ -> mv.zoomToBoundingBox(box.increaseByScale(1.15f), false) }
            }
            mv
        },
        update = { mv ->
            val h = holder[0] ?: return@AndroidView
            h.marker.events = events
            h.marker.showEvents = ecoMode
            h.marker.pos = cursor?.takeIf { it.lat != 0.0 }?.let { GeoPoint(it.lat, it.lon) }
            h.marker.heading = cursorHeading
            mv.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}

private fun buildRoute(mv: MapView, pts: List<Sample>, tm: TmColors) {
    if (pts.size < 2) return
    fun colorOf(lean: Float): Int {
        val k = (abs(lean) / 45f).coerceIn(0f, 1f)
        val base = when { abs(lean) < 5f -> tm.neutral; lean > 0 -> tm.right; else -> tm.left }
        val a = if (abs(lean) < 5f) 1f else 0.45f + 0.55f * k
        return base.copy(alpha = a).toArgb()
    }
    var cur = ArrayList<GeoPoint>()
    var curColor = colorOf(pts[0].lean)
    fun flush() {
        if (cur.size >= 2) {
            val line = Polyline(mv)
            line.setPoints(cur)
            line.outlinePaint.color = curColor
            line.outlinePaint.strokeWidth = 10f
            line.outlinePaint.strokeCap = Paint.Cap.ROUND
            mv.overlays.add(line)
        }
    }
    for (s in pts) {
        if (s.lat == 0.0) continue
        val c = colorOf((s.lean / 5).toInt() * 5f)
        val g = GeoPoint(s.lat, s.lon)
        if (c != curColor && cur.isNotEmpty()) {
            cur.add(g); flush()
            cur = arrayListOf(g)
            curColor = c
        } else cur.add(g)
    }
    flush()
}

@Composable
private fun RoutePreview(pts: List<Sample>, events: List<DriveEvent>, ecoMode: Boolean, cursor: Sample?, modifier: Modifier) {
    val tm = LocalTm.current
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(if (tm.light) androidx.compose.ui.graphics.Color(0xFFE6EBEF) else androidx.compose.ui.graphics.Color(0xFF11161B))
        val grid = if (tm.light) androidx.compose.ui.graphics.Color(0xFFD6DDE3) else androidx.compose.ui.graphics.Color(0xFF1A2128)
        var gx = 0f; while (gx < size.width) { drawLine(grid, androidx.compose.ui.geometry.Offset(gx, 0f), androidx.compose.ui.geometry.Offset(gx, size.height), 1f); gx += 60f }
        var gy = 0f; while (gy < size.height) { drawLine(grid, androidx.compose.ui.geometry.Offset(0f, gy), androidx.compose.ui.geometry.Offset(size.width, gy), 1f); gy += 60f }
        val g = pts.filter { it.lat != 0.0 }
        if (g.size < 2) return@Canvas
        val k = kotlin.math.cos(Math.toRadians(g[0].lat))
        val minX = g.minOf { it.lon * k }; val maxX = g.maxOf { it.lon * k }
        val minY = g.minOf { it.lat }; val maxY = g.maxOf { it.lat }
        val pad = 30f
        val s = minOf((size.width - 2 * pad) / (maxX - minX).coerceAtLeast(1e-9), (size.height - 2 * pad) / (maxY - minY).coerceAtLeast(1e-9))
        val ox = (size.width - ((maxX - minX) * s).toFloat()) / 2; val oy = (size.height - ((maxY - minY) * s).toFloat()) / 2
        fun p(a: Double, b: Double) = androidx.compose.ui.geometry.Offset((ox + (b * k - minX) * s).toFloat(), (oy + (maxY - a) * s).toFloat())
        for (i in 1 until g.size) {
            val l = g[i].lean
            val col = when { ecoMode -> if (tm.light) androidx.compose.ui.graphics.Color(0xFF3E7C59) else androidx.compose.ui.graphics.Color(0xFF3F8F63)
                abs(l) < 5f -> tm.neutral; l > 0 -> tm.right; else -> tm.left }
            val a = (if (ecoMode || abs(l) < 5f) 1f else 0.45f + 0.55f * (abs(l) / 45f).coerceIn(0f, 1f))
            drawLine(col.copy(alpha = a), p(g[i - 1].lat, g[i - 1].lon), p(g[i].lat, g[i].lon), 9f, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        events.forEach { e ->
            val c = p(e.lat, e.lon)
            when {
                ecoMode && e.type == EventType.ECO_ACCEL -> drawCircle(androidx.compose.ui.graphics.Color(0xFFFFB020), 11f, c)
                ecoMode && e.type == EventType.ECO_BRAKE -> drawCircle(tm.red, 11f, c)
                ecoMode && e.type == EventType.STOP -> drawCircle(tm.mute, 10f, c)
                !ecoMode && (e.type == EventType.BRAKE || e.type == EventType.HARD_BRAKE) -> drawCircle(tm.red, 12f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                else -> {}
            }
        }
        cursor?.let {
            val c = p(it.lat, it.lon)
            drawCircle(androidx.compose.ui.graphics.Color(0x40FFB020), 30f, c)
            drawCircle(androidx.compose.ui.graphics.Color(0xFFFFB020), 14f, c)
            drawCircle(androidx.compose.ui.graphics.Color(0xFF0B0E11), 14f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
    }
}
