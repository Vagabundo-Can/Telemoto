package com.vagabundo.telemoto.data

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/** Sürüş modu. */
enum class RideMode(val code: String, val label: String) {
    PERF("PERF", "Performans"),
    ECO("ECO", "Ekonomi");

    companion object {
        fun of(code: String?): RideMode = entries.firstOrNull { it.code == code } ?: PERF
    }
}

/**
 * Füzyon sonrası tek örnek (25 Hz).
 * lean: derece, + sağa yatış. gLon: g, + hızlanma / − fren. gLat: g, + sağa dönüş.
 * yaw: derece/sn (dünya düşeyi etrafında, + sola dönüş). speed: m/s.
 */
data class Sample(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Float,
    val speed: Float,
    val lean: Float,
    val gLon: Float,
    val gLat: Float,
    val yaw: Float,
    val gpsFresh: Boolean,
)

data class MarkEvent(val t: Long, val type: String)

/** Saatten gelen nabız örneği. */
data class HrSample(val t: Long, val bpm: Int)

/** .ridelog başlığı. Düz metin "#anahtar=değer" satırları: grep'lenebilir, overlay uygulaması da okur. */
data class RideHeader(
    val id: String,
    var name: String,
    val mode: RideMode,
    val startEpochMs: Long,
    val calib: String,
    val appVersion: String,
)

/**
 * .ridelog v1 biçimi (UTF-8 metin):
 *   #telemoto-ridelog v1
 *   #id=… #name=… #mode=PERF|ECO #start=epochMs #calib=… #app=…
 *   #cols=t_ms,lat,lon,alt,speed,lean,glon,glat,yaw,gps
 *   S,<t>,<lat>,<lon>,<alt>,<speed>,<lean>,<glon>,<glat>,<yaw>,<0|1>
 *   E,<t>,<TYPE>
 *   H,<t>,<bpm>          (Galaxy Watch nabzı, varsa)
 */
object RideLogFormat {
    const val MAGIC = "#telemoto-ridelog v1"
    const val COLS = "t_ms,lat,lon,alt,speed,lean,glon,glat,yaw,gps"

    fun writeHeader(w: BufferedWriter, h: RideHeader) {
        w.write(MAGIC); w.newLine()
        w.write("#id=${h.id}"); w.newLine()
        w.write("#name=${h.name.replace('\n', ' ')}"); w.newLine()
        w.write("#mode=${h.mode.code}"); w.newLine()
        w.write("#start=${h.startEpochMs}"); w.newLine()
        w.write("#calib=${h.calib}"); w.newLine()
        w.write("#app=${h.appVersion}"); w.newLine()
        w.write("#cols=$COLS"); w.newLine()
    }

    fun writeSample(w: BufferedWriter, s: Sample) {
        w.write(
            String.format(
                Locale.US, "S,%d,%.7f,%.7f,%.1f,%.2f,%.2f,%.3f,%.3f,%.2f,%d",
                s.t, s.lat, s.lon, s.alt, s.speed, s.lean, s.gLon, s.gLat, s.yaw, if (s.gpsFresh) 1 else 0
            )
        )
        w.newLine()
    }

    fun writeEvent(w: BufferedWriter, e: MarkEvent) {
        w.write("E,${e.t},${e.type}"); w.newLine()
    }

    fun writeHr(w: BufferedWriter, h: HrSample) {
        w.write("H,${h.t},${h.bpm}"); w.newLine()
    }

    class Parsed(val header: RideHeader, val samples: List<Sample>, val events: List<MarkEvent>, val hr: List<HrSample> = emptyList()) {
        /** t anındaki nabız (±10 sn içinde en yakın örnek) ya da null. */
        fun hrAt(t: Long): Int? {
            if (hr.isEmpty()) return null
            var lo = 0; var hi = hr.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (hr[m].t < t) lo = m + 1 else hi = m }
            val c = listOfNotNull(hr.getOrNull(lo), hr.getOrNull(lo - 1)).minByOrNull { kotlin.math.abs(it.t - t) } ?: return null
            return if (kotlin.math.abs(c.t - t) <= 10_000) c.bpm else null
        }
    }

    fun read(file: File): Parsed = file.bufferedReader(Charsets.UTF_8).use { read(it) }

    fun read(r: BufferedReader): Parsed {
        val meta = HashMap<String, String>()
        val samples = ArrayList<Sample>(50_000)
        val events = ArrayList<MarkEvent>()
        val hr = ArrayList<HrSample>()
        r.lineSequence().forEach { line ->
            when {
                line.startsWith("#") -> {
                    val i = line.indexOf('=')
                    if (i > 1) meta[line.substring(1, i)] = line.substring(i + 1)
                }
                line.startsWith("S,") -> parseSample(line)?.let { samples.add(it) }
                line.startsWith("E,") -> {
                    val p = line.split(',')
                    if (p.size >= 3) p[1].toLongOrNull()?.let { events.add(MarkEvent(it, p[2])) }
                }
                line.startsWith("H,") -> {
                    val p = line.split(',')
                    val t = p.getOrNull(1)?.toLongOrNull(); val b = p.getOrNull(2)?.toIntOrNull()
                    if (t != null && b != null && b in 30..230) hr.add(HrSample(t, b))
                }
            }
        }
        val h = RideHeader(
            id = meta["id"] ?: "",
            name = meta["name"] ?: "Sürüş",
            mode = RideMode.of(meta["mode"]),
            startEpochMs = meta["start"]?.toLongOrNull() ?: 0L,
            calib = meta["calib"] ?: "",
            appVersion = meta["app"] ?: "",
        )
        return Parsed(h, samples, events, hr.sortedBy { it.t })
    }

    private fun parseSample(line: String): Sample? {
        val p = line.split(',')
        if (p.size < 11) return null
        return try {
            Sample(
                t = p[1].toLong(),
                lat = p[2].toDouble(), lon = p[3].toDouble(),
                alt = p[4].toFloat(), speed = p[5].toFloat(),
                lean = p[6].toFloat(), gLon = p[7].toFloat(), gLat = p[8].toFloat(),
                yaw = p[9].toFloat(), gpsFresh = p[10] == "1",
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
