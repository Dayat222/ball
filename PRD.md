# Product Requirements Document (PRD): Screen Angle & Geometry Ruler Overlay

## 1. Project Overview
- **App Name:** Screen Angle Ruler
- **Package Name:** `com.utility.screenprotractor`
- **Platform:** Android (Min SDK 26 / Android 8.0+, Target SDK 34 / Android 14)
- **Language & Build System:** Kotlin, Gradle (Kotlin DSL `build.gradle.kts`), Android SDK Standar (Tanpa library pihak ketiga yang berat).
- **CI/CD Build:** GitHub Actions (Otomatis kompilasi & menghasilkan artifact `app-debug.apk` setiap kali ada `git push`).
- **Core Objective:** Aplikasi utilitas penggaris layar transparan (*screen overlay*) yang berjalan di atas layar untuk memvisualisasikan garis lurus dan sudut pantulan vektor 2D ($\theta_i = \theta_r$) secara manual dan presisi, dilengkapi arsitektur *non-intrusive* (tidak mencampuri aplikasi di bawahnya) dan mode *quick-hide*.

---

## 2. User Flow (Alur Pengguna)
1. Pengguna membuka aplikasi utama (`MainActivity`).
2. Aplikasi memeriksa izin `SYSTEM_ALERT_WINDOW` (Display over other apps). Jika belum diberikan, tampilkan tombol untuk membuka pengaturan izin sistem.
3. Pengguna menekan tombol **"Start Ruler Overlay"** di `MainActivity`, yang akan menjalankan `OverlayService` dan menutup `MainActivity`.
4. Di layar muncul dua komponen terpisah:
   - **Floating Control Bar:** Panel kecil bertema gelap yang bisa digeser ke sudut layar mana pun.
   - **Canvas Overlay:** Lapisan transparan tempat menggambar garis bantu dan batas pantulan (*boundary box*).
5. Pengguna mengatur batas kotak pantulan (*Boundary Box*) satu kali (tersimpan otomatis di `SharedPreferences`), lalu menggeser titik **Point A** dan **Point B** untuk melihat sudut pantulan.
6. Saat ingin berinteraksi penuh dengan aplikasi di bawahnya, pengguna menekan tombol **`[Lock]`** (tembus sentuhan) atau **`[Hide]`** (menyembunyikan kanvas sepenuhnya dari layar sementara waktu).

---

## 3. Core Functional Requirements (Fitur Utama)

### Fitur 1: Sistem Multi-Window di `OverlayService`
Gunakan **dua** instance `View` yang terpisah di dalam `WindowManager` menggunakan tipe `TYPE_APPLICATION_OVERLAY`:
1. **`CanvasOverlayView` (`MATCH_PARENT` x `MATCH_PARENT`):**
   - Berfungsi khusus merender grafik garis, titik kontrol (*handle*), dan kotak batas pantulan.
   - Memiliki 3 status interaksi:
     - **EDIT MODE:** Menerima input sentuhan jari untuk menggeser titik koordinat (`FLAG_NOT_FOCUSABLE`).
     - **LOCK MODE (Pass-Through):** Menambahkan flag `FLAG_NOT_TOUCHABLE` sehingga seluruh sentuhan jari menembus langsung ke aplikasi di bawahnya. Lingkaran *handle* disembunyikan, menyisakan garis tipis.
     - **HIDDEN MODE (Clear Touch):** Mengubah visibilitas `CanvasOverlayView` menjadi `View.GONE` sehingga tidak ada lapisan overlay sama sekali di atas layar utama.
2. **`FloatingControlView` (`WRAP_CONTENT` x `WRAP_CONTENT`):**
   - Panel tombol kontrol yang selalu aktif menerima klik (`FLAG_NOT_FOCUSABLE`) dan bisa di-*drag* ke posisi mana saja di layar.

### Fitur 2: Geometri Garis & Pantulan (`CanvasOverlayView` & `GeometryUtils`)
Sediakan 2 mode perhitungan garis yang dapat diganti melalui tombol di panel kontrol:
- **Mode 1: Auto-Reflection ($\theta_i = \theta_r$)**
  - Menampilkan persegi panjang batas area pantul (*Boundary Box*) yang ke-4 sisinya dapat dikalibrasi oleh pengguna. Posisi batas ini wajib disimpan ke `SharedPreferences` agar tidak perlu diatur ulang saat aplikasi dibuka kembali.
  - Pengguna menggeser **Point A (Titik Awal)** dan **Point B (Titik Arah)**.
  - Sistem menghitung titik perpotongan (*ray-box intersection*) antara sinar dari Point A yang melewati Point B terhadap sisi *Boundary Box*.
  - Dari titik potong tersebut, gambar garis pantulan kedua secara otomatis dengan membalik vektor arah:
    - Pantulan pada sisi Horizontal (Atas/Bawah): $(dx', dy') = (dx, -dy)$
    - Pantulan pada sisi Vertikal (Kiri/Kanan): $(dx', dy') = (-dx, dy)$
- **Mode 2: Manual 3-Point Line**
  - Pengguna menggeser 3 titik secara bebas: **Point A (Start)**, **Point B (Pivot/Mid)**, dan **Point C (End)**.

