package com.vagabundo.telemoto.wear

/**
 * Telefon ↔ Galaxy Watch mesaj protokolü (Wearable Data Layer, MessageClient).
 * Saat modülünde aynı dosyanın kopyası var — değiştirirken ikisini birlikte güncelle.
 */
object WearProtocol {
    const val PATH_CMD = "/tm/cmd"      // saat → telefon
    const val PATH_HR = "/tm/hr"        // saat → telefon, nabız (bpm)
    const val PATH_STATE = "/tm/state"  // telefon → saat, ~1 Hz
    const val PATH_EVT = "/tm/evt"      // telefon → saat, titreşim olayları

    // Komutlar
    const val CMD_START = "START"
    const val CMD_START_PERF = "START_PERF"
    const val CMD_START_ECO = "START_ECO"
    const val CMD_MARK = "MARK"
    const val CMD_STOP = "STOP"
    const val CMD_PING = "PING"

    // Olaylar
    const val EVT_CALIBRATED = "CALIBRATED"
    const val EVT_CAL_FAIL = "CAL_FAIL"
    const val EVT_STARTED = "STARTED"
    const val EVT_MARKED = "MARKED"
    const val EVT_STOP_ARMED = "STOP_ARMED"
    const val EVT_STOPPED = "STOPPED"
    const val EVT_ECO_HARSH = "ECO_HARSH"
    const val EVT_GPS_LOST = "GPS_LOST"
    const val EVT_NEED_APP = "NEED_APP"

    data class State(
        val phase: String = "IDLE",
        val mode: String = "PERF",
        val elapsedMs: Long = 0,
        val distanceM: Double = 0.0,
        val speedKmh: Int = 0,
        val lean: Int = 0,
        val ecoScore: Int = -1,
        val maxL: Int = 0,
        val maxR: Int = 0,
        val hr: Int = 0,
        val stopArmed: Boolean = false,
        val message: String = "",
    )

    fun encode(s: State): String = listOf(
        s.phase, s.mode, s.elapsedMs, s.distanceM.toLong(), s.speedKmh, s.lean, s.ecoScore,
        s.maxL, s.maxR, s.hr, if (s.stopArmed) 1 else 0, s.message.replace('|', '/'),
    ).joinToString("|")

    fun decode(raw: String): State? {
        val p = raw.split('|')
        if (p.size < 12) return null
        return runCatching {
            State(
                phase = p[0], mode = p[1], elapsedMs = p[2].toLong(), distanceM = p[3].toDouble(),
                speedKmh = p[4].toInt(), lean = p[5].toInt(), ecoScore = p[6].toInt(),
                maxL = p[7].toInt(), maxR = p[8].toInt(), hr = p[9].toInt(), stopArmed = p[10] == "1",
                message = p.subList(11, p.size).joinToString("|"),
            )
        }.getOrNull()
    }

    /** STOPPED olayının yükü: "STOPPED|mod|km|ana değer|ikincil" */
    fun stoppedPayload(mode: String, km: Double, main: String, second: String) =
        listOf(EVT_STOPPED, mode, String.format(java.util.Locale.US, "%.1f", km), main, second).joinToString("|")
}
