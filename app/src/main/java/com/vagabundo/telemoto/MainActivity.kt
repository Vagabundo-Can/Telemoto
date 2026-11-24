package com.vagabundo.telemoto

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vagabundo.telemoto.data.ThemeMode
import com.vagabundo.telemoto.service.Phase
import com.vagabundo.telemoto.service.RideSession
import com.vagabundo.telemoto.ui.Amber
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.TelemotoTheme
import com.vagabundo.telemoto.ui.components.Ico
import com.vagabundo.telemoto.ui.components.TmIcon
import com.vagabundo.telemoto.ui.screens.HistoryScreen
import com.vagabundo.telemoto.ui.screens.HomeScreen
import com.vagabundo.telemoto.ui.screens.LiveScreen
import com.vagabundo.telemoto.ui.screens.RideDetailScreen
import com.vagabundo.telemoto.ui.screens.SettingsScreen
import com.vagabundo.telemoto.ui.screens.SummaryScreen

class MainActivity : ComponentActivity(), SensorEventListener {

    /** Otomatik tema: ortam ışığı 2.000 lux üstü → açık, 800 altı → koyu (histerezis). */
    private val autoLight = mutableStateOf(false)
    private var sm: SensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TelemotoApp
        setContent {
            val settings by app.prefs.flow.collectAsState()
            val light = when (settings.theme) {
                ThemeMode.LIGHT -> true
                ThemeMode.DARK -> false
                ThemeMode.AUTO -> autoLight.value
            }
            TelemotoTheme(light) { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        sm?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0]
        if (!autoLight.value && lux > 2000f) autoLight.value = true
        else if (autoLight.value && lux < 800f) autoLight.value = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SUMMARY = "summary"
    const val SETTINGS = "settings"
    const val LIVE = "live"
    const val RIDE = "ride/{id}"
    fun ride(id: String) = "ride/$id"
}

@Composable
fun AppRoot() {
    val t = LocalTm.current
    val nav = rememberNavController()
    val live by RideSession.flow.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    // Kayıt sürüyorsa uygulama açıldığında canlı ekrana git
    LaunchedEffect(Unit) {
        if (live.phase != Phase.IDLE) nav.navigate(Routes.LIVE)
    }

    val tabs = listOf(
        Triple(Routes.HOME, "Sürüş", Ico.RIDE),
        Triple(Routes.HISTORY, "Geçmiş", Ico.LIST),
        Triple(Routes.SUMMARY, "Özet", Ico.CHART),
        Triple(Routes.SETTINGS, "Ayarlar", Ico.GEAR),
    )
    val showBar = route in tabs.map { it.first }

    Box(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { NavGraph(nav) }
            if (showBar) {
                NavigationBar(containerColor = t.surf) {
                    tabs.forEach { (r, label, ico) ->
                        val sel = route == r
                        NavigationBarItem(
                            selected = sel,
                            onClick = { if (!sel) nav.navigate(r) { popUpTo(Routes.HOME); launchSingleTop = true } },
                            icon = { TmIcon(ico, if (sel) t.accInk else t.mute) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = t.accInk, unselectedTextColor = t.mute,
                                indicatorColor = Amber.copy(alpha = 0.18f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavGraph(nav: NavHostController) {
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onStartLive = { nav.navigate(Routes.LIVE) }, onOpenRide = { nav.navigate(Routes.ride(it)) })
        }
        composable(Routes.HISTORY) { HistoryScreen(onOpenRide = { nav.navigate(Routes.ride(it)) }) }
        composable(Routes.SUMMARY) { SummaryScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.LIVE) {
            LiveScreen(
                onExit = { nav.popBackStack() },
                onSaved = { id -> nav.navigate(Routes.ride(id)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.RIDE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            RideDetailScreen(id = e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
        }
    }
}
