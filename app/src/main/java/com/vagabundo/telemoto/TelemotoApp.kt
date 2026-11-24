package com.vagabundo.telemoto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.vagabundo.telemoto.data.Prefs
import com.vagabundo.telemoto.data.RideStore
import com.vagabundo.telemoto.voice.Speaker
import org.osmdroid.config.Configuration

class TelemotoApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var store: RideStore; private set
    lateinit var speaker: Speaker; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = RideStore(this)
        speaker = Speaker(this)
        Configuration.getInstance().apply {
            userAgentValue = "Telemoto/${BuildConfigHelper.version(this@TelemotoApp)}"
            osmdroidBasePath = cacheDir
            osmdroidTileCache = java.io.File(cacheDir, "tiles")
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Sürüş kaydı", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kayıt sürerken kilit ekranında kontrol"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        const val CH_RECORDING = "recording"
        lateinit var instance: TelemotoApp; private set
    }
}

object BuildConfigHelper {
    fun version(app: Application): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.1"
    }.getOrDefault("0.1")
}
