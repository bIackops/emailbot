# DSi Touch Screen Calibration (Android)

An Android fan recreation of the Nintendo DSi **System Settings → Touch Screen**
calibration menu. It is not affiliated with Nintendo, and it uses no Nintendo
assets: every graphic is drawn in code, and the sounds are system tones.

## What it does

- Draws two 256×192 "screens" stacked like a DSi (top and touch screen) with a
  hinge between them. They are scaled up with nearest-neighbour filtering to keep
  the pixel look.
- **System Settings** screen with a **Touch Screen** button and **Back**.
- **Calibration:** touch the center of three pulsing marks in turn (top-left,
  bottom-right, bottom-left). Touches that land too far from a mark fail with a
  retry prompt.
- **Check:** after calibrating, a mark follows your finger so you can check the
  result. Tap **Retry** to redo it or **OK** to save it.
- The calibration is a real affine correction. It is saved and applied to every
  later touch in the app.

## Getting the APK

Every push runs GitHub Actions (`.github/workflows/build.yml`), which runs the
unit tests and builds a debug APK. Open the workflow run and download the
`dsi-touch-calibration-apk` artifact, then install it on your phone. You may
need to allow installs from unknown sources.

## Building locally

Requirements: JDK 17 and the Android SDK (API 35).

```sh
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # calibration math tests
```

## Tweaking

- On-screen text: `app/src/main/res/values/strings.xml`
- Colors, layout, target positions, tolerance: the constants at the bottom of
  `app/src/main/java/com/dsicalib/app/DsiView.kt`
