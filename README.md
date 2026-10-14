# Screen Angle Ruler (Screen Protractor)

A precision geometric screen overlay ruler and vector 2D reflection visualizer ($\theta_i = \theta_r$) for Android. Built with a safe, non-intrusive architecture and lightweight standard Android SDK components.

---

## Features

- **Multi-Window Overlay Service:**
  - **Canvas Overlay (`MATCH_PARENT` x `MATCH_PARENT`):** Full-screen transparent canvas rendering vector lines, angle calculations, and boundary box limits.
  - **Draggable Floating Control Bar (`WRAP_CONTENT` x `WRAP_CONTENT`):** Compact dark UI (`#CC1E1E1E`, 12dp rounded corners) positioned anywhere on screen.
- **3 Interaction Modes:**
  - **Edit Mode:** Touch and drag points A, B, C or calibrate the boundary box.
  - **Lock Mode (Pass-Through):** Adds `FLAG_NOT_TOUCHABLE` to allow all touch events to pass directly into the underlying apps.
  - **Hidden Mode:** Quick-hide overlay (`View.GONE`) for unobstructed screen view.
- **3 Measurement & Reflection Modes:**
  - **Auto-Reflection ($\theta_i = \theta_r$):** Ray-box intersection from Point A through Point B hitting calibrated boundary walls, reflecting automatically with angle computation.
  - **Manual 3-Point:** Freely reposition Start (A), Pivot (B), and End (C) to measure angles ($\angle ABC$).
  - **Edge-Marker Only:** Minimalist mode displaying indicator dots ($16\text{dp}$) directly at the boundary wall bounce points without obstructing the middle of the screen.
- **Persistent Calibration:** Boundary box coordinates and handle positions are persisted in `SharedPreferences`.
- **Strict Offline & Safe Policy:**
  - 100% offline (No `android.permission.INTERNET`).
  - No `AccessibilityService`, `MediaProjection`, or `QUERY_ALL_PACKAGES`.
  - Android 14 (API 34) compliant using `FOREGROUND_SERVICE_SPECIAL_USE`.

---

## Project Structure

```text
.
├── .github/
│   └── workflows/
│       └── build-apk.yml              # CI/CD workflow to compile debug APK
├── app/
│   ├── build.gradle.kts               # App-level build config (Kotlin DSL)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml        # Permissions and service declarations
│       ├── java/com/utility/screenprotractor/
│       │   ├── MainActivity.kt        # Entry dashboard & permission manager
│       │   ├── OverlayService.kt      # Foreground service & floating control bar
│       │   ├── CanvasOverlayView.kt   # High-performance transparent canvas
│       │   ├── GeometryUtils.kt       # Ray-box intersection & reflection engine
│       │   └── PrefsManager.kt        # Calibration & coordinates persistence
│       └── res/
│           ├── drawable/              # Vector icons and rounded shapes
│           ├── layout/                # Activity and floating toolbar layouts
│           ├── mipmap-anydpi-v26/     # Adaptive app icons
│           └── values/                # Strings, colors, and dark theme
├── build.gradle.kts                   # Root build script
├── settings.gradle.kts                # Repositories and project settings
├── gradle.properties                  # JVM args and AndroidX configuration
└── gradlew / gradlew.bat              # Gradle wrapper scripts
```

---

## Build with GitHub Actions

This repository is pre-configured with `.github/workflows/build-apk.yml`. You don't need Android Studio installed locally to compile the APK:

1. **Push to GitHub:**
   ```bash
   git init
   git add .
   git commit -m "Initial commit: Screen Angle Ruler"
   git branch -M main
   git remote add origin <YOUR_GITHUB_REPO_URL>
   git push -u origin main
   ```

2. **Automatic Compilation:**
   - GitHub Actions will trigger automatically on `push`.
   - The workflow sets up JDK 17, Android SDK 34, and Gradle 8.4.
   - It runs `gradle assembleDebug` to produce `app-debug.apk`.

3. **Download the APK:**
   - Go to your repository on GitHub.
   - Click on the **Actions** tab.
   - Select the latest workflow run.
   - Download the artifact named **`ScreenAngleRuler-Debug-APK`**.
