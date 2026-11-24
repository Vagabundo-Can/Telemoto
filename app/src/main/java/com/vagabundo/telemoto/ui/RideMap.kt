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
