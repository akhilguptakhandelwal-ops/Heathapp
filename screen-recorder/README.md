# FrameCast — mobile frame screen recorder

Android app. Phone ki screen record karta hai, aur video mein screen ke chaaron taraf ek phone ka frame (body, camera hole, side buttons, shadow, background) banata hai. Recording ke time right side par tool panel milta hai.

**Download:** [FrameCast.apk](../../../releases/download/framecast-latest/FrameCast.apk) (har push par GitHub Actions naya banata hai).

## Tools (right side panel)

| Button | Kaam |
|---|---|
| Arrow | Ungli kheencho — arrow banta hai |
| Square | Kheencho — box banta hai |
| Mouse | Angootha ghumao — cursor saath chalta hai, chhodo to wahin rukta hai. Jaldi tap = click. Daba ke rakho = sirf cursor ghoomta hai (click nahi). Do ungliyon se upar/neeche = scroll |
| ◎ ⌃ ⌄ | (Mouse mode mein) cursor ki jagah click / scroll up / scroll down |
| Colour, Undo, Clear | Arrow/box ka rang, last hatao, sab hatao |
| ■ | Stop — video Gallery → Movies/FrameCast mein |

Jo tool chalu hai use dobara dabao to tool band aur phone normal chalta hai. Timer kheench ke panel upar-neeche, timer par tap = panel chhota.

## Setup on the phone

1. APK install karein (Chrome → "Install unknown apps" allow).
2. App kholein → **Cursor aur tools → Turn on** → Accessibility → FrameCast cursor and tools → On.
   Android 13+ par "Restricted setting" aaye to: App info → ⋮ → *Allow restricted settings*, phir On karein.
3. Notification + Microphone allow karein, phir **Start recording**.

## How it works

- `RecorderService` — MediaProjection mirrors the screen into an `ImageReader` at exactly the size of the phone screen in the frame. Every 1/30 s, `FrameRenderer` draws background + phone + latest screen image onto the H.264 encoder's input surface. Mic audio goes to AAC; both go into one MP4 (`MuxerSink`).
- `ControlService` (accessibility) — owns three overlay windows: drawing layer (arrows, boxes, cursor), a touch layer that only takes touches while a tool is on, and the button panel. Clicks and scrolls are sent with `dispatchGesture` at the cursor; the touch layer steps aside for that moment so the tap reaches the app below.
- `MouseGesture` — tap vs hold vs drag vs two-finger scroll rules.

## Build

No Gradle needed:

```
sudo apt-get install aapt dalvik-exchange zipalign apksigner
./build.sh            # -> build/FrameCast.apk
test/run-tests.sh     # Robolectric tests + screenshots in test/target/shots/
```

Font: Manrope (SIL Open Font License, `assets/fonts/Manrope-OFL.txt`).
