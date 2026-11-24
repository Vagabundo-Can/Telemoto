package com.vagabundo.telemoto.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Tile: durum + son sürüş özeti; dokununca uygulamayı açar. */
class TelemotoTile : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val sp = getSharedPreferences("tile", Context.MODE_PRIVATE)
        val phase = sp.getString("phase", "IDLE") ?: "IDLE"
        val mode = if (sp.getString("mode", "PERF") == "ECO") "Ekonomi" else "Performans"
        val status = when (phase) {
            "RECORDING" -> "● Kayıt sürüyor · $mode"
            "WARMUP", "CALIBRATING", "READY" -> "Hazırlanıyor · $mode"
            else -> "Başlatmak için dokun"
        }
        val last = sp.getString("last", null)

        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WatchActivity::class.java.name)
                            .build()
                    ).build()
            ).build()

        fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
            LayoutElementBuilders.Text.Builder()
                .setText(s)
                .setMaxLines(2)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(size))
                        .setColor(argb(color))
                        .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                        .build()
                ).build()

        val col = LayoutElementBuilders.Column.Builder()
            .addContent(text("TELEMOTO", 18f, 0xFFFFB020.toInt(), bold = true))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(text(status, 14f, if (phase == "RECORDING") 0xFFFF6B6B.toInt() else 0xFFE8EDF2.toInt(), bold = true))
        if (last != null) {
            col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            col.addContent(text(last, 12f, 0xFF9AA6B2.toInt()))
        }

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(col.build())
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}
