package com.vagabundo.telemoto.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vagabundo.telemoto.TelemotoApp
import com.vagabundo.telemoto.data.ThemeMode
import com.vagabundo.telemoto.data.Thresholds
import com.vagabundo.telemoto.ui.Amber
import com.vagabundo.telemoto.ui.OnAmber
import com.vagabundo.telemoto.ui.LocalTm
import com.vagabundo.telemoto.ui.components.GhostButton
import com.vagabundo.telemoto.ui.components.Panel
import com.vagabundo.telemoto.ui.components.Segmented
import com.vagabundo.telemoto.ui.components.SmallLabel
import com.vagabundo.telemoto.ui.components.f1
import com.vagabundo.telemoto.ui.components.signedG

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen() {
    val t = LocalTm.current
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TelemotoApp
    val s by app.prefs.flow.collectAsState()
    val pm = ctx.getSystemService(PowerManager::class.java)
    val batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    fun th(f: (Thresholds) -> Thresholds) = app.prefs.update { it.copy(th = f(it.th)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ayarlar", color = t.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("Görünüm", t.accInk)
            Segmented(listOf(ThemeMode.DARK to "Koyu", ThemeMode.LIGHT to "Açık", ThemeMode.AUTO to "Otomatik"), s.theme, Modifier.fillMaxWidth().padding(top = 8.dp)) { v ->
                app.prefs.update { it.copy(theme = v) }
            }
            Text("Otomatik: ortam ışığı 2.000 lux üstünde açık temaya geçer (güneşte okunurluk), 800 lux altında koyuya döner.", color = t.mute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("Olay eşikleri · Performans", t.accInk)
            Thr("Fren", "≤ ${signedG(s.th.perfBrakeG)} g", -s.th.perfBrakeG, 0.1f..0.8f, t.red) { v -> th { it.copy(perfBrakeG = -v) } }
            Thr("Sert fren", "≤ ${signedG(s.th.perfHardBrakeG)} g", -s.th.perfHardBrakeG, 0.3f..1.0f, t.red) { v -> th { it.copy(perfHardBrakeG = -v) } }
            Thr("Ani gaz", "≥ ${signedG(s.th.perfAccelG)} g", s.th.perfAccelG, 0.1f..0.6f, t.green) { v -> th { it.copy(perfAccelG = v) } }
            Thr("Minimum süre", "${f1(s.th.minEventSec)} sn", s.th.minEventSec, 0.1f..1.5f, t.ink) { v -> th { it.copy(minEventSec = v) } }
            HorizontalDivider(color = t.line, modifier = Modifier.padding(vertical = 6.dp))
            SmallLabel("Ekonomi modu")
            Thr("Ani hızlanma", "≥ ${signedG(s.th.ecoAccelG)} g", s.th.ecoAccelG, 0.05f..0.4f, t.accInk) { v -> th { it.copy(ecoAccelG = v) } }
            Thr("Ani fren", "≤ ${signedG(s.th.ecoBrakeG)} g", -s.th.ecoBrakeG, 0.1f..0.6f, t.red) { v -> th { it.copy(ecoBrakeG = -v) } }
            Text("Duruş: 3 km/h altında 2 sn · yumuşak bölge = ani hızlanma eşiği", color = t.mute, fontSize = 12.sp)
        }

        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("Viraj tespiti", t.accInk)
            Thr("Viraj başlangıcı (yatış)", "${s.th.cornerStartDeg.toInt()}°", s.th.cornerStartDeg, 3f..20f, t.ink, step = 1f) { v -> th { it.copy(cornerStartDeg = v) } }
            Text("Bitiş: 3° altında 0,5 sn. S virajda yön değişince yeni viraj sayılır.", color = t.mute, fontSize = 12.sp)
        }

        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("Arka plan kaydı", t.accInk)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pil optimizasyonu", color = t.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (batteryOk) "Muaf ✓" else "Kısıtlı", color = if (batteryOk) t.green else t.red, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            if (!batteryOk) {
                GhostButton("Muaf tut (önerilir)", Modifier.fillMaxWidth().padding(top = 6.dp), height = 48.dp) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
            }
            Text("Samsung'da ayrıca: Ayarlar › Pil › Arka plan kullanım sınırları › Hiç uyumayan uygulamalar › Telemoto.", color = t.mute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("Sena / sesli komut", t.accInk)
            Toggle("Sesli komutları dinle", s.voiceCommands) { v -> app.prefs.update { it.copy(voiceCommands = v) } }
            Toggle("Sena'dan sesli onay", s.voiceFeedback) { v -> app.prefs.update { it.copy(voiceFeedback = v) }; app.speaker.enabled = v }
            Text(
                "Kurulum: Sena'nın sesli asistan tuşuna bas → Android hangi uygulamanın açılacağını sorarsa Telemoto › Her zaman. " +
                    "Daha önce Gemini “her zaman” seçildiyse Varsayılan uygulamalar'dan sıfırla. " +
                    "Telemoto kayıt dışındayken isteği doğrudan Gemini'ye aktarır.",
                color = t.mute, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Test et", Modifier.weight(1f), height = 48.dp) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                GhostButton("Varsayılan uygulamalar", Modifier.weight(1f), height = 48.dp) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("Galaxy Watch", t.accInk)
            Toggle("Ekonomi koçluğu: ani hızlanma/frende saat titreşsin", s.ecoCoachVibrate) { v -> app.prefs.update { it.copy(ecoCoachVibrate = v) } }
            Text(
                "Saatte Telemoto: Başlat / Olay işaretle (ekrana dokun ya da Geri tuşu) / Durdur (basılı tut). " +
                    "Kısayol: saat Ayarlar › Gelişmiş özellikler › Ana Sayfa tuşuna iki kez bas › Telemoto. " +
                    "Nabız, saatteki uygulama kayıt sırasında açıldığında kaydedilir.",
                color = t.mute, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            SmallLabel("Veri", t.accInk)
            Text("Kayıt formatı: .ridelog (25 Hz, düz metin) · dışa aktarım GPX/CSV", color = t.ink, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Klasör: ${app.store.dir.absolutePath}", color = t.mute, fontSize = 12.sp)
            Text("Füzyon: tamamlayıcı filtre · IMU 100 Hz · GPS 1 Hz", color = t.mute, fontSize = 12.sp)
        }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun Thr(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, color: androidx.compose.ui.graphics.Color, step: Float = 0.01f, onChange: (Float) -> Unit) {
    val t = LocalTm.current
    Column(Modifier.padding(top = 6.dp)) {
        Row { Text(label, color = t.ink, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(valueText, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Slider(
            value = value.coerceIn(range), onValueChange = { onChange((Math.round(it / step) * step)) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalTm.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = OnAmber, checkedTrackColor = Amber))
    }
}

