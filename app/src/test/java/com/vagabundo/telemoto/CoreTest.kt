package com.vagabundo.telemoto

import com.vagabundo.telemoto.analysis.EventType
import com.vagabundo.telemoto.analysis.RideAnalyzer
import com.vagabundo.telemoto.data.RideHeader
import com.vagabundo.telemoto.data.RideLogFormat
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.Sample
import com.vagabundo.telemoto.data.Thresholds
import com.vagabundo.telemoto.sensor.Calibrator
import com.vagabundo.telemoto.sensor.LeanFusion
import com.vagabundo.telemoto.sensor.MountRotation
import com.vagabundo.telemoto.sensor.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.StringReader
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class CoreTest {

    private val g = 9.80665

    @Test
    fun calibration_landscapePortLeft_tiltedBack15() {
        val t = Math.toRadians(15.0)
        val up = V3(-cos(t), 0.0, sin(t))           // motorun "yukarı"sı telefon koordinatlarında
        val accel = List(200) { up * g + V3(0.02 * sin(it * 1.3), 0.03 * cos(it * 0.7), 0.0) }
        val gyro = List(200) { V3(0.001, -0.002, 0.0015) }
        val r = Calibrator.compute(accel, gyro)
        assertTrue(r.message, r.ok)
        // ileri ekseni telefon koordinatlarında (−sin15, 0, −cos15)
        assertEquals(-sin(t), r.rotation.bx.x, 0.02)
        assertEquals(-cos(t), r.rotation.bx.z, 0.02)
        assertEquals(15.0, r.pitchDeg, 1.0)
        assertEquals(0.0, r.rollOffsetDeg, 1.0)
    }

    @Test
    fun calibration_portRight_flipsSides() {
        val t = Math.toRadians(10.0)
        val up = V3(cos(t), 0.0, sin(t))             // şarj girişi sağda: +x yukarı
        val r = Calibrator.compute(List(200) { up * g }, List(200) { V3.ZERO })
        assertTrue(r.message, r.ok)
        // motorun sol ekseni telefonun +y'si olmalı
        assertEquals(1.0, r.rotation.by.y, 0.02)
    }

    @Test
    fun voice_parse() {
        assertEquals(com.vagabundo.telemoto.voice.VoiceParser.Cmd.CALIBRATE, com.vagabundo.telemoto.voice.VoiceParser.parse("Kalibre et"))
        assertEquals(com.vagabundo.telemoto.voice.VoiceParser.Cmd.STOP, com.vagabundo.telemoto.voice.VoiceParser.parse("kaydı durdur"))
        assertEquals(com.vagabundo.telemoto.voice.VoiceParser.Cmd.MARK, com.vagabundo.telemoto.voice.VoiceParser.parse("olay işaretle"))
        assertEquals(com.vagabundo.telemoto.voice.VoiceParser.Cmd.START, com.vagabundo.telemoto.voice.VoiceParser.parse("KAYDI BAŞLAT"))
        assertEquals(null, com.vagabundo.telemoto.voice.VoiceParser.parse("yarın hava nasıl"))
        assertEquals(null, com.vagabundo.telemoto.voice.VoiceParser.parse("durdur"))
    }

    @Test
    fun calibration_rejectsPortraitPhone() {
        val accel = List(200) { V3(0.0, g, 0.0) }     // dikey: +y yukarı
        val gyro = List(200) { V3.ZERO }
        assertTrue(!Calibrator.compute(accel, gyro).ok)
    }

    /** Koordineli virajda ivme ölçer ~0 gösterirken füzyon gerçek yatışa yakınsamalı. */
    private fun steadyTurn(leanDeg: Double): Double {
        val phi = Math.toRadians(leanDeg)
        val v = 20.0
        val r = -g * tan(phi) / v                     // + sola dönüş konvansiyonu
        val f = LeanFusion(MountRotation.IDENTITY, V3.ZERO)
        var tNs = 0L
        val dtNs = 10_000_000L                        // 100 Hz
        f.onGpsSpeed(0, v)
        for (i in 0 until 800) {
            tNs += dtNs
            f.onGyro(tNs, 0.0, r * sin(phi), r * cos(phi))
            f.onAccel(tNs, 0.0, 0.0, g / cos(phi))
            if (i % 100 == 0) f.onGpsSpeed(tNs / 1_000_000, v)
        }
        return f.leanDeg
    }

    @Test
    fun fusion_rightTurn30() {
        val lean = steadyTurn(30.0)
        assertEquals(30.0, lean, 1.5)
    }

    @Test
    fun fusion_leftTurn42() {
        val lean = steadyTurn(-42.0)
        assertEquals(-42.0, lean, 2.0)
    }

    private fun synthRide(): List<Sample> {
        val out = ArrayList<Sample>()
        var t = 0L
        var lat = 39.97
        val lon = 32.63
        var v = 15.0
        fun add(lean: Double, gLon: Double) {
            out.add(Sample(t, lat, lon, 900f, v.toFloat(), lean.toFloat(), gLon.toFloat(), 0f, 0f, t % 1000 == 0L))
            lat += v * 0.04 / 111_320.0
            v = (v + gLon * g * 0.04).coerceAtLeast(0.0)
            t += 40
        }
        repeat(250) { add(0.0, 0.0) }                                   // 10 s düz
        repeat(100) { i -> add(35.0 * sin(Math.PI * i / 100.0), 0.0) }  // 4 s sağ viraj
        repeat(125) { add(0.0, 0.0) }                                   // 5 s düz
        repeat(25) { add(0.0, -0.5) }                                   // 1 s fren
        while (v > 0.05) add(0.0, -0.3)                                  // durana kadar
        v = 0.0
        repeat(125) { add(0.0, 0.0) }                                   // 5 s bekle
        repeat(100) { add(0.0, 0.2) }                                   // 4 s kalkış
        repeat(250) { add(0.0, 0.0) }
        return out
    }

    @Test
    fun analyzer_cornersEventsStops() {
        val stats = RideAnalyzer.analyze(synthRide(), emptyList(), Thresholds())
        assertEquals(1, stats.cornerCount)
        val c = stats.corners.first()
        assertTrue(c.right)
        assertEquals(35f, c.maxLean, 1f)
        assertTrue(stats.events.any { it.type == EventType.BRAKE })
        assertEquals(1, stats.stops)
        assertTrue("eko hızlanma ${stats.ecoAccelCount}", stats.ecoAccelCount >= 1)
        assertTrue(stats.distanceM > 300)
        assertEquals(35f, stats.maxLeanRight, 1f)
    }

    @Test
    fun ecoLoss_formula() {
        val l = RideAnalyzer.computeLoss(18_600.0, 3, 1, 14, 0.31)
        assertEquals(3.2, l.accel, 0.1)
        assertEquals(1.34, l.brake, 0.05)
        assertEquals(2.21, l.stops, 0.05)
        assertEquals(2.4, l.variation, 0.05)
    }

    @Test
    fun ridelog_roundTrip() {
        val sw = StringWriter()
        val w = BufferedWriter(sw)
        val h = RideHeader("20261124-081100", "Ev → İş", RideMode.ECO, 1_700_000_000_000L, "x", "0.1")
        RideLogFormat.writeHeader(w, h)
        synthRide().take(50).forEach { RideLogFormat.writeSample(w, it) }
        RideLogFormat.writeEvent(w, com.vagabundo.telemoto.data.MarkEvent(400, "MARK"))
        w.flush()
        val p = RideLogFormat.read(BufferedReader(StringReader(sw.toString())))
        assertEquals("Ev → İş", p.header.name)
        assertEquals(RideMode.ECO, p.header.mode)
        assertEquals(50, p.samples.size)
        assertEquals(1, p.events.size)
        assertNotNull(p.samples.firstOrNull())
        assertTrue(abs(p.samples[10].lat - synthRide()[10].lat) < 1e-6)
    }
}
