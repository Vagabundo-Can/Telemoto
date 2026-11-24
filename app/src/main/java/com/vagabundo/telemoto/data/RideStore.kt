package com.vagabundo.telemoto.data

import android.content.Context
import com.vagabundo.telemoto.analysis.RideStats
import java.io.File
import java.util.Locale

/** Liste/özet ekranları için hafif özet (her sürüş için .sum dosyası). */
data class RideSummary(
    val id: String,
    val name: String,
    val mode: RideMode,
    val startEpochMs: Long,
    val durationS: Double,
    val distanceM: Double,
    val maxSpeed: Float,
    val maxLeanL: Float,
    val maxLeanR: Float,
    val corners: Int,
    val maxBrakeG: Float,
    val hardBrakes: Int,
    val ecoScore: Int?,
    val stops: Int,
    val stoppedS: Double,
    val pke: Double,
    val ecoAccel: Int,
    val ecoBrake: Int,
)

class RideStore(ctx: Context) {
    val dir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "ridelogs").apply { mkdirs() }

    fun logFile(id: String) = File(dir, "$id.ridelog")
    private fun sumFile(id: String) = File(dir, "$id.sum")

    fun list(): List<RideSummary> =
        dir.listFiles { f -> f.name.endsWith(".sum") }?.mapNotNull { readSummary(it) }
            ?.sortedByDescending { it.startEpochMs } ?: emptyList()

    fun summary(id: String): RideSummary? = readSummary(sumFile(id))

    fun saveSummary(h: RideHeader, st: RideStats) {
        val s = RideSummary(
            id = h.id, name = h.name, mode = h.mode, startEpochMs = h.startEpochMs,
            durationS = st.durationS, distanceM = st.distanceM, maxSpeed = st.maxSpeed,
            maxLeanL = st.maxLeanLeft, maxLeanR = st.maxLeanRight, corners = st.cornerCount,
            maxBrakeG = st.maxBrakeG, hardBrakes = st.hardBrakeCount, ecoScore = st.ecoScore,
            stops = st.stops, stoppedS = st.stoppedS, pke = st.pke,
            ecoAccel = st.ecoAccelCount, ecoBrake = st.ecoBrakeCount,
        )
        writeSummary(s)
    }

    fun rename(id: String, name: String) {
        summary(id)?.let { writeSummary(it.copy(name = name)) }
        // .ridelog başlığını da güncelle
        val f = logFile(id)
        if (f.exists()) {
            val tmp = File(dir, "$id.tmp")
            f.bufferedReader().use { r ->
                tmp.bufferedWriter().use { w ->
                    r.lineSequence().forEach { line ->
                        w.write(if (line.startsWith("#name=")) "#name=${name.replace('\n', ' ')}" else line)
                        w.newLine()
                    }
                }
            }
            tmp.renameTo(f)
        }
    }

    fun delete(id: String) {
        logFile(id).delete(); sumFile(id).delete()
        File(dir, "$id.gpx").delete(); File(dir, "$id.csv").delete()
    }

    private fun writeSummary(s: RideSummary) {
        val m = linkedMapOf(
            "id" to s.id, "name" to s.name, "mode" to s.mode.code, "start" to s.startEpochMs,
            "duration" to s.durationS, "distance" to s.distanceM, "maxSpeed" to s.maxSpeed,
            "maxLeanL" to s.maxLeanL, "maxLeanR" to s.maxLeanR, "corners" to s.corners,
            "maxBrakeG" to s.maxBrakeG, "hardBrakes" to s.hardBrakes, "eco" to (s.ecoScore ?: -1),
            "stops" to s.stops, "stoppedS" to s.stoppedS, "pke" to s.pke,
            "ecoAccel" to s.ecoAccel, "ecoBrake" to s.ecoBrake,
        )
        sumFile(s.id).writeText(m.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun readSummary(f: File): RideSummary? = runCatching {
        if (!f.exists()) return null
        val m = f.readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        RideSummary(
            id = m["id"]!!, name = m["name"] ?: "Sürüş", mode = RideMode.of(m["mode"]),
            startEpochMs = m["start"]!!.toLong(), durationS = m["duration"]!!.toDouble(),
            distanceM = m["distance"]!!.toDouble(), maxSpeed = m["maxSpeed"]!!.toFloat(),
            maxLeanL = m["maxLeanL"]!!.toFloat(), maxLeanR = m["maxLeanR"]!!.toFloat(),
            corners = m["corners"]!!.toInt(), maxBrakeG = m["maxBrakeG"]!!.toFloat(),
            hardBrakes = m["hardBrakes"]?.toInt() ?: 0,
            ecoScore = m["eco"]?.toInt()?.takeIf { it >= 0 },
            stops = m["stops"]?.toInt() ?: 0, stoppedS = m["stoppedS"]?.toDouble() ?: 0.0,
            pke = m["pke"]?.toDouble() ?: 0.0,
            ecoAccel = m["ecoAccel"]?.toInt() ?: 0, ecoBrake = m["ecoBrake"]?.toInt() ?: 0,
        )
    }.getOrNull()

    /** GPX (taze GPS noktaları) dışa aktarımı. */
    fun exportGpx(p: RideLogFormat.Parsed): File {
        val f = File(dir, "${p.header.id}.gpx")
        f.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Telemoto\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.write("<trk><name>${xml(p.header.name)}</name><trkseg>\n")
            p.samples.filter { it.gpsFresh }.forEach { s ->
                val time = java.time.Instant.ofEpochMilli(p.header.startEpochMs + s.t).toString()
                w.write(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n", s.lat, s.lon, s.alt, time))
            }
            w.write("</trkseg></trk>\n</gpx>\n")
        }
        return f
    }

    /** CSV (tüm 25 Hz örnekler) dışa aktarımı. */
    fun exportCsv(p: RideLogFormat.Parsed): File {
        val f = File(dir, "${p.header.id}.csv")
        f.bufferedWriter().use { w ->
            w.write("time_iso,t_ms,lat,lon,alt_m,speed_kmh,lean_deg,g_lon,g_lat,yaw_dps,gps_fresh\n")
            p.samples.forEach { s ->
                val time = java.time.Instant.ofEpochMilli(p.header.startEpochMs + s.t).toString()
                w.write(String.format(Locale.US, "%s,%d,%.7f,%.7f,%.1f,%.1f,%.1f,%.3f,%.3f,%.1f,%d\n",
                    time, s.t, s.lat, s.lon, s.alt, s.speed * 3.6f, s.lean, s.gLon, s.gLat, s.yaw, if (s.gpsFresh) 1 else 0))
            }
        }
        return f
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
