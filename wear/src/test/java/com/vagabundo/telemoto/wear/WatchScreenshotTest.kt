package com.vagabundo.telemoto.wear

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material.MaterialTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Kılavuz için saat ekranları. Çıktı: wear/build/screens/ */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class WatchScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun guard() { assumeTrue(System.getProperty("screens") == "true") }

    private fun shot(name: String, ui: WatchUi) {
        WatchState.ui.value = ui
        rule.setContent { MaterialTheme { WatchApp() } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun now() = SystemClock.elapsedRealtime()

    @Test fun w1_ready() = shot("w1_hazir", WatchUi(state = WearProtocol.State(phase = "IDLE"), stateAt = now()))

    @Test fun w2_perf() = shot("w2_kayit_performans", WatchUi(
        state = WearProtocol.State("RECORDING", "PERF", 2_537_000, 38_400.0, 78, 32, -1, 41, 47, 124, false, ""), stateAt = now()))

    @Test fun w3_eco() = shot("w3_kayit_ekonomi", WatchUi(
        state = WearProtocol.State("RECORDING", "ECO", 1_300_000, 11_200.0, 46, 1, 84, 0, 0, 96, false, ""), stateAt = now()))

    @Test fun w4_summary() = shot("w4_ozet", WatchUi(
        state = WearProtocol.State(phase = "IDLE"), stateAt = now(),
        summary = listOf("Sürüş bitti · Performans", "62,4 km", "L 41° · S 47°", "143 viraj")))
}
