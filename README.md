# Telemoto

Motosiklet sürüş telemetrisi — Vagabundo Apps.

- **Performans:** viraj başına yatış (sol/sağ), giriş/apex/çıkış hızı, fren ve gaz g, harita üzerinde replay, paylaşım görseli.
- **Ekonomi:** akıcılık skoru, dur-kalk, ani hızlanma/fren, PKE, depo kayıtlarıyla kalibre edilen yakıt tahmini, gün/hafta/ay özeti.

## Kullanım
1. Telefonu mount'a **yatay** tak (şarj girişi solda ya da sağda — otomatik algılanır).
2. Mod seç → **Kalibre et ve başlat**. Motor dik ve 2 sn hareketsiz olmalı.
3. Ekran kapansa da kayıt sürer (kilit ekranındaki bildirimden *Olay işaretle* / *Durdur*).
4. Sena'nın sesli asistan tuşu → Telemoto: "kalibre et", "kaydı başlat", "olay işaretle", "kaydı durdur". Telemoto kayıt dışındayken istek Gemini'ye aktarılır.

## Teknik
- Kotlin + Jetpack Compose, minSdk 29, osmdroid (OSM) harita.
- Yatış: gyro entegrasyonu + kinematik referans `φ = atan(−v·r/g)` (tamamlayıcı filtre). Koordineli virajda ivme ölçer ~0° gösterdiği için su terazisi yöntemi kullanılmaz.
- Boyuna ivme: IMU (2 Hz alçak geçiren) + GPS dv/dt ile bias düzeltmesi.

### `.ridelog` v1
```
#telemoto-ridelog v1
#id=… #name=… #mode=PERF|ECO #start=<epoch ms> #calib=… #app=…
#cols=t_ms,lat,lon,alt,speed,lean,glon,glat,yaw,gps
S,<t>,<lat>,<lon>,<alt m>,<speed m/s>,<lean °, + sağ>,<g boyuna>,<g yanal>,<yaw °/s>,<gps taze 0|1>
E,<t>,MARK
```
25 Hz. Video overlay uygulaması aynı dosyayı okuyabilir.

## Derleme
`main`'e her push'ta GitHub Actions birim testleri çalıştırır ve APK üretir (Actions › Build APK › `telemoto-apk`).
