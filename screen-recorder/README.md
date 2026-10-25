# FrameCast — mobile frame screen recorder

Android app. Records your phone screen with your voice. Two styles:

- **Phone frame** — the screen sits inside a phone (body, camera hole, side buttons, shadow, background); video 9:16 or 16:9.
- **Normal** — plain screen recording, same shape as your phone.

720p / 1080p. After you stop, a review screen opens: watch, **cut out any parts** (start, middle, end — as many as you like), **keep or remove the voice**, then **Save** or **Discard**.

**Download:** [FrameCast.apk](../../../releases/download/framecast-latest/FrameCast.apk) (rebuilt by GitHub Actions on every push).

## While recording

A small panel sits on the right edge. It is **not** in the video.

| Button | What it does |
|---|---|
| ✎ Draw | Your finger draws instead of tapping the app (the real screen stays visible at full quality): Arrow, Square or Pen; colour; Undo; Clear |
| White / Black | Opens a blank white page or a black board; write with your finger (pen is ready, dark ink / chalk) |
| Photo | Pick any picture; it opens full screen inside FrameCast to draw on, and always records |
| ▶ Live | Removes all marks, closes the freeze / board. Your phone works normally |
| Camera | Screenshot with the phone frame → Gallery → Pictures/FrameCast |
| ■ | Stop → review screen: Save, Trim & Save, or Discard (saved to Gallery → Movies/FrameCast) |

In Live mode nothing covers the screen, so tapping, scrolling and opening apps work as usual. Drag the panel from anywhere to put it anywhere, even 95% off the screen; tap the timer to fold it.

**My recordings** (main screen → Open folder): all FrameCast videos and screenshots, each with Play / Open, Share and Delete.

## Setup on the phone

1. Install the APK.
2. Open FrameCast → **Tools over every screen → Allow** ("Display over other apps").
3. Allow Notification and Microphone, then **Start recording**.

If the screen comes through black (an app like Groww blocks recording), the panel timer turns red and says **Blocked**. If the mic hears nothing (another app or a call is using it, or Microphone access is off in Settings), the app says so during recording and when saving.

## How it works

- `RecorderService` — MediaProjection mirrors the screen into an `ImageReader` sized like the phone screen in the frame. Every 1/30 s, `FrameRenderer` draws background + phone + latest screen image onto the H.264 encoder surface. Mic → AAC (`AudioEncoder`); both go into one MP4 (`MuxerSink`).
- `ReviewActivity` + `Trimmer` — after Stop the video waits in app storage (never lost; it reopens next time) until you Save or Discard. Cutting joins the kept parts without re-encoding; each kept part starts at the nearest key frame (≤ 1 s early).
- `OverlayController` — the panel ("display over other apps"). Draw adds a clear full-screen `InkView` that takes the finger and shows the marks (the real screen shows through, no copied picture); Live removes it.
- Panel kept out of the video: `PanelMask` fills the panel area of each captured image from the pixels next to it; then the marks are painted straight into the frame, so marks under the panel stay.

## Build

No Gradle needed:

```
sudo apt-get install aapt dalvik-exchange zipalign apksigner
./build.sh            # -> build/FrameCast.apk
test/run-tests.sh     # Robolectric tests + screenshots in test/target/shots/
```

Font: Manrope (SIL Open Font License, `assets/fonts/Manrope-OFL.txt`).
