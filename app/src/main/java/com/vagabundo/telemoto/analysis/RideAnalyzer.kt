package com.vagabundo.telemoto.analysis

import com.vagabundo.telemoto.data.Sample
import com.vagabundo.telemoto.data.Thresholds
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

enum class EventType(val label: String) {
    BRAKE("Fren"), HARD_BRAKE("Sert fren"), ACCEL("Ani gaz"),
    ECO_ACCEL("Ani hızlanma"), ECO_BRAKE("Ani fren"), STOP("Duruş"), MARK("İşaret")
}

data class DriveEvent(val type: EventType, val t: Long, val idx: Int, val lat: Double, val lon: Double, val value: Float)

data class Corner(
    val no: Int,
    val startIdx: Int,
    val apexIdx: Int,
    val endIdx: Int,
    val right: Boolean,
    val maxLean: Float,
    val vIn: Float,
    val vApex: Float,
    val vOut: Float,
    val braked: Boolean,
    val startDistM: Double,
)

data class EcoLoss(val accel: Double, val brake: Double, val stops: Double, val variation: Double) {
    val total get() = accel + brake + stops + variation
}

data class RideStats(
    val durationS: Double,
    val distanceM: Double,
    val maxSpeed: Float,
    val avgMovingSpeed: Float,
    val maxLeanLeft: Float,
    val maxLeanRight: Float,
    val maxBrakeG: Float,
    val maxAccelG: Float,
    val corners: List<Corner>,
    val events: List<DriveEvent>,
    // Ekonomi
    val stops: Int,
    val stoppedS: Double,
    val pke: Double,
    val ecoAccelCount: Int,
    val ecoBrakeCount: Int,
    val ecoLoss: EcoLoss,
    val ecoScore: Int?,
    /** Her örneğe karşılık birikimli mesafe (m). */
    val cumDist: DoubleArray,
) {
    val perfBrakeCount get() = events.count { it.type == EventType.BRAKE || it.type == EventType.HARD_BRAKE }
    val hardBrakeCount get() = events.count { it.type == EventType.HARD_BRAKE }
    val perfAccelCount get() = events.count { it.type == EventType.ACCEL }
    val cornerCount get() = corners.size
}

/**
 * Artımlı analiz: kayıt sırasında canlı sayaçlar, sonrasında tam dosya için aynı kod yolu.
 */
class RideAnalyzer(private val th: Thresholds = Thresholds()) {

    private val samples = ArrayList<Sample>(50_000)
    private val cum = ArrayList<Double>(50_000)

    private var distance = 0.0
    private var lastFix: Sample? = null
    private var movingTimeS = 0.0
    private var maxSpeed = 0f
    private var maxL = 0f
    private var maxR = 0f
    private var maxBrake = 0f
    private var maxAccel = 0f

    private val events = ArrayList<DriveEvent>()
    private val corners = ArrayList<Corner>()

    // Olay durum makineleri
    private inner class Threshold(val type: EventType, val limit: Float, val below: Boolean) {
        var startT = -1L
        var peak = 0f
        var peakIdx = -1
        var fired = false
        fun push(s: Sample, idx: Int) {
            val v = s.gLon
            val over = if (below) v <= limit else v >= limit
            if (over) {
                if (startT < 0) { startT = s.t; peak = v; peakIdx = idx; fired = false }
                if ((below && v < peak) || (!below && v > peak)) { peak = v; peakIdx = idx }
                if (!fired && (s.t - startT) / 1000f >= th.minEventSec) fired = true
            } else if (startT >= 0) {
                val dup = type == EventType.BRAKE && events.any { it.type == EventType.HARD_BRAKE && it.t >= startT }
                if (fired && !dup) {
                    val p = samples[peakIdx]
                    events.add(DriveEvent(type, p.t, peakIdx, p.lat, p.lon, peak))
                }
                startT = -1
            }
        }
    }

    private val detectors = listOf(
        Threshold(EventType.HARD_BRAKE, th.perfHardBrakeG, true),
        Threshold(EventType.BRAKE, th.perfBrakeG, true),
        Threshold(EventType.ACCEL, th.perfAccelG, false),
        Threshold(EventType.ECO_ACCEL, th.ecoAccelG, false),
        Threshold(EventType.ECO_BRAKE, th.ecoBrakeG, true),
    )

    // Viraj durum makinesi
    private var cStart = -1
    private var cSign = 0
    private var cApex = -1
    private var cBelowSince = -1L

