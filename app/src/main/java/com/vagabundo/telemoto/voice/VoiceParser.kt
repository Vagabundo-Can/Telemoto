package com.vagabundo.telemoto.voice

import java.util.Locale

/** Basit Türkçe anahtar kelime eşleştirme (saf Kotlin, testli). */
object VoiceParser {
    enum class Cmd { CALIBRATE, START, MARK, STOP }

    private val TR = Locale("tr", "TR")

    fun parse(raw: String): Cmd? {
        val s = raw.lowercase(TR)
        return when {
            "kalibre" in s -> Cmd.CALIBRATE
            ("durdur" in s || "bitir" in s) && ("kayd" in s || "kayıt" in s || "sürüş" in s) -> Cmd.STOP
            "işaret" in s || "olay" in s -> Cmd.MARK
            "başlat" in s || "başla" in s -> Cmd.START
            else -> null
        }
    }
}
