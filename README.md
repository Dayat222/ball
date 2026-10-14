# Screen Angle Ruler (Screen Protractor)

A precision geometric screen overlay ruler and vector 2D reflection visualizer ($\theta_i = \theta_r$) for Android. Built with a safe, non-intrusive architecture and lightweight standard Android SDK components.

---

## Features

- **Multi-Window Overlay Service:**
  - **Always Pass-Through Canvas Overlay (`MATCH_PARENT` x `MATCH_PARENT`):** Full-screen transparent canvas permanently set with `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`. All touches on screen pass directly through to underlying apps.
  - **Independent Floating Handles (42dp x 42dp):** Precision `HandleView` windows for Point A, Point B, and Point C with real-time vector ray updates as you drag them.
  - **Draggable Floating Control Bar (`WRAP_CONTENT` x `WRAP_CONTENT`):** Compact dark UI (`#CC1E1E1E`, 12dp rounded corners) with Quick-Ghost, visibility toggles, and mode switches.
- **Instant Controls & Quick-Ghost:**
  - **Interactive Boundary Calibration:** Tap `[Calib]` to unlock boundary dragging with 4 corner pins. Tap `[Save Bound]` to lock the canvas back to pass-through mode and persist coordinates.
  - **Real-Time Line Customization (`[⚙]`):** Sub-panel with sliders for line thickness (`0.5dp` – `15dp`) and line opacity (`10%` – `100%`), saved automatically.
  - **Quick-Ghost (Auto-Hide 4s):** Temporarily hides all overlay graphics and handles for 4 seconds for unobstructed execution, auto-restoring automatically.
  - **Direct-Touch Interaction:** Move handles A, B, C freely at any time without having to toggle an Edit/Lock mode.
  - **Hidden Mode:** Toggle overlay visibility on and off instantly.
- **3 Measurement & Reflection Modes:**
  - **Auto-Reflection ($\theta_i = \theta_r$):** Ray-box intersection from Point A through Point B hitting calibrated boundary walls, reflecting automatically with angle computation.
  - **Manual 3-Point:** Freely reposition Start (A), Pivot (B), and End (C) to measure angles ($\angle ABC$).
  - **Edge-Marker Only:** Minimalist mode displaying indicator dots ($16\text{dp}$) directly at the boundary wall bounce points without obstructing the middle of the screen.
- **Persistent Calibration:** Boundary box coordinates, line styles, and handle positions are persisted in `SharedPreferences`.
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
│       │   ├── CanvasOverlayView.kt   # High-performance transparent canvas (pass-through)
│       │   ├── GeometryUtils.kt       # Ray-box intersection & reflection engine
│       │   ├── HandleView.kt          # Independent 42dp floating touch handle
│       │   ├── MainActivity.kt        # Entry dashboard & permission manager
│       │   ├── OverlayService.kt      # Foreground service & multi-window manager
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
