package com.vagabundo.telemoto.service

import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.sensor.CalibrationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Phase { IDLE, WARMUP, CALIBRATING, READY, RECORDING, SAVING }

/** Servisten arayüze akan canlı durum. */
data class LiveState(
    val phase: Phase = Phase.IDLE,
    val mode: RideMode = RideMode.PERF,
    val calib: CalibrationResult? = null,
    val calibMessage: String = "",
    val calibProgress: Float = 0f,
    /** Kalibrasyon ekranındaki su terazisi: telefonun ham yan/ön eğimi (derece). */
    val bubbleRollDeg: Float = 0f,
    val bubblePitchDeg: Float = 0f,
    val stationary: Boolean = false,
    val gpsFix: Boolean = false,
    val gpsAccuracyM: Float = 0f,
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val speedKmh: Float = 0f,
    val leanDeg: Float = 0f,
    val gLon: Float = 0f,
    val gLat: Float = 0f,
    val maxLeanL: Float = 0f,
    val maxLeanR: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val corners: Int = 0,
    val maxBrakeG: Float = 0f,
    // Ekonomi
    val ecoScore: Int? = null,
    val stops: Int = 0,
    val stoppedS: Double = 0.0,
    val ecoAccel: Int = 0,
    val ecoBrake: Int = 0,
    val pke: Double = 0.0,
    val fuelLPer100: Double? = null,
    val marks: Int = 0,
    val stopArmed: Boolean = false,
    val lastSavedId: String? = null,
)

object RideSession {
    val state = MutableStateFlow(LiveState())
    val flow: StateFlow<LiveState> = state
    fun update(f: (LiveState) -> LiveState) { state.value = f(state.value) }
}
