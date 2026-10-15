# Screen Angle Ruler (Screen Protractor)

A precision geometric screen overlay ruler and vector 2D reflection visualizer ($\theta_i = \theta_r$) for Android. Built with a safe, non-intrusive architecture and lightweight standard Android SDK components.

---

## Features

- **Multi-Window Overlay Service:**
  - **Always Pass-Through Canvas Overlay (`MATCH_PARENT` x `MATCH_PARENT`):** Full-screen transparent canvas permanently set with `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`. All touches on screen pass directly through to underlying apps.
  - **Clean View:** Boundary box and calibration pins are strictly visible only during calibration mode. Once saved, the border is completely hidden from view while retaining active reflection calculations.
  - **Minimalist Plus-Sign Handles (`+`) & Live Alpha Sync:** Independent `42dp x 42dp` transparent touch pads displaying a clean center crosshair (`+`) without circle borders or labels, with transparency and color synchronized in real-time with line settings.
  - **1-Point Target Mode with 6 Direct & 10 Mirror Bank Rays (Plain Lines):**
    - **Single-Point Mode:** Activating 6-Line mode automatically hides handles A and C, displaying only Point B with its stem tether for an uncluttered target view.
    - **6 Direct Pocket Rays:** Clean plain lines (`85dp`) pointing directly to all 6 table pockets from Point B's center white dot (`6dp`), using the user's primary line color.
    - **10 Mirror Cushion Bank Rays:** Clean plain rays (`64dp`) pointing towards 10 virtual mirror pockets across all 4 cushions (Top, Bottom, Right, Left), highlighted in contrasting amber/gold without tip dots or text labels.
  - **Collapsible Mini Capsule Bar:** Tap `[−]` to collapse toolbar into a draggable mini capsule containing quick `[ON]` / `[OFF]` switch and `[+]` expand button.
  - **Draggable Floating Control Bar (`WRAP_CONTENT` x `WRAP_CONTENT`):** Compact dark UI (`#CC1E1E1E`, 12dp rounded corners) with Quick-Ghost, 6-Line toggle, calibration controls, and line sliders.
- **Instant Controls & Quick-Ghost:**
  - **Landscape & Portrait 8-Handle Calibration:** 8 draggable handles (4 corners + 4 edge centers) with auto-clamping preventing borders from overflowing screen bounds, plus a `[Reset Bound]` button.
  - **Real-Time Line Customization (`[⚙]`):** Sub-panel with sliders for line thickness (`0.5dp` – `15dp`), line opacity (`10%` – `100%`), and **Line Color Picker** (6 preset color swatches + 360° Hue spectrum slider with live preview).
  - **Quick-Ghost (Auto-Hide 4s):** Temporarily hides all overlay graphics and handles for 4 seconds for unobstructed execution, auto-restoring automatically.
  - **Direct-Touch Interaction:** Move handles A, B, C freely at any time without having to toggle an Edit/Lock mode.
  - **Quick ON/OFF Switch:** Instant one-tap toggle on minimized bar (`[ON]` / `[OFF]`) for 100% clean screen during shots.
- **3 Measurement & Reflection Modes:**
  - **Auto-Reflection ($\theta_i = \theta_r$):** Ray-box intersection from Point A through Point B hitting calibrated boundary walls, reflecting automatically with angle computation.
  - **Manual 3-Point:** Freely reposition Start (A), Pivot (B), and End (C) to measure angles ($\angle ABC$).
  - **Edge-Marker Only:** Minimalist mode displaying indicator dots ($16\text{dp}$) directly at the boundary wall bounce points without obstructing the middle of the screen.
- **Persistent Calibration:** Boundary box coordinates, line styles (thickness, opacity, color), 6-line toggle, and handle positions are persisted in `SharedPreferences`.
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