### Fitur 3: Safe-Operation & Non-Intrusive Architecture
Untuk menjaga keamanan privasi, performa, dan mencegah konflik deteksi *obscured touch* pada Android 12+:
1. **Strict Permission & Offline Policy:**
   - Aplikasi harus 100% *offline* (**DILARANG** menambahkan `android.permission.INTERNET`).
   - Hanya gunakan izin `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, dan `FOREGROUND_SERVICE_SPECIAL_USE` (untuk Android 14).
   - **DILARANG** menggunakan `AccessibilityService`, `MediaProjection`, atau `QUERY_ALL_PACKAGES`.
2. **Edge-Marker Mode (Titik Patokan Pinggir):**
   - Tambahkan opsi pada tombol mode di mana saat garis dikunci (*Lock*), aplikasi menyembunyikan garis melintang di tengah layar dan hanya menampilkan indikator titik kecil (`16dp`) tepat di titik pantul pada sisi *Boundary Box*.
3. **Neutral Identity:**
   - Seluruh penamaan variabel, komentar kode, nama paket (`com.utility.screenprotractor`), dan string UI harus menggunakan terminologi penggaris geometri/desain layar umum.

### Fitur 4: Desain Floating Control Panel (Compact Dark UI)
Buat UI panel kontrol secara terprogram (atau XML sederhana) dengan latar belakang `#CC1E1E1E` (Dark Gray transparan), sudut membulat `12dp`, ukuran ringkas, berisi tombol-tombol berikut:
- **`[Drag Handle (::)]`**: Area untuk menggeser posisi panel kontrol.
- **`[Edit / Lock]`**: Mengubah antara mode geser titik dan mode tembus sentuhan.
- **`[Hide / Show]`**: Menyembunyikan/menampilkan `CanvasOverlayView` (`View.GONE` vs `View.VISIBLE`).
- **`[Mode]`**: Siklus mode: *Auto-Reflect* -> *Manual 3-Point* -> *Edge-Marker Only*.
- **`[Calibrate]`**: Mengaktifkan/menonaktifkan mode geser sisi *Boundary Box*.
- **`[X]`**: Menghentikan `OverlayService` dan menutup overlay.

---

## 4. Visual & Canvas Specifications
- **Primary Line (Garis Utama A -> B):** Warna `#00E5FF` (Cyan), `strokeWidth = 3.5f`, menggunakan `DashPathEffect(floatArrayOf(15f, 10f), 0f)`.
- **Reflected Line (Garis Pantul B -> C):** Warna `#FFD600` (Kuning), `strokeWidth = 3.5f`, menggunakan `DashPathEffect(floatArrayOf(15f, 10f), 0f)`.
- **Boundary Box:** Warna `#80FFFFFF` (Putih semi-transparan), `strokeWidth = 2f`.
- **Touch Handles (Titik Geser A, B, C):**
  - Lingkaran luar radius `45f` dengan warna `#4000E5FF` (sebagai area sentuh jari).
  - Garis *crosshair* tipis di bagian tengah dan titik pusat radius `5f` berwarna `#FFFFFF` agar penempatan titik sangat presisi.

---

## 5. GitHub Actions CI/CD Specification (Wajib Dibuat)
Agar proyek ini dapat di-build menjadi file `.apk` langsung di GitHub tanpa Android Studio lokal, buat file workflow `.github/workflows/build-apk.yml` serta seluruh file *Gradle Wrapper* yang dibutuhkan:

1. **Konfigurasi Workflow (`.github/workflows/build-apk.yml`):**
   - Trigger: `on: [push, workflow_dispatch]`
   - Runner: `ubuntu-latest`
   - Steps:
     1. Checkout repository (`actions/checkout@v4`).
     2. Setup JDK 17 (`actions/setup-java@v4` dengan distribusi `temurin` dan cache `gradle`).
     3. Setup Android SDK (`android-actions/setup-android@v3`).
     4. Buat/gunakan Gradle Wrapper atau gunakan `gradle/actions/setup-gradle@v3` untuk menjalankan perintah `gradle assembleDebug` (sehingga tetap berjalan lancar meskipun file binary `gradle-wrapper.jar` belum ada di repo).
     5. Upload hasil APK menggunakan `actions/upload-artifact@v4` dengan nama `ScreenAngleRuler-Debug-APK` dari path `app/build/outputs/apk/debug/app-debug.apk`.

---

## 6. Expected Directory Structure
Pastikan seluruh file berikut dibuat secara lengkap tanpa *placeholder* yang terpotong:

```text
.
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/utility/screenprotractor/
│       │   ├── MainActivity.kt
│       │   ├── OverlayService.kt
│       │   ├── CanvasOverlayView.kt
│       │   ├── GeometryUtils.kt
│       │   └── PrefsManager.kt
│       └── res/
│           └── values/
│               └── strings.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 7. Instructions for AI Agent (Antigravity Execution Plan)
Kerjakan proyek ini hingga tuntas dengan langkah berurutan:
1. **Step 1 (Build System & CI/CD):** Buat `settings.gradle.kts`, `build.gradle.kts` (root & app), `gradle.properties`, dan `.github/workflows/build-apk.yml`. Pastikan menggunakan `gradle/actions/setup-gradle@v3` di GitHub Actions agar build tidak gagal meskipun `gradle-wrapper.jar` tidak di-commit.
2. **Step 2 (Manifest & Resources):** Buat `AndroidManifest.xml` (dengan permission `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`) dan `strings.xml`.
3. **Step 3 (Math & Storage Engine):** Buat `PrefsManager.kt` untuk menyimpan koordinat kalibrasi *Boundary Box* dan `GeometryUtils.kt` untuk perhitungan *ray-box intersection* serta vektor pantulan 2D.
4. **Step 4 (Views & Service):** Buat `CanvasOverlayView.kt` dan `OverlayService.kt` lengkap dengan panel kontrol mengambang bertema gelap yang bisa di-*drag*.
5. **Step 5 (Entry Activity):** Buat `MainActivity.kt` dengan tampilan gelap minimalis untuk manajemen izin dan tombol Start/Stop.