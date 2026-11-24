package com.vagabundo.telemoto.data

/** Analiz eşikleri. Saf Kotlin: hem servis hem birim testleri kullanır. */
data class Thresholds(
    // Performans
    val perfBrakeG: Float = -0.35f,
    val perfHardBrakeG: Float = -0.60f,
    val perfAccelG: Float = 0.25f,
    val minEventSec: Float = 0.4f,
    // Ekonomi
    val ecoAccelG: Float = 0.15f,
    val ecoBrakeG: Float = -0.25f,
    // Viraj
    val cornerStartDeg: Float = 5f,
    val cornerEndDeg: Float = 3f,
    val cornerEndHoldSec: Float = 0.5f,
    val cornerMinSec: Float = 0.8f,
    // Duruş
    val stopSpeedMps: Float = 0.83f,   // 3 km/h
    val stopMinSec: Float = 2f,
    val moveSpeedMps: Float = 1.39f,   // 5 km/h
)
