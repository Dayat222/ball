# Product Requirements Document (PRD): Screen Angle Ruler Overlay

**Status dokumen:** diselaraskan dengan implementasi **v2.6** (versionCode 17).
Bagian yang sudah tidak berlaku ditandai di §8.

## 1. Ringkasan Produk

- **Nama aplikasi:** Screen Angle Ruler
- **Package:** `com.utility.screenprotractor`
- **Platform:** Android, minSdk 26, targetSdk 34
- **Bahasa & build:** Kotlin, Gradle Kotlin DSL, Android SDK standar tanpa library berat
- **Tujuan inti:** overlay transparan untuk memvisualisasikan arah tembakan bola pada meja
  biliar: ring seukuran bola, 6 garis langsung ke mulut lubang, dan 10 garis pantul bantalan
  yang dihitung secara geometris.
- **Prinsip arsitektur:** *non-intrusive*. Overlay tidak menyerap sentuhan, tidak membaca layar,
  tidak memakai AccessibilityService maupun MediaProjection.

## 2. Alur Pengguna

1. Pengguna membuka `MainActivity`, aplikasi mengecek izin `SYSTEM_ALERT_WINDOW`.
2. Tombol **Start Ruler Overlay** menjalankan `OverlayService` sebagai foreground service lalu
   menutup activity.
3. Muncul tiga komponen: kanvas overlay (dipotong seukuran meja), satu handle target `+`, dan
   toolbar mengambang.
4. Pengguna melakukan kalibrasi sekali: tombol **Calib** membuat kanvas memenuhi layar, menarik
   8 handle batas (4 sudut + 4 tengah sisi), lalu **Save Bound**. Batas disimpan otomatis.
5. Selama bermain, pengguna menggeser handle target. Ring dan seluruh ray dihitung ulang secara
   real-time; sentuhan lain menembus ke game di bawahnya.
6. Toolbar bisa diciutkan menjadi kapsul mini dengan tombol ON/OFF untuk layar bersih total.

## 3. Kebutuhan Fungsional

### 3.1 Manajemen multi-window (`OverlayService`)

- `CanvasOverlayView`: `MATCH_PARENT` saat kalibrasi, dipotong seukuran boundary box + `18dp`
  saat bermain. Selalu `FLAG_NOT_TOUCHABLE` + `FLAG_NOT_FOCUSABLE`; touch diaktifkan hanya saat
  kalibrasi. Alpha window `0.78`.
- `HandleView` target: window `42dp` terpisah, selalu menerima sentuhan, digeser dengan offset
  anti-jari `48dp` horizontal dan `36dp` vertikal.
- `FloatingControlView`: toolbar `WRAP_CONTENT` yang bisa di-drag, atau kapsul mini setelah
  minimize.

### 3.2 Geometri & render (`CanvasOverlayView`)

- Ruang koordinat: landscape (`max(w,h) × min(w,h)`); kanvas yang dipotong dikompensasi dengan
  translasi `(-winX, -winY)`.
- **6 direct ray** dari titik target ke 6 mulut lubang:
  - 4 sudut memakai inset `9.0dp` horizontal dan `11.2dp` vertikal ke pusat mulut lubang.
  - 2 lubang tengah memakai kompensasi kemiringan (`tiltRatio`) dengan pergeseran maksimum
    `6–9dp`.
- **10 bank ray**: titik tujuan lubang dicerminkan terhadap bidang bantalan, titik pantul dicari
  secara analitik, lalu digambar garis datang + garis pantul. Bantalan dimodelkan pada
  `boundary box ± radius bola`. Ray tetap tampil ketika bola menempel bantalan.
- Panjang ray: **Short** (`85dp`) atau **Full** (sampai lubang/vertex, plus titik kontak bantalan).
- Mode render tunggal: *single-point target*. Garis A–B dan mode Auto-Reflect / Manual 3-Point /
  Edge-Marker sudah dihapus.

### 3.3 Kalibrasi & persistensi (`PrefsManager`)

- 8 handle kalibrasi: 4 sudut + 4 tengah sisi, dengan clamp otomatis dan validasi agar kotak tidak
  terbalik/kolaps.
