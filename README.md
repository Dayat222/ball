# Toolkit (Screen Overlay Utility)

A precision screen-overlay aiming guide for Android. It draws a target-ball reticle with direct
pocket rays and cushion bank rays on top of a calibrated table boundary, and it is fully
pass-through: touches never reach the overlay, they go straight to the app underneath.

> **v3.0 identity change:** the app is now `com.utility.toolkit` / "Toolkit", signed with a new
> keystore, and ships as an R8-minified release build. The first v3.0 install is a **separate app**
> from v2.x — uninstall the old "Screen Angle Ruler" afterwards.

---

## Features

- **Table-bounded pass-through overlay**
  - Full mode: the canvas window is cropped to the calibrated table plus `18dp` padding, so the power
    bar and the rest of the UI stay completely clear.
  - Short mode: a **fixed-size** capsule window moves with the target instead of being resized on
    every touch sample. A constant size keeps each drag update a cheap move transaction, which is
    what stopped the guides from breaking up while the ball point is dragged.
  - `FLAG_NOT_TOUCHABLE` while playing: every touch passes through to the game underneath.
  - Calibration mode temporarily expands the window to full screen and enables touch.
- **Single-point target mode (Point B)**
  - Ball-sized reticle ring (`8dp` – `30dp`, persisted).
  - **6 direct rays** to the four corner mouths and two side pockets, aimed with a mouth-gate solver
    (maximum clearance to both jaw tips) instead of fixed insets.
  - **10 cushion bank rays**, solved analytically against the ball-centre cushion plane and aimed at
    the same mouth-gate point the direct rays use. Optional full-length mode draws the incoming ray,
    the contact dot, and the reflected ray into the pocket.
  - In Short mode the reflected leg always keeps its full `85dp` length, even when the ball sits
    against a cushion, so the bank line stays usable as a parallel-direction reference. The incoming
    leg may be naturally short; the reflected one never is.
- **Anti-finger handle**
  - The draggable `42dp` crosshair sits `48dp` to the right and `36dp` above/below the real aim
    point, connected by a dashed tether, so the finger never covers the target.
  - Long-press flips the grip above/below the ball; auto-flip near the bottom edge.
  - Three-stage drag sensitivity (`0.28` / `0.65` / `1.0` px per frame) for fine aiming.
- **8-handle calibration** with auto-clamping, sanity checks, and a `Reset Bound` path.
  Verified state is persisted together with a calibration version so stale geometry is invalidated.
- **Compact dark toolbar** (`#CC1E1E1E`, 12dp corners): Short/Full ray length, Bank rays on/off,
  Calibrate, Settings, Close; collapsible to a draggable mini capsule with a quick ON/OFF switch.
- **Live styling** persisted across restarts: thickness (`0.5dp` – `15dp`), opacity (`25` – `255`),
  primary ray color, bank ray color (6 presets each plus a 360° hue slider).
- **In-app OTA updater** with manifest fallback and `FileProvider` installation.

> The old Auto-Reflect / Manual 3-Point / Edge-Marker modes and the 4-second Quick-Ghost were
> removed in v2.6. They were unreachable from the toolbar since v2.4; the single-point target mode
> is the only rendering mode left.

---

## Architecture

```text
OverlayService (foreground, type specialUse)
├── CanvasOverlayView   table-sized window (Full) or fixed capsule (Short), pass-through, renders rays
├── HandleView "B"      42dp drag handle window with anti-finger offset
└── FloatingControlView toolbar + minimized capsule (layout_floating_control.xml)
```

Coordinate model: all geometry lives in a **landscape screen space** (`max(w,h) × min(w,h)`).
When the canvas window is cropped, `CanvasOverlayView` translates drawing by `(-winX, -winY)` so
world coordinates stay identical between calibration and play.

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Permission gate, start/stop service, update check UI |
| `OverlayService.kt` | WindowManager setup, handle drag logic, toolbar wiring, persistence |
| `CanvasOverlayView.kt` | Ray/bank rendering, calibration handles and hit-testing |
| `AimGeometry.kt` | Pocket-gate solver, cushion reflection, rebound ratio |
| `PrefsManager.kt` | Boundary box, target point, styling and toggles in `SharedPreferences` |
| `AppUpdateManager.kt` | OTA manifest fetch, APK download, install intent |
| `HandleView.kt` | Minimal crosshair touch pad |
| `GeometryUtils.kt` | Distance helpers used by calibration hit-testing |

---

## Permissions

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the overlay above other apps |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the overlay alive on Android 14+ |
| `INTERNET` | In-app update check and APK download |
| `REQUEST_INSTALL_PACKAGES` | Launch the package installer for a downloaded update |

No `AccessibilityService`, no `MediaProjection`, no `QUERY_ALL_PACKAGES`, and no analytics or
third-party SDKs.

---

## Build

**Local**

```bash
./gradlew assembleDebug      # debug APK, useful for logcat
./gradlew assembleRelease    # shipped APK: R8 minified + resource shrink, signed
```

`app/build.gradle.kts` signs both build types with `app/release.keystore` so that a new build can
replace an installed one without a signature mismatch. The keystore and its passwords are committed
to the repository for that update continuity; treat the repository as sensitive. The keystore was
regenerated for v3.0, so it only matches builds from v3.0 onwards.

**CI (`.github/workflows/build-apk.yml`)**

1. Every push builds `assembleRelease` (R8 minified) with JDK 17 and Gradle 8.4.
2. The APK is staged as `app-latest.apk` and uploaded as the artifact `Toolkit-Release-APK`.
3. Pushes to `main` also publish/refresh the GitHub release tagged `latest` with **both**
   `app-latest.apk` and `version.json`.

The wrapper JAR is not committed; local builds need a Gradle installation, CI uses
`gradle/actions/setup-gradle`.

---

## Release / update flow

1. Bump `versionCode` in `app/build.gradle.kts` **and** `version.json` (currently `20` / `3.0.1`).
2. Update the `changelog` in `version.json`.
3. Push to `main` → CI builds and refreshes the `latest` release assets.
4. Installed apps read `version.json` from `raw.githubusercontent.com`; if that is unreachable they
   read the `version.json` asset of the latest release through the GitHub API.
5. An update is offered only when the manifest `versionCode` is greater than the installed one.

The manifest `versionCode` is the single source of truth. The floating `latest` tag is never
interpreted as a version number.

---

## Project structure

```text
.
├── .github/workflows/build-apk.yml
├── app/
│   ├── build.gradle.kts
│   ├── release.keystore
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/utility/toolkit/
│       │   ├── AimGeometry.kt
│       │   ├── AppUpdateManager.kt
│       │   ├── CanvasOverlayView.kt
│       │   ├── GeometryUtils.kt
│       │   ├── HandleView.kt
│       │   ├── MainActivity.kt
│       │   ├── OverlayService.kt
│       │   └── PrefsManager.kt
│       └── res/
│           ├── drawable/
│           ├── layout/
│           ├── mipmap-anydpi-v26/
│           └── values/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── version.json
```

`gabungan_kode_terbaru.txt` is a generated concatenation of the sources kept for offline review;
it is not compiled.
