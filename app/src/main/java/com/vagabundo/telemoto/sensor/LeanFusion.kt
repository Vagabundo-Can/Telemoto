package com.vagabundo.telemoto.sensor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Yatış / ivme füzyonu (saf Kotlin, birim testli).
 *
 * Neden ivme ölçerle "su terazisi" olmaz: koordineli virajda ivme ölçer yerçekimi + merkezcil
 * bileşkesini ölçer, o da motor düzlemine paraleldir → 40° yatışta bile ~0° gösterir.
 *
 * Yöntem (tamamlayıcı filtre):
 *  - φ, gyro roll hızının (motor X ekseni) entegrasyonu ile yürür.
 *  - v > 4 m/s: referans φ_ref = atan(−v·r / g), r = dünya düşeyi etrafındaki yaw hızı.
 *    φ ← φ + (φ_ref − φ)·dt/τ  (τ = 0,8 s). Gyro kısa vadeyi, kinematik referans uzun vadeyi taşır.
 *  - v < 1,5 m/s: yarı-statik → ivme ölçer eğimi φ_acc = atan2(a_y, a_z) referans alınır.
 *  - Arada: kinematik referans, τ = 2 s.
 * Boyuna ivme: motor X ekseni ivmesi (2 Hz alçak geçiren), yol eğimi/dalış hatası GPS dv/dt ile
 * τ = 5 s'lik bias düzeltmesiyle temizlenir. Yanal ivme kinematik: v·r/g.
 */
class LeanFusion(
    private val rot: MountRotation,
    private val gyroBias: V3,
) {
    companion object {
        const val G = 9.80665
        private const val TAU_FAST = 0.8
        private const val TAU_SLOW = 2.0
        private const val TAU_STATIC = 0.5
        private const val TAU_BIAS = 5.0
        private const val LON_CUTOFF_HZ = 2.0
        private const val MAX_LEAN_RAD = 65.0 * PI / 180.0
    }

    /** Yatış açısı (rad), + sağ. */
    var phi = 0.0; private set
    /** Dünya düşeyi etrafında yaw hızı (rad/s), + sola dönüş (ters saat yönü). */
    var yawRate = 0.0; private set
    /** Boyuna ivme (g), bias düzeltilmiş. */
    var gLon = 0.0; private set
    /** Yanal ivme (g), + sağa dönüş. */
    var gLat = 0.0; private set

    /** Hız tahmini (m/s): GPS fix'lerinde güncellenir, aralarda ivmeyle ilerler. */
    var speed = 0.0; private set

    private var lastGyroNs = 0L
    private var lastAccNs = 0L
    private var accY = 0.0
    private var accZ = G
    private var lonRawLpf = 0.0
    private var lonBias = 0.0
    private var lonSumSinceFix = 0.0
    private var lonCountSinceFix = 0
    private var lastFixMs = 0L
    private var lastFixSpeed = -1.0
    private var yawLpf = 0.0

    fun onGyro(tNs: Long, x: Double, y: Double, z: Double) {
        val w = rot.apply(V3(x, y, z) - gyroBias)
        if (lastGyroNs == 0L) { lastGyroNs = tNs; return }
        val dt = ((tNs - lastGyroNs) / 1e9).coerceIn(0.0, 0.1)
        lastGyroNs = tNs
        if (dt <= 0.0) return

        val p = w.x
        val r = w.y * sin(phi) + w.z * cos(phi)
        yawLpf += (r - yawLpf) * (dt / (0.15 + dt))
        yawRate = yawLpf

        phi += p * dt

        val v = speed
        val (ref, tau) = when {
            v > 4.0 -> atan(-v * yawRate / G) to TAU_FAST
            v > 1.5 -> atan(-v * yawRate / G) to TAU_SLOW
            else -> atan2(accY, accZ) to TAU_STATIC
        }
        phi += (ref.coerceIn(-MAX_LEAN_RAD, MAX_LEAN_RAD) - phi) * (dt / tau).coerceAtMost(1.0)
        phi = phi.coerceIn(-MAX_LEAN_RAD, MAX_LEAN_RAD)

        gLat = (-v * yawRate / G).coerceIn(-2.0, 2.0)
        if (v > 0.5) speed = max(0.0, speed + gLon * G * dt)
    }

    fun onAccel(tNs: Long, x: Double, y: Double, z: Double) {
        val a = rot.apply(V3(x, y, z))
        if (lastAccNs == 0L) { lastAccNs = tNs; lonRawLpf = a.x / G; return }
        val dt = ((tNs - lastAccNs) / 1e9).coerceIn(0.0, 0.1)
        lastAccNs = tNs
        if (dt <= 0.0) return
        val kStatic = dt / (0.3 + dt)
        accY += (a.y - accY) * kStatic
        accZ += (a.z - accZ) * kStatic
        val rc = 1.0 / (2 * PI * LON_CUTOFF_HZ)
        lonRawLpf += (a.x / G - lonRawLpf) * (dt / (rc + dt))
        gLon = lonRawLpf - lonBias
        lonSumSinceFix += lonRawLpf
        lonCountSinceFix++
    }

    /** GPS hız fix'i (m/s). Bias düzeltmesi ve hız ankrajı. */
    fun onGpsSpeed(tMs: Long, speedMps: Double) {
        if (lastFixSpeed >= 0 && lastFixMs > 0) {
            val dt = (tMs - lastFixMs) / 1000.0
            if (dt in 0.5..3.0 && lonCountSinceFix > 10) {
                val gpsLon = (speedMps - lastFixSpeed) / dt / G
                val imuLon = lonSumSinceFix / lonCountSinceFix
                val err = imuLon - gpsLon
                lonBias += (err - lonBias) * (dt / TAU_BIAS).coerceAtMost(1.0)
                lonBias = lonBias.coerceIn(-0.3, 0.3)
            }
        }
        lonSumSinceFix = 0.0
        lonCountSinceFix = 0
        lastFixMs = tMs
        lastFixSpeed = speedMps
        speed = speedMps
    }

    /** GPS sinyali yokken (tünel) hız tahmininin kaybolmaması için dışarıdan zayıflatma. */
    fun decaySpeed(factor: Double) { speed *= factor }

    val leanDeg: Double get() = Math.toDegrees(phi)
    val yawDps: Double get() = Math.toDegrees(yawRate)
}