- `calib_version` disimpan; kalibrasi dari versi lama otomatis direset.
- Default boundary mengikuti proporsi meja 2:1 (`x: 19.1%`, `y: 22.8%` sampai `92.1%`).
- Disimpan di `SharedPreferences` `screen_protractor_prefs`: boundary box, posisi target,
  ketebalan, opacity, warna garis, warna bank ray, radius ring, status Bank, dan mode panjang ray.

### 3.4 Toolbar & pengaturan

- Toolbar utama: drag handle, `Short/Full`, `Bank`, `Calib`, `⚙`, `✕`.
- Panel `⚙`: slider ketebalan (`0.5–15dp`), opacity, ukuran ring (`8–30dp`), 6 swatch warna garis
  + hue 360°, 6 swatch warna bank ray + hue 360°, dan tombol cek update.
- Kapsul mini: drag, tombol `ON/OFF`, tombol expand.

### 3.5 Pembaruan dalam aplikasi (`AppUpdateManager`)

- Sumber utama: `https://raw.githubusercontent.com/Dayat222/ball/main/version.json` dengan
  cache-buster.
- Fallback: asset `version.json` pada GitHub Release `latest` melalui API.
- Perbandingan memakai `versionCode` manifes; tag `latest` tidak pernah dianggap nomor versi.
- Unduhan mengikuti redirect (maks. 6), progres persentase, lalu instalasi via `FileProvider`
  dan `REQUEST_INSTALL_PACKAGES`.

## 4. Izin

| Izin | Alasan |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Menampilkan overlay |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Foreground service di Android 14+ |
| `INTERNET` | Cek update dan unduh APK |
| `REQUEST_INSTALL_PACKAGES` | Memulai installer APK hasil unduhan |

**Dilarang:** `AccessibilityService`, `MediaProjection`, `QUERY_ALL_PACKAGES`, analytics, dan SDK
pihak ketiga.

## 5. Spesifikasi Visual

- Latar toolbar `#CC1E1E1E`, sudut `12dp`.
- Ray lubang: warna utama (default `#00E5FF`), `strokeWidth` mengikuti slider, `Cap.ROUND`.
- Bank ray: warna sekunder (default `#FFAB00`), dashed `10dp/7dp`, dengan titik kontak `3.5dp`
  pada mode Full.
- Ring target: bayangan hitam `2.8dp` + cincin putih `1.4dp`; titik pusat ganda `2.5dp`/`1.5dp`.
- Tether handle: putih dashed `6dp/6dp`, `1.5dp`, alpha 70% dari alpha garis.

## 6. Build & CI/CD

- Workflow: `.github/workflows/build-apk.yml`, trigger semua push dan `workflow_dispatch`.
- Runner `ubuntu-latest`, JDK 17, Gradle 8.4 via `gradle/actions/setup-gradle`.
- Build `assembleDebug`; artifact `ScreenAngleRuler-Debug-APK`.
- Push ke `main` juga merefresh release tag `latest` berisi `app-debug.apk` **dan** `version.json`.
- Debug dan release ditandatangani dengan `app/release.keystore` agar update tidak bentrok
  signature. Keystore dan password ada di repo: repo harus diperlakukan sebagai rahasia.

## 7. Kriteria Rilis

1. `versionCode` di `app/build.gradle.kts` dan `version.json` harus sama dan **lebih besar** dari
   versi terpasang.
2. `changelog` di `version.json` diperbarui.
3. CI hijau dan asset release `latest` berisi APK + `version.json`.
4. APK baru bisa dipasang di atas APK lama tanpa uninstall.

## 8. Perubahan dari spesifikasi awal

| Item spesifikasi lama | Status v2.6 |
|---|---|
| Mode Auto-Reflect (A→B→dinding), Manual 3-Point, Edge-Marker | Dihapus; tidak dapat diakses sejak v2.4 |
| Handle A dan C | Dihapus |
| Quick-Ghost 4 detik | Dihapus |
| Tombol Lock/Edit manual | Diganti: kanvas selalu pass-through, touch hanya saat kalibrasi |
| Kebijakan 100% offline tanpa `INTERNET` | Tidak berlaku sejak updater OTA ditambahkan; lihat §4 |
| Tombol Reset Bound di toolbar | Diganti tombol `Calib` (8 handle) + reset dari `MainActivity` |
| Rotasi layar memakai koordinat portrait | Semua geometri memakai ruang landscape |
