package com.vagabundo.telemoto.sensor

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/** 3B vektör yardımcıları (saf Kotlin). */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = V3(x * k, y * k, z * k)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun unit(): V3 { val n = norm(); return if (n < 1e-9) this else this * (1.0 / n) }

    companion object { val ZERO = V3(0.0, 0.0, 0.0) }
}

/**
 * Telefon → motosiklet dönüşümü.
 * Motor ekseni: X ileri, Y sol, Z yukarı. Satırlar motor eksenlerinin telefon koordinatlarındaki karşılığı.
 */
data class MountRotation(val bx: V3, val by: V3, val bz: V3) {
    fun apply(p: V3) = V3(bx dot p, by dot p, bz dot p)

    fun encode(): String = listOf(bx, by, bz).joinToString(";") { "%.5f,%.5f,%.5f".format(java.util.Locale.US, it.x, it.y, it.z) }

    companion object {
        val IDENTITY = MountRotation(V3(1.0, 0.0, 0.0), V3(0.0, 1.0, 0.0), V3(0.0, 0.0, 1.0))
    }
}

data class CalibrationResult(
    val ok: Boolean,
    val message: String,
    val rotation: MountRotation,
    val gyroBias: V3,
    /** Telefonun yanal eğimi (derece) — motor dik değilse ya da mount yamuksa büyür. */
    val rollOffsetDeg: Double,
    /** Mount'un öne/arkaya eğimi (derece). */
    val pitchDeg: Double,
    val gyroBiasDps: Double,
)

/**
 * Kalibrasyon: motor dik ve durağanken ~2 sn ivme + gyro ortalaması.
 * Telefon yatay; şarj girişi solda → telefonun +y ekseni sürücünün sağını gösterir.
 */
object Calibrator {
    const val G = 9.80665

    /** Durağanlık sınırları (motor rölantide titreşir, bu yüzden geniş tutuldu). */
    const val MAX_GYRO_RAD_S = 0.06
    const val MAX_ACCEL_STD = 0.8
    const val WARN_ROLL_DEG = 3.0
    const val MAX_ROLL_DEG = 12.0

    fun compute(accel: List<V3>, gyro: List<V3>): CalibrationResult {
        val fail = { msg: String -> CalibrationResult(false, msg, MountRotation.IDENTITY, V3.ZERO, 0.0, 0.0, 0.0) }
        if (accel.size < 50 || gyro.size < 50) return fail("Yeterli sensör verisi yok")

        val aMean = accel.fold(V3.ZERO) { s, v -> s + v } * (1.0 / accel.size)
        val gMean = gyro.fold(V3.ZERO) { s, v -> s + v } * (1.0 / gyro.size)
        val aStd = sqrt(accel.sumOf { (it - aMean).let { d -> d dot d } } / accel.size)
        val gyroMotion = gyro.maxOf { (it - gMean).norm() }

        if (abs(aMean.norm() - G) > 1.5) return fail("İvme ölçer tutarsız (${"%.1f".format(aMean.norm())} m/s²)")
        if (aStd > MAX_ACCEL_STD || gyroMotion > MAX_GYRO_RAD_S * 4) return fail("Hareket algılandı — 2 sn sabit bekle")
        if (gMean.norm() > MAX_GYRO_RAD_S) return fail("Gyro sapması yüksek — tekrar dene")

        val up = aMean.unit()                       // ivme ölçer durağanken yukarıyı gösterir
        // Yatay montaj: şarj girişi solda → telefonun +x'i aşağı bakar (up.x < 0) ve +y sağı gösterir.
        // Şarj girişi sağdaysa her ikisi de ters döner; sağ/sol işareti otomatik düzelir.
        if (abs(up.x) < 0.5) return fail("Telefon yatay takılı değil — şarj girişi yana bakacak şekilde tak")
        val portLeft = up.x < 0
        val phoneRight = if (portLeft) V3(0.0, 1.0, 0.0) else V3(0.0, -1.0, 0.0)
        val sideComp = phoneRight dot up
        val rollDeg = Math.toDegrees(asin(sideComp.coerceIn(-1.0, 1.0)))
        if (abs(rollDeg) > MAX_ROLL_DEG) {
            return fail("Telefon çok yan yatık (${"%.0f".format(rollDeg)}°) — motoru dikleştir ya da mount'u düzelt")
        }
        val right = (phoneRight - up * sideComp).unit()
        val left = right * -1.0
        val fwd = left cross up                      // X = Y × Z
        val rot = MountRotation(fwd, left, up)

        // Mount eğimi: ekran normalinin (+z) yataydan yukarı açısı. Dik ekran = 0°, arkaya yatık = +.
        val pitch = Math.toDegrees(asin((V3(0.0, 0.0, 1.0) dot up).coerceIn(-1.0, 1.0)))

        val msg = if (abs(rollDeg) > WARN_ROLL_DEG) "Kalibre edildi — yanal eğim ${"%.1f".format(rollDeg)}°: motor dik miydi?" else "Kalibre edildi"
        return CalibrationResult(true, msg, rot, gMean, rollDeg, pitch, Math.toDegrees(gMean.norm()))
    }
}
