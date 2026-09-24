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

On your Android phone, open this link to download the app:

https://github.com/bIackops/emailbot/releases/download/latest/DSi-Touch-Calibration.apk

Then open the downloaded file to install it. If Android blocks it, allow your
browser to "install unknown apps" when prompted.

The link always points at the newest build: every push rebuilds the app on
GitHub Actions (`.github/workflows/build.yml`) and replaces the file.

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
