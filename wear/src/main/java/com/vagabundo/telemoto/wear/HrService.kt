package com.vagabundo.telemoto.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Kayıt sürerken nabzı ~2 sn'de bir telefona yollar. Saat uygulaması kayıt sırasında açıldığında başlar
 * (Android arka plandan ön plan servisi başlatmaya izin vermiyor), telefon kaydı bitirince kendini kapatır.
 */
class HrService : Service(), SensorEventListener {
    companion object {
        private const val CH = "hr"
        private const val ID = 7

        fun startIfAllowed(ctx: Context) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, HrService::class.java)) }
        }
    }

    private lateinit var sm: SensorManager
    private val h = Handler(Looper.getMainLooper())
    private var lastSent = 0L
    private var lastBpm = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Nabız kaydı", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Telemoto")
            .setContentText("Sürüş nabzı kaydediliyor")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        ServiceCompat.startForeground(this, ID, n, type)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        h.postDelayed(watchdog, 15_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Telefon kaydı bitirdiyse ya da bağlantı koptuysa kapan. */
    private val watchdog = object : Runnable {
        override fun run() {
            val ui = WatchState.flow.value
            val stale = SystemClock.elapsedRealtime() - ui.stateAt > 60_000
            if (stale || (ui.connected && ui.state.phase == "IDLE")) { stopSelf(); return }
            h.postDelayed(this, 15_000)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val bpm = e.values.firstOrNull()?.toInt() ?: return
        if (bpm < 30 || e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT || e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        lastBpm = bpm
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent >= 2000) { lastSent = now; PhoneLink.hr(this, bpm) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sm.unregisterListener(this)
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