    // Duruş
    private var stopped = false
    private var slowSince = -1L
    private var wasMoving = false
    private var stops = 0
    private var stoppedS = 0.0
    private var stopStartT = 0L

    // PKE: 1 sn aralıklı hız örnekleri
    private var pkeLastT = -1L
    private var pkeLastV = 0.0
    private var pkeSum = 0.0

    val count get() = samples.size
    val distanceM get() = distance
    val maxLeanLeft get() = maxL
    val maxLeanRight get() = maxR
    val maxSpeedMps get() = maxSpeed
    val cornerCount get() = corners.size
    val stopCount get() = stops
    val stoppedSeconds get() = stoppedS + if (stopped) (lastT() - stopStartT) / 1000.0 else 0.0
    fun eventCount(t: EventType) = events.count { it.type == t }
    val pke get() = if (distance > 50) pkeSum / distance else 0.0
    val maxBrakeG get() = maxBrake
    val maxAccelG get() = maxAccel

    private fun lastT() = if (samples.isEmpty()) 0L else samples.last().t

    fun push(s: Sample) {
        val idx = samples.size
        val prev = samples.lastOrNull()
        samples.add(s)

        // Mesafe: yalnızca taze GPS örnekleri arasında
        if (s.gpsFresh) {
            val lf = lastFix
            if (lf != null) {
                val d = haversine(lf.lat, lf.lon, s.lat, s.lon)
                val dt = (s.t - lf.t) / 1000.0
                // GPS sıçramalarını ele: 300 km/h üstü hareketi yok say
                if (dt > 0 && d / dt < 85.0 && s.speed > 0.5f) distance += d
            }
            lastFix = s
        }
        cum.add(distance)

        if (prev != null && s.speed > th.moveSpeedMps) movingTimeS += (s.t - prev.t) / 1000.0
        maxSpeed = max(maxSpeed, s.speed)
        if (s.speed > 2f) {
            if (s.lean < 0) maxL = max(maxL, -s.lean) else maxR = max(maxR, s.lean)
        }
        if (s.speed > 1.5f) {
            maxBrake = min(maxBrake, s.gLon)
            maxAccel = max(maxAccel, s.gLon)
        }

        if (s.speed > 1.5f) detectors.forEach { it.push(s, idx) }
        pushCorner(s, idx)
        pushStop(s)
        pushPke(s)
    }

    private fun pushCorner(s: Sample, idx: Int) {
        val lean = s.lean
        val mag = abs(lean)
        val sg = sign(lean).toInt()
        if (cStart < 0) {
            if (mag >= th.cornerStartDeg && s.speed > 2f) {
                cStart = idx; cSign = sg; cApex = idx; cBelowSince = -1
            }
            return
        }
        // S virajı: ters yöne başlangıç eşiği aşıldıysa mevcut virajı kapat, yenisini aç
        if (sg != cSign && mag >= th.cornerStartDeg) {
            closeCorner(idx - 1)
            cStart = idx; cSign = sg; cApex = idx; cBelowSince = -1
            return
        }
        if (sg == cSign && mag > abs(samples[cApex].lean)) cApex = idx
        if (mag < th.cornerEndDeg || sg != cSign) {
            if (cBelowSince < 0) cBelowSince = s.t
            if ((s.t - cBelowSince) / 1000f >= th.cornerEndHoldSec) {
                closeCorner(idx)
            }
        } else {
            cBelowSince = -1
        }
    }

    private fun closeCorner(endIdx: Int) {
        val st = samples[cStart]
        val en = samples[endIdx.coerceAtLeast(cStart)]
        val apex = samples[cApex]
        val durS = (en.t - st.t) / 1000f
        val maxLean = abs(apex.lean)
        if (durS >= th.cornerMinSec && maxLean >= th.cornerStartDeg + 2f) {
            // Viraj içinde ya da girişten 1,5 sn önce fren
            var braked = false
            var i = cStart
            while (i > 0 && st.t - samples[i].t < 1500) i--
            for (k in i..endIdx.coerceAtMost(samples.size - 1)) {
                if (samples[k].gLon <= th.perfBrakeG) { braked = true; break }
            }
            corners.add(
                Corner(
                    no = corners.size + 1, startIdx = cStart, apexIdx = cApex, endIdx = endIdx,
                    right = cSign > 0, maxLean = maxLean,
                    vIn = st.speed, vApex = apex.speed, vOut = en.speed,
                    braked = braked, startDistM = cum[cStart],
                )
            )
        }
        cStart = -1; cSign = 0; cApex = -1; cBelowSince = -1
    }

