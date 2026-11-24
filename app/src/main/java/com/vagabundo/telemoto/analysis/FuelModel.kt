package com.vagabundo.telemoto.analysis

/**
 * Yakıt TAHMİNİ (ölçüm değil). DCT'den yakıt verisi okunamıyor; kullanıcının depo kayıtlarından
 * çıkan ortalama tüketim, sürüşün PKE'si ve duruş oranıyla ölçeklenir:
 *   L/100 = taban × clamp(1 + 0,9·(PKE − 0,30) + 0,4·(duruş oranı − 0,15), 0,75, 1,40)
 * Katsayılar başlangıç değeri; yeterli depo kaydıyla regresyon ileride eklenecek.
 */
object FuelModel {
    fun estimate(baseLPer100: Double?, pke: Double, stoppedFraction: Double): Double? {
        baseLPer100 ?: return null
        val k = (1.0 + 0.9 * (pke - 0.30) + 0.4 * (stoppedFraction - 0.15)).coerceIn(0.75, 1.40)
        return baseLPer100 * k
    }
}
