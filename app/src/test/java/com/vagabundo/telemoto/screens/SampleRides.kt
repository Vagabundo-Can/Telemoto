package com.vagabundo.telemoto.screens

import com.vagabundo.telemoto.analysis.RideAnalyzer
import com.vagabundo.telemoto.data.HrSample
import com.vagabundo.telemoto.data.RideHeader
import com.vagabundo.telemoto.data.RideLogFormat
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.RideStore
import com.vagabundo.telemoto.data.Sample
import com.vagabundo.telemoto.data.Thresholds
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Kılavuz ekran görüntüleri için sentetik ama fiziksel olarak tutarlı sürüşler. */
object SampleRides {
    private const val G = 9.80665
    const val PERF_ID = "20261124-130000"

    /** Virajlı yol: eğrilik κ(s) (1/m, + sağa). Hız, maks yatış 42° ve ±ivme sınırlarıyla seçilir. */
    private fun perf(): Pair<List<Sample>, List<HrSample>> {
        val out = ArrayList<Sample>(); val hr = ArrayList<HrSample>()
        val dt = 0.04
        var lat = 40.4720; var lon = 32.6480; var psi = Math.toRadians(70.0)
        var v = 0.0; var s = 0.0; var t = 0L
        val totalS = 24_000.0
        fun kappa(x: Double): Double {
            val base = 0.016 * sin(x / 210.0) + 0.011 * sin(x / 83.0 + 1.3) + 0.006 * sin(x / 37.0 + 0.4)
            val straight = if ((x / 1500.0).toInt() % 3 == 2) 0.15 else 1.0
            return base * straight
        }
        while (s < totalS) {
            // ileri bak: 60 m önündeki en sert eğriliğe göre hedef hız
            var kMax = 0.0; var d = 0.0
            while (d < 80) { kMax = max(kMax, abs(kappa(s + d))); d += 5 }
            val vCorner = if (kMax < 1e-4) 33.0 else sqrt(G * kotlin.math.tan(Math.toRadians(42.0)) / kMax)
            val vTarget = min(min(vCorner, 25.0), if (s < 200) 15.0 else 99.0)
            val a = when {
                v < vTarget - 0.5 -> min(0.28, (vTarget - v) * 0.12) * G
                v > vTarget + 0.5 -> -min(0.62, (v - vTarget) * 0.25) * G
                else -> 0.0
            }
            v = max(0.0, v + a * dt)
            val k = kappa(s)
            psi += v * k * dt
            lat += v * cos(psi) * dt / 111_320.0
            lon += v * sin(psi) * dt / (111_320.0 * cos(Math.toRadians(lat)))
            s += v * dt
            val lean = Math.toDegrees(atan(v * v * k / G)) + 0.8 * sin(t / 700.0)
            out.add(Sample(t, lat, lon, 1050f + (s / 60).toFloat(), v.toFloat(), lean.toFloat(), (a / G).toFloat(), (v * v * k / G).toFloat(), Math.toDegrees(-v * k).toFloat(), t % 1000 == 0L))
            if (t % 2000 == 0L) hr.add(HrSample(t, (98 + 22 * min(1.0, abs(lean) / 40.0) + 6 * sin(t / 50_000.0)).toInt()))
            t += 40
        }
        return out to hr
    }

    /** Şehir içi: 50 km/h seyir, ~700 m'de bir ışık, birkaç sert kalkış/fren. */
    private fun eco(seed: Int, km: Double): List<Sample> {
        val out = ArrayList<Sample>()
        val dt = 0.04
        var lat = 39.9700; var lon = 32.6300 + seed * 0.001; var psi = Math.toRadians(80.0)
        var v = 0.0; var s = 0.0; var t = 0L
        var nextStop = 650.0 + seed * 40; var waitUntil = -1L
        var harsh = false
        while (s < km * 1000) {
            val distToStop = nextStop - s
            val a: Double
            if (waitUntil > 0) {
                a = 0.0; v = 0.0
                if (t >= waitUntil) { waitUntil = -1; nextStop = s + 600 + ((s.toInt() * 7 + seed * 131) % 500); harsh = ((s.toInt() + seed) % 5 == 0) }
            } else if (distToStop < v * v / (2 * 0.2 * G) + 5) {
                val dec = if (harsh) 0.33 else 0.18
                a = -dec * G
                if (v < 0.4) { v = 0.0; waitUntil = t + 8_000 + (seed * 1300L % 20_000) }
            } else {
                a = if (v < 13.8) (if (harsh) 0.24 else 0.11) * G else 0.0
            }
            v = max(0.0, v + a * dt)
            psi += v * 0.0006 * sin(s / 400.0) * dt
            lat += v * cos(psi) * dt / 111_320.0
            lon += v * sin(psi) * dt / (111_320.0 * cos(Math.toRadians(lat)))
            s += v * dt
            out.add(Sample(t, lat, lon, 880f, v.toFloat(), (2 * sin(t / 3000.0)).toFloat(), (a / G).toFloat(), 0f, 0f, t % 1000 == 0L))
            t += 40
        }
        return out
    }

    private fun at(daysAgo: Int, h: Int, m: Int) = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
    }.timeInMillis

    fun seed(store: RideStore) {
        if (store.summary(PERF_ID) != null) return
        val th = Thresholds()
        fun save(id: String, name: String, mode: RideMode, start: Long, samples: List<Sample>, hr: List<HrSample> = emptyList()) {
            val h = RideHeader(id, name, mode, start, "örnek", "0.1")
            store.logFile(id).bufferedWriter().use { w ->
                RideLogFormat.writeHeader(w, h)
                samples.forEach { RideLogFormat.writeSample(w, it) }
                hr.forEach { RideLogFormat.writeHr(w, it) }
            }
            store.saveSummary(h, RideAnalyzer.analyze(samples, emptyList(), th))
        }
        val (p, hr) = perf()
        save(PERF_ID, "Karagöl Sürüşü", RideMode.PERF, at(1, 13, 0), p, hr)
        save("20261125-081100", "Ev → İş", RideMode.ECO, at(0, 8, 11), eco(1, 18.6))
        save("20261124-180400", "İş → Ev", RideMode.ECO, at(1, 18, 4), eco(2, 18.9))
        save("20261124-081200", "Ev → İş", RideMode.ECO, at(1, 8, 12), eco(3, 18.6))
        save("20261123-180000", "İş → Ev", RideMode.ECO, at(2, 18, 0), eco(4, 18.9))
        save("20261123-080700", "Ev → İş", RideMode.ECO, at(2, 8, 7), eco(5, 18.6))
    }
}