    private fun pushStop(s: Sample) {
        if (s.speed > th.moveSpeedMps) wasMoving = true
        if (s.speed < th.stopSpeedMps) {
            if (slowSince < 0) slowSince = s.t
            if (!stopped && wasMoving && (s.t - slowSince) / 1000f >= th.stopMinSec) {
                stopped = true
                stops++
                stopStartT = slowSince
                events.add(DriveEvent(EventType.STOP, s.t, samples.size - 1, s.lat, s.lon, 0f))
            }
        } else {
            if (stopped) {
                stoppedS += (s.t - stopStartT) / 1000.0
                stopped = false
                wasMoving = false
            }
            slowSince = -1
        }
    }

    private fun pushPke(s: Sample) {
        if (pkeLastT < 0) { pkeLastT = s.t; pkeLastV = s.speed.toDouble(); return }
        if (s.t - pkeLastT >= 1000) {
            val v = s.speed.toDouble()
            val dv2 = v * v - pkeLastV * pkeLastV
            if (dv2 > 0) pkeSum += dv2
            pkeLastV = v
            pkeLastT = s.t
        }
    }

    fun addMark(t: Long) {
        val idx = samples.indexOfLast { it.t <= t }.coerceAtLeast(0)
        val s = samples.getOrNull(idx) ?: return
        events.add(DriveEvent(EventType.MARK, t, idx, s.lat, s.lon, 0f))
    }

    /** Ekonomi skoru ve puan kaybı dökümü. 1 km altında skor verilmez. */
    fun ecoLoss(): EcoLoss = computeLoss(distance, eventCount(EventType.ECO_ACCEL), eventCount(EventType.ECO_BRAKE), stops, pke)

    fun ecoScore(): Int? = if (distance < 1000) null else (100 - ecoLoss().total).toInt().coerceIn(0, 100)

    fun finish(): RideStats {
        if (cStart >= 0) closeCorner(samples.size - 1)
        // açık kalan olay pencerelerini kapat
        samples.lastOrNull()?.let { last ->
            val closer = last.copy(gLon = 0f)
            detectors.forEach { it.push(closer, samples.size - 1) }
        }
        val durationS = if (samples.size > 1) (samples.last().t - samples.first().t) / 1000.0 else 0.0
        return RideStats(
            durationS = durationS,
            distanceM = distance,
            maxSpeed = maxSpeed,
            avgMovingSpeed = if (movingTimeS > 1) (distance / movingTimeS).toFloat() else 0f,
            maxLeanLeft = maxL,
            maxLeanRight = maxR,
            maxBrakeG = maxBrake,
            maxAccelG = maxAccel,
            corners = corners.toList(),
            events = events.sortedBy { it.t },
            stops = stops,
            stoppedS = stoppedSeconds,
            pke = pke,
            ecoAccelCount = eventCount(EventType.ECO_ACCEL),
            ecoBrakeCount = eventCount(EventType.ECO_BRAKE),
            ecoLoss = ecoLoss(),
            ecoScore = ecoScore(),
            cumDist = cum.toDoubleArray(),
        )
    }

    companion object {
        /**
         * Puan kaybı (100'den düşülür):
         *  ani hızlanma: 2,0 × (adet / 10 km), en çok 30
         *  ani fren:     2,5 × (adet / 10 km), en çok 30
         *  dur-kalk:     4 × max(0, duruş/km − 0,2), en çok 20
         *  hız dalgalanması (PKE): 40 × max(0, PKE − 0,25 m/s²), en çok 20
         */
        fun computeLoss(distM: Double, acc: Int, brk: Int, stops: Int, pke: Double): EcoLoss {
            if (distM < 100) return EcoLoss(0.0, 0.0, 0.0, 0.0)
            val per10 = 10_000.0 / distM
            val km = distM / 1000.0
            return EcoLoss(
                accel = min(30.0, 2.0 * acc * per10),
                brake = min(30.0, 2.5 * brk * per10),
                stops = min(20.0, 4.0 * max(0.0, stops / km - 0.2)),
                variation = min(20.0, 40.0 * max(0.0, pke - 0.25)),
            )
        }

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        fun analyze(samples: List<Sample>, marks: List<Long>, th: Thresholds): RideStats {
            val a = RideAnalyzer(th)
            samples.forEach { a.push(it) }
            marks.forEach { a.addMark(it) }
            return a.finish()
        }
    }
}
