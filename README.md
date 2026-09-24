# DSi Touch Screen Calibration (Android)

An Android fan recreation of the Nintendo DSi **System Settings → Touch Screen**
calibration menu. It is not affiliated with Nintendo, and it uses no Nintendo
assets: every graphic is drawn in code, and the sounds are system tones.

## What it does

- One full-height screen in the DS System Settings style: grid background, header
  bar, white panels, rounded gradient buttons. It is drawn at a low native
  resolution (about 256 px wide) and scaled up with nearest-neighbour filtering, so
  it keeps the pixel look.
- **System Settings** menu with **Touch Screen** (calibrate) and **Records**.
- **Calibration:** touch the center of five pulsing marks in turn (the four corners,
  then the center). Progress dots show how far along you are. A touch more than
  8 mm from its mark fails with a retry prompt.
- **Check:** a mark follows your finger, with X/Y readout. **Retry** redoes it,
  **OK** saves it.
- **Results:** a grade (S/A/B/C/D) and precision score out of 100, a touch map
  showing where you hit each mark, and these stats:
  - Average error and worst point (in mm)
  - Speed (average time to hit each mark)
  - Steadiness (how far your finger wandered while held down)
  - Consistency (error left over after the correction)
  - Tendency (whether you tend to touch high, low, left or right)
  - The correction applied (shift, scale, rotation)
- **Records:** number of calibrations, best and last score, best average error, and
  a bar chart of your last 10 scores.
- The saved calibration is a real least-squares correction, applied to every later
  touch in the app.

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
- Colors, layout, tolerance: the constants at the bottom of
  `app/src/main/java/com/dsicalib/app/DsiView.kt`
- Scoring and grade thresholds: `app/src/main/java/com/dsicalib/app/CalibrationStats.kt`
