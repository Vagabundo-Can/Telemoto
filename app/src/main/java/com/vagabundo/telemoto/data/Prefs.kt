package com.vagabundo.telemoto.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, AUTO }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.AUTO,
    val th: Thresholds = Thresholds(),
    val voiceFeedback: Boolean = true,
    val voiceCommands: Boolean = true,
    val ecoCoachVibrate: Boolean = false,
    val lastMode: RideMode = RideMode.PERF,
)

data class FuelFill(val epochMs: Long, val liters: Double, val km: Double)

/** SharedPreferences sarmalayıcı. Ayarlar StateFlow olarak yayınlanır. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("telemoto", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow
    val value get() = _flow.value

    private fun load(): AppSettings {
        val d = Thresholds()
        return AppSettings(
            theme = runCatching { ThemeMode.valueOf(sp.getString("theme", "AUTO")!!) }.getOrDefault(ThemeMode.AUTO),
            th = Thresholds(
                perfBrakeG = sp.getFloat("perfBrakeG", d.perfBrakeG),
                perfHardBrakeG = sp.getFloat("perfHardBrakeG", d.perfHardBrakeG),
                perfAccelG = sp.getFloat("perfAccelG", d.perfAccelG),
                minEventSec = sp.getFloat("minEventSec", d.minEventSec),
                ecoAccelG = sp.getFloat("ecoAccelG", d.ecoAccelG),
                ecoBrakeG = sp.getFloat("ecoBrakeG", d.ecoBrakeG),
                cornerStartDeg = sp.getFloat("cornerStartDeg", d.cornerStartDeg),
            ),
            voiceFeedback = sp.getBoolean("voiceFeedback", true),
            voiceCommands = sp.getBoolean("voiceCommands", true),
            ecoCoachVibrate = sp.getBoolean("ecoCoachVibrate", false),
            lastMode = RideMode.of(sp.getString("lastMode", "PERF")),
        )
    }

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_flow.value)
        sp.edit()
            .putString("theme", n.theme.name)
            .putFloat("perfBrakeG", n.th.perfBrakeG)
            .putFloat("perfHardBrakeG", n.th.perfHardBrakeG)
            .putFloat("perfAccelG", n.th.perfAccelG)
            .putFloat("minEventSec", n.th.minEventSec)
            .putFloat("ecoAccelG", n.th.ecoAccelG)
            .putFloat("ecoBrakeG", n.th.ecoBrakeG)
            .putFloat("cornerStartDeg", n.th.cornerStartDeg)
            .putBoolean("voiceFeedback", n.voiceFeedback)
            .putBoolean("voiceCommands", n.voiceCommands)
            .putBoolean("ecoCoachVibrate", n.ecoCoachVibrate)
            .putString("lastMode", n.lastMode.code)
            .apply()
        _flow.value = n
    }

    // Yakıt dolumları: "epoch;litre;km|…"
    fun fuelFills(): List<FuelFill> =
        (sp.getString("fuel", "") ?: "").split('|').mapNotNull { e ->
            val p = e.split(';')
            if (p.size == 3) runCatching { FuelFill(p[0].toLong(), p[1].toDouble(), p[2].toDouble()) }.getOrNull() else null
        }

    fun addFuel(f: FuelFill) {
        val all = (fuelFills() + f).takeLast(20)
        sp.edit().putString("fuel", all.joinToString("|") { "${it.epochMs};${it.liters};${it.km}" }).apply()
    }

    /** Son dolumların ortalaması (L/100 km), yoksa null. */
    fun baseConsumption(): Double? {
        val f = fuelFills().takeLast(3)
        val km = f.sumOf { it.km }
        return if (km < 50) null else f.sumOf { it.liters } / km * 100.0
    }
}
