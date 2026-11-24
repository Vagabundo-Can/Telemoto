package com.vagabundo.telemoto.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vagabundo.telemoto.MainActivity
import com.vagabundo.telemoto.R
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.analysis.EventType
import com.vagabundo.telemoto.analysis.FuelModel
import com.vagabundo.telemoto.analysis.RideAnalyzer
import com.vagabundo.telemoto.data.MarkEvent
import com.vagabundo.telemoto.data.RideHeader
import com.vagabundo.telemoto.data.RideLogFormat
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.data.Sample
import com.vagabundo.telemoto.sensor.Calibrator
import com.vagabundo.telemoto.sensor.LeanFusion
import com.vagabundo.telemoto.sensor.V3
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kayıt servisi: ekran kapalıyken de çalışır (konum tipinde foreground service + kısmi wake lock).
 * Sensörler 100 Hz, GPS 1 Hz; füzyon çıktısı 25 Hz olarak .ridelog'a yazılır.
 */
class RecordingService : Service(), SensorEventListener, LocationListener {

    companion object {
        const val ACTION_PREPARE = "tm.PREPARE"
        const val ACTION_CALIBRATE = "tm.CALIBRATE"
        const val ACTION_CALIBRATE_AND_START = "tm.CALIBRATE_START"
        const val ACTION_START = "tm.START"
        const val ACTION_MARK = "tm.MARK"
        const val ACTION_STOP_REQUEST = "tm.STOP_REQ"
        const val ACTION_STOP = "tm.STOP"
        const val ACTION_CANCEL = "tm.CANCEL"
        const val EXTRA_MODE = "mode"
        private const val NOTIF_ID = 42
        private const val TICK_MS = 40L

        fun send(ctx: Context, action: String, mode: RideMode? = null) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action)
            mode?.let { i.putExtra(EXTRA_MODE, it.code) }
            ContextCompat.startForegroundService(ctx, i)
        }

        val isActive get() = RideSession.flow.value.phase != Phase.IDLE
    }

    private val app get() = application as TelemotoApp
    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorsOn = false

    // Ham tampon (kalibrasyon + su terazisi)
    private val accBuf = ArrayDeque<V3>()
    private val gyroBuf = ArrayDeque<V3>()
    private var lastAcc = V3(0.0, 0.0, 9.81)
    private var calibCollecting = false
    private var calibStartMs = 0L
    private var autoStartAfterCalib = false

    private var fusion: LeanFusion? = null
    private var analyzer: RideAnalyzer? = null
    private var writer: BufferedWriter? = null
    private var header: RideHeader? = null
    private var recStartElapsed = 0L
    private var mode = RideMode.PERF

    // GPS
    private var fixLat = 0.0
    private var fixLon = 0.0
    private var fixAlt = 0.0
    private var fixBearing = 0f
    private var fixElapsed = 0L
    private var fixAcc = 0f
    private var haveFix = false
    private var freshFix = false
    private var drLat = 0.0
    private var drLon = 0.0

    private var lastUiMs = 0L
    private var lastNotifMs = 0L
    private var lastFlushMs = 0L
    private var stopArmedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("telemoto-sensors").apply { start() }
        h = Handler(thread.looper)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val action = intent?.action ?: ACTION_PREPARE
        intent?.getStringExtra(EXTRA_MODE)?.let { mode = RideMode.of(it) }
        h.post { handle(action) }
        return START_STICKY
    }

    private fun handle(action: String) {
        val phase = RideSession.flow.value.phase
        when (action) {
            ACTION_PREPARE -> prepare()
            ACTION_CALIBRATE -> { prepare(); beginCalibration(false) }
            ACTION_CALIBRATE_AND_START -> {
                prepare()
                if (phase == Phase.RECORDING) return
                if (phase == Phase.READY) startRecording() else beginCalibration(true)
            }
            ACTION_START -> {
                if (phase == Phase.READY) startRecording()
                else if (phase != Phase.RECORDING) { prepare(); beginCalibration(true) }
            }
            ACTION_MARK -> mark()
            ACTION_STOP_REQUEST -> {
                if (phase != Phase.RECORDING) { cancelAll(); return }
                val now = SystemClock.elapsedRealtime()
                if (now - stopArmedAt < 5000) stopRecording()
                else {
                    stopArmedAt = now
                    RideSession.update { it.copy(stopArmed = true) }
                    app.speaker.say("Durdurmak için tekrar bas")
                    updateNotification(force = true)
                    h.postDelayed({ RideSession.update { it.copy(stopArmed = false) }; updateNotification(true) }, 5000)
                }
            }
            ACTION_STOP -> if (phase == Phase.RECORDING) stopRecording() else cancelAll()
            ACTION_CANCEL -> if (phase != Phase.RECORDING) cancelAll()
        }
    }

    // ---------- Ön plan ----------

    private fun goForeground() {
        val n = buildNotification()
        val type = if (hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun prepare() {
        if (sensorsOn) return
        app.speaker.enabled = app.prefs.value.voiceFeedback
        mode = if (RideSession.flow.value.phase == Phase.IDLE) mode else RideSession.flow.value.mode
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telemoto:ride").apply { acquire(8 * 60 * 60 * 1000L) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 10_000, h) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, 10_000, h) }
        if (hasLocation()) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, thread.looper)
        }
        sensorsOn = true
        RideSession.update { it.copy(phase = Phase.WARMUP, mode = mode, calibMessage = "Motoru dik tut ve kalibre et", lastSavedId = null) }
        h.post(tick)
    }

    private fun releaseSensors() {
        if (!sensorsOn) return
        sm.unregisterListener(this)
        runCatching { lm.removeUpdates(this) }
        h.removeCallbacks(tick)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        sensorsOn = false
    }

    // ---------- Kalibrasyon ----------

    private fun beginCalibration(autoStart: Boolean) {
        autoStartAfterCalib = autoStart
        accBuf.clear(); gyroBuf.clear()
        calibCollecting = true
        calibStartMs = SystemClock.elapsedRealtime()
        RideSession.update { it.copy(phase = Phase.CALIBRATING, calibProgress = 0f, calibMessage = "Hareketsiz bekle…") }
    }

    private fun finishCalibration() {
        calibCollecting = false
        val r = Calibrator.compute(accBuf.toList(), gyroBuf.toList())
        if (r.ok) {
            RideSession.update { it.copy(phase = Phase.READY, calib = r, calibMessage = r.message, calibProgress = 1f) }
            if (autoStartAfterCalib) startRecording() else app.speaker.say("Kalibre edildi")
        } else {
            RideSession.update { it.copy(phase = Phase.WARMUP, calib = null, calibMessage = r.message, calibProgress = 0f) }
            app.speaker.say("Kalibrasyon başarısız. ${r.message}")
        }
        updateNotification(true)
    }

    // ---------- Kayıt ----------

    private fun startRecording() {
        val c = RideSession.flow.value.calib ?: return
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val name = "Sürüş " + SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR")).format(Date(now))
        val hdr = RideHeader(
            id = id, name = name, mode = mode, startEpochMs = now,
            calib = "rot=${c.rotation.encode()}|roll=${"%.2f".format(Locale.US, c.rollOffsetDeg)}|pitch=${"%.2f".format(Locale.US, c.pitchDeg)}",
            appVersion = com.vagabundo.telemoto.BuildConfigHelper.version(application),
        )
        val w = app.store.logFile(id).bufferedWriter(Charsets.UTF_8)
        RideLogFormat.writeHeader(w, hdr)
        header = hdr
        writer = w
        fusion = LeanFusion(c.rotation, c.gyroBias).also { f ->
            if (haveFix) f.onGpsSpeed(SystemClock.elapsedRealtime(), 0.0)
        }
        analyzer = RideAnalyzer(app.prefs.value.th)
        recStartElapsed = SystemClock.elapsedRealtime()
        drLat = fixLat; drLon = fixLon
        app.prefs.update { it.copy(lastMode = mode) }
        RideSession.update {
            LiveState(phase = Phase.RECORDING, mode = mode, calib = c, calibMessage = c.message, gpsFix = haveFix, gpsAccuracyM = fixAcc)
        }
        app.speaker.say(if (mode == RideMode.ECO) "Ekonomi kaydı başladı" else "Performans kaydı başladı")
        updateNotification(true)
    }

    private fun mark() {
        val w = writer ?: return
        val a = analyzer ?: return
        val t = SystemClock.elapsedRealtime() - recStartElapsed
        RideLogFormat.writeEvent(w, MarkEvent(t, "MARK"))
        a.addMark(t)
        RideSession.update { it.copy(marks = it.marks + 1) }
        app.speaker.say("İşaretlendi")
    }

    private fun stopRecording() {
        RideSession.update { it.copy(phase = Phase.SAVING) }
        val w = writer
        val a = analyzer
        val hdr = header
        writer = null; analyzer = null; fusion = null
        releaseSensors()
        runCatching { w?.flush(); w?.close() }
        var savedId: String? = null
        if (a != null && hdr != null) {
            val st = a.finish()
            if (a.count > 25 * 10) {
                app.store.saveSummary(hdr, st)
                savedId = hdr.id
                val km = st.distanceM / 1000.0
                app.speaker.say("Kayıt durdu. ${"%.1f".format(Locale("tr", "TR"), km)} kilometre")
            } else {
                app.store.delete(hdr.id)
                app.speaker.say("Kayıt çok kısa, silindi")
            }
        }
        RideSession.state.value = LiveState(phase = Phase.IDLE, lastSavedId = savedId)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelAll() {
        releaseSensors()
        RideSession.state.value = LiveState(phase = Phase.IDLE)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Döngü ----------

    private val tick = object : Runnable {
        override fun run() {
            val nowE = SystemClock.elapsedRealtime()
            val st = RideSession.flow.value
            when (st.phase) {
                Phase.RECORDING -> recordTick(nowE)
                Phase.WARMUP, Phase.CALIBRATING, Phase.READY -> warmupTick(nowE)
                else -> {}
            }
            if (sensorsOn) h.postDelayed(this, TICK_MS)
        }
    }

    private fun warmupTick(nowE: Long) {
        if (calibCollecting) {
            val p = ((nowE - calibStartMs) / 2000f).coerceIn(0f, 1f)
            if (p >= 1f) finishCalibration() else if (nowE - lastUiMs > 100) RideSession.update { it.copy(calibProgress = p) }
        }
        if (nowE - lastUiMs > 100) {
            lastUiMs = nowE
            val a = lastAcc
            val n = a.norm().coerceAtLeast(0.1)
            val gyroMax = gyroBuf.takeLast(50).maxOfOrNull { it.norm() } ?: 1.0
            RideSession.update {
                it.copy(
                    bubbleRollDeg = Math.toDegrees(asin((a.y / n).coerceIn(-1.0, 1.0))).toFloat(),
                    bubblePitchDeg = Math.toDegrees(asin((a.z / n).coerceIn(-1.0, 1.0))).toFloat(),
                    stationary = gyroMax < Calibrator.MAX_GYRO_RAD_S * 2,
                    gpsFix = haveFix && nowE - fixElapsed < 3000,
                    gpsAccuracyM = fixAcc,
                )
            }
        }
        if (nowE - lastNotifMs > 2000) updateNotification()
    }

    private fun recordTick(nowE: Long) {
        val f = fusion ?: return
        val a = analyzer ?: return
        val w = writer ?: return
        val t = nowE - recStartElapsed

        // Konum: son fix'ten ölü hesap
        val v = f.speed
        if (freshFix) {
            drLat = fixLat; drLon = fixLon
        } else if (haveFix && v > 0.3) {
            val d = v * TICK_MS / 1000.0
            val b = Math.toRadians(fixBearing.toDouble())
            drLat += d * cos(b) / 111_320.0
            drLon += d * sin(b) / (111_320.0 * cos(Math.toRadians(drLat)).coerceAtLeast(0.1))
        }
        val s = Sample(
            t = t, lat = drLat, lon = drLon, alt = fixAlt.toFloat(), speed = v.toFloat(),
            lean = f.leanDeg.toFloat(), gLon = f.gLon.toFloat(), gLat = f.gLat.toFloat(),
            yaw = f.yawDps.toFloat(), gpsFresh = freshFix,
        )
        freshFix = false
        RideLogFormat.writeSample(w, s)
        a.push(s)

        if (nowE - lastFlushMs > 5000) { lastFlushMs = nowE; runCatching { w.flush() } }
        if (nowE - lastUiMs > 200) {
            lastUiMs = nowE
            val dist = a.distanceM
            val stoppedS = a.stoppedSeconds
            val fuel = FuelModel.estimate(app.prefs.baseConsumption(), a.pke, if (t > 0) stoppedS / (t / 1000.0) else 0.0)
            RideSession.update {
                it.copy(
                    elapsedMs = t, distanceM = dist, speedKmh = (v * 3.6).toFloat(),
                    leanDeg = s.lean, gLon = s.gLon, gLat = s.gLat,
                    maxLeanL = a.maxLeanLeft, maxLeanR = a.maxLeanRight,
                    maxSpeedKmh = a.maxSpeedMps * 3.6f,
                    avgSpeedKmh = if (t > 1000) (dist / (t / 1000.0) * 3.6).toFloat() else 0f,
                    corners = a.cornerCount, maxBrakeG = a.maxBrakeG,
                    ecoScore = a.ecoScore(), stops = a.stopCount, stoppedS = stoppedS,
                    ecoAccel = a.eventCount(EventType.ECO_ACCEL), ecoBrake = a.eventCount(EventType.ECO_BRAKE),
                    pke = a.pke, fuelLPer100 = fuel,
                    gpsFix = haveFix && nowE - fixElapsed < 3000, gpsAccuracyM = fixAcc,
                )
            }
        }
        if (nowE - lastNotifMs > 1000) updateNotification()
    }

    // ---------- Sensörler ----------

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAcc = V3(x, y, z)
                if (fusion == null) { accBuf.addLast(lastAcc); while (accBuf.size > 300) accBuf.removeFirst() }
                fusion?.onAccel(e.timestamp, x, y, z)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (fusion == null) { gyroBuf.addLast(V3(x, y, z)); while (gyroBuf.size > 300) gyroBuf.removeFirst() }
                fusion?.onGyro(e.timestamp, x, y, z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        val nowE = SystemClock.elapsedRealtime()
        fixLat = l.latitude; fixLon = l.longitude
        if (l.hasAltitude()) fixAlt = l.altitude
        if (l.hasBearing() && l.speed > 1.5f) fixBearing = l.bearing
        fixAcc = l.accuracy
        fixElapsed = nowE
        if (!haveFix) { drLat = fixLat; drLon = fixLon }
        haveFix = true
        freshFix = true
        if (l.hasSpeed()) fusion?.onGpsSpeed(nowE, l.speed.toDouble())
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------- Bildirim ----------

    private fun pi(action: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(): Notification {
        val s = RideSession.flow.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, TelemotoApp.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_telemoto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
        if (s.phase == Phase.RECORDING) {
            val sec = s.elapsedMs / 1000
            val time = "%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)
            val detail = if (s.mode == RideMode.ECO)
                "${"%.1f".format(s.distanceM / 1000)} km · Akıcılık ${s.ecoScore ?: "—"} · Dur-kalk ${s.stops}"
            else
                "${"%.1f".format(s.distanceM / 1000)} km · ${s.speedKmh.toInt()} km/h · Maks L ${s.maxLeanL.toInt()}° S ${s.maxLeanR.toInt()}°"
            b.setContentTitle("Kayıt sürüyor · $time").setContentText(detail)
                .addAction(0, "Olay işaretle", pi(ACTION_MARK, 1))
                .addAction(0, if (s.stopArmed) "Emin misin? DURDUR" else "Durdur", pi(ACTION_STOP_REQUEST, 2))
        } else {
            b.setContentTitle("Telemoto hazır").setContentText(s.calibMessage.ifEmpty { "Kalibre et ve başlat" })
                .addAction(0, "Kalibre et ve başlat", pi(ACTION_CALIBRATE_AND_START, 3))
                .addAction(0, "Kapat", pi(ACTION_CANCEL, 4))
        }
        return b.build()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifMs < 900) return
        lastNotifMs = now
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    override fun onDestroy() {
        releaseSensors()
        runCatching { writer?.flush(); writer?.close() }
        thread.quitSafely()
        super.onDestroy()
    }

}
