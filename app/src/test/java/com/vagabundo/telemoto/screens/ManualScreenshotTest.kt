package com.vagabundo.telemoto.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.vagabundo.telemoto.AppRoot
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.analysis.RideAnalyzer
import com.vagabundo.telemoto.data.RideLogFormat
import com.vagabundo.telemoto.data.RideMode
import com.vagabundo.telemoto.service.LiveState
import com.vagabundo.telemoto.service.Phase
import com.vagabundo.telemoto.service.RideSession
import com.vagabundo.telemoto.share.ShareCards
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.TelemotoTheme
import com.vagabundo.telemoto.ui.screens.LiveScreen
import com.vagabundo.telemoto.ui.screens.RideDetailScreen
import com.vagabundo.telemoto.ui.screens.SettingsScreen
import com.vagabundo.telemoto.ui.screens.SummaryScreen
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Kullanım kılavuzu için gerçek arayüzden ekran görüntüleri.
 * Çalıştırma: ./gradlew :app:testDebugUnitTest -Pscreens=true --tests '*ManualScreenshotTest*'
 * Çıktı: app/build/screens/
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-port-xxhdpi")
class ManualScreenshotTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = "build/screens"
    private val app get() = rule.activity.application as TelemotoApp

    @Before fun setUp() {
        assumeTrue(System.getProperty("screens") == "true")
        SampleRides.seed(app.store)
        RideSession.state.value = LiveState()
    }

    private fun shot(name: String, light: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                TelemotoTheme(light) {
                    Box(Modifier.fillMaxSize().background(LocalTm.current.bg)) { content() }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$out/$name.png")
    }

    private fun waitText(text: String) {
        rule.waitUntil(20_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun t01_home_dark() = shot("01_ana_ekran_koyu", false) { AppRoot() }
    @Test fun t02_home_light() = shot("02_ana_ekran_acik", true) { AppRoot() }

    @Config(qualifiers = "w873dp-h393dp-land-xxhdpi")
    @Test fun t03_calibration() {
        RideSession.state.value = LiveState(
            phase = Phase.CALIBRATING, mode = RideMode.PERF, calibProgress = 0.7f, calibMessage = "Hareketsiz bekle…",
            bubbleRollDeg = 1.2f, bubblePitchDeg = 14.2f, stationary = true, gpsFix = true, gpsAccuracyM = 4f,
        )
        shot("03_kalibrasyon", false) { LiveScreen(onExit = {}, onSaved = {}) }
    }

    private fun recording(mode: RideMode) = LiveState(
        phase = Phase.RECORDING, mode = mode, elapsedMs = 2_537_000, distanceM = 38_400.0, speedKmh = if (mode == RideMode.PERF) 78f else 46f,
        leanDeg = if (mode == RideMode.PERF) 32f else 1f, gLon = if (mode == RideMode.PERF) -0.42f else 0.06f, gLat = 0.61f,
        maxLeanL = 41f, maxLeanR = 47f, maxSpeedKmh = 112f, avgSpeedKmh = 64f, corners = 87, maxBrakeG = -0.82f,
        ecoScore = 84, stops = 9, stoppedS = 245.0, ecoAccel = 2, ecoBrake = 1, pke = 0.31, fuelLPer100 = 4.5,
        gpsFix = true, gpsAccuracyM = 4f, hr = 124, marks = 1,
    )

    @Config(qualifiers = "w873dp-h393dp-land-xxhdpi")
    @Test fun t04_live_perf_dark() { RideSession.state.value = recording(RideMode.PERF); shot("04_performans_koyu", false) { LiveScreen({}, {}) } }

    @Config(qualifiers = "w873dp-h393dp-land-xxhdpi")
    @Test fun t05_live_perf_light() { RideSession.state.value = recording(RideMode.PERF); shot("05_performans_acik", true) { LiveScreen({}, {}) } }

    @Config(qualifiers = "w873dp-h393dp-land-xxhdpi")
    @Test fun t06_live_eco() { RideSession.state.value = recording(RideMode.ECO); shot("06_ekonomi_koyu", false) { LiveScreen({}, {}) } }

    @Config(qualifiers = "w393dp-h1000dp-port-xxhdpi")
    @Test fun t07_detail_perf() {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                TelemotoTheme(false) { Box(Modifier.fillMaxSize().background(LocalTm.current.bg)) { RideDetailScreen(SampleRides.PERF_ID, {}, initialFraction = 0.42f) } }
            }
        }
        waitText("En derin")
        rule.onRoot().captureRoboImage("$out/07_analiz_performans.png")
    }

    @Config(qualifiers = "w393dp-h1000dp-port-xxhdpi")
    @Test fun t08_detail_eco() {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                TelemotoTheme(true) { Box(Modifier.fillMaxSize().background(LocalTm.current.bg)) { RideDetailScreen("20261125-081100", {}, initialFraction = 0.3f) } }
            }
        }
        waitText("Puan kaybı")
        rule.onRoot().captureRoboImage("$out/08_analiz_ekonomi.png")
    }

    @Test fun t09_summary() = shot("09_ozet", false) { SummaryScreen() }

    @Config(qualifiers = "w393dp-h1400dp-port-xxhdpi")
    @Test fun t10_settings() = shot("10_ayarlar", false) { SettingsScreen() }

    @Test fun t11_share_cards() {
        val p = RideLogFormat.read(app.store.logFile(SampleRides.PERF_ID))
        val st = RideAnalyzer.analyze(p.samples, emptyList(), app.prefs.value.th)
        val route = p.samples.filterIndexed { i, _ -> i % 25 == 0 }
        File(out).mkdirs()
        listOf(ShareCards.NIGHT to "11_paylasim_gece_916", ShareCards.TOPO to "12_paylasim_topo_169").forEach { (tpl, name) ->
            val bmp = ShareCards.render(tpl, "Karagöl Sürüşü", RideMode.PERF, p.header.startEpochMs, route, st)
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
