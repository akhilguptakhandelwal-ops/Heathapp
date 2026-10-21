# FrameCast — mobile frame screen recorder

Android app. Phone ki screen record karta hai, aur video mein screen ke chaaron taraf ek phone ka frame (body, camera hole, side buttons, shadow, background) banata hai. Recording ke time right side par tool panel milta hai.

**Download** (har push par GitHub Actions naya banata hai):

- [FrameCast.apk](../../../releases/download/framecast-latest/FrameCast.apk) — normal install hota hai. Phone frame, arrow, square, cursor, screenshot, recording. Cursor sirf dikhane ke liye (asli click ke liye tool band karke normal tap).
- [FrameCast-Full.apk](../../../releases/download/framecast-latest/FrameCast-Full.apk) — upar sab + cursor se asli click / scroll. Isme Accessibility service hai, isliye Play Protect ("sensitive data" warning) bahar se install rokta hai. Install ke liye: Play Store → profile photo → Play Protect → ⚙ → "Scan apps with Play Protect" Off → install → wapas On. Phir App info → ⋮ → Allow restricted settings → Accessibility mein On.

Dono ka package same hai — Full, normal wale ke upar install ho jata hai.

## Tools (right side panel)

| Button | Kaam |
|---|---|
| Arrow | Ungli kheencho — arrow banta hai |
| Square | Kheencho — box banta hai |
| Mouse | Angootha ghumao — cursor saath chalta hai, chhodo to wahin rukta hai. Jaldi tap = click (Full: asli click; normal: video mein click ka nishaan). Daba ke rakho = sirf cursor ghoomta hai. Full mein do ungliyon se upar/neeche = scroll |
| ◎ ⌃ ⌄ | (Sirf Full, Mouse mode mein) cursor ki jagah click / scroll up / scroll down |
| Camera | Screenshot phone frame ke saath → Gallery → Pictures/FrameCast |
| Colour, Undo, Clear | Arrow/box ka rang, last hatao, sab hatao |
| ■ | Stop — video Gallery → Movies/FrameCast mein |

**Panel video mein nahi aata** — sirf screen, arrows, boxes aur cursor record hote hain. Jo tool chalu hai use dobara dabao to tool band aur phone normal chalta hai. Timer kheench ke panel upar-neeche, timer par tap = panel chhota.

## Setup on the phone

1. APK install karein (Chrome → "Install unknown apps" allow).
2. App kholein → **Tools har screen ke upar → Allow** ("Display over other apps").
3. (Sirf Full APK) **Asli click aur scroll → Turn on** → Accessibility → FrameCast cursor and tools → On. "Restricted setting" aaye to: App info → ⋮ → *Allow restricted settings*.
4. Notification + Microphone allow karein, phir **Start recording**.

## How it works

- `RecorderService` — MediaProjection mirrors the screen into an `ImageReader` at exactly the size of the phone screen in the frame. Every 1/30 s, `FrameRenderer` draws background + phone + latest screen image onto the H.264 encoder's input surface. Mic audio goes to AAC; both go into one MP4 (`MuxerSink`).
- `OverlayController` — two overlay windows: a full-screen layer (arrows, boxes, cursor; takes touches only while a tool is on) and the button panel. Normal APK: "display over other apps" windows, the full-screen one at 80% opacity because Android 12+ only lets taps pass through windows up to that. Full APK: accessibility overlays, and clicks / scrolls are sent with `dispatchGesture` at the cursor (`ControlService`); the layer steps aside for that moment so the tap reaches the app below.
- Normal vs Full: same code; `build.sh FULL=1` adds `manifest-full-service.xml` to the manifest.
- Panel kept out of the video: `PanelMask` fills the panel's area in every captured image by stretching the screen next to it (median of a few columns, so thin text/lines don't streak). Then `FrameRenderer` paints the marks and cursor from `InkView` straight into the frame, so marks under the panel stay, at full colour.
- `MouseGesture` — tap vs hold vs drag vs two-finger scroll rules.

## Build

No Gradle needed:

```
sudo apt-get install aapt dalvik-exchange zipalign apksigner
./build.sh            # -> build/FrameCast.apk
FULL=1 ./build.sh     # -> build/FrameCast-Full.apk
test/run-tests.sh     # Robolectric tests + screenshots in test/target/shots/
```

Font: Manrope (SIL Open Font License, `assets/fonts/Manrope-OFL.txt`).
