# SlideVox Studio

Phone se finance / explainer videos banane ki app. Images daalo, script paste karo, landscape mein record karo — voice ke saath zoom, tick, circle, arrow sab record hota hai. Phir cut karo, music lagao, export karo.

## Kaise chalayein

Yeh ek installable web app (PWA) hai — kisi build step ki zarurat nahi.

1. GitHub repo → **Settings → Pages → Source: GitHub Actions** select karein.
2. Har push par `.github/workflows/pages.yml` app ko deploy kar dega. Link milega: `https://<username>.github.io/Heathapp/`
3. Phone par link kholein (Android: Chrome, iPhone: Safari) → menu → **Add to Home screen**. Ab yeh normal app ki tarah full screen chalegi.

Local testing: `npx http-server -p 8080` then open `http://localhost:8080`.

## Workflow

1. **New project** → **Images** / **Video / audio** add karein. ☰ pakad ke drag karke order badlein (portrait mein), ⟳ se rotate.
2. **Script** → text paste karein (teleprompter ke liye).
3. **Video shape**: Landscape 16:9 (YouTube) ya Portrait 9:16 (Shorts / Reels).
4. **Create video & record voice** → full screen.
   - **Swipe ← →** se next / previous image (button ki zarurat nahi). Pen ke saath tez flick, Move tool se normal swipe, ya do ungliyon se swipe. Tools menu mein on/off.
   - Video/audio clip par pahunchte hi recording apne aap pause hoti hai; final video mein clip wahi chalegi. Aage swipe karte hi recording phir chalu.
   - Left side: Record / Stop, Pause, mic level, previous / next image.
   - Right side: Tools (pen, highlighter, tick, cross, circle, square, arrow, line, laser pointer, move), colour & size, undo, **delete all marks**, rotate, fit, script on/off.
   - Do ungliyon se zoom in / out / move — yeh bhi record hota hai.
   - Script panel sirf aapko dikhta hai, video mein nahi aata. Speed −/+, text size A−/A+, ungli se upar-niche scroll, ⇅ se panel upar/niche.
5. **Editor**
   - Timeline par tap = wahan jao. Long-press + drag = part select. Ya **Mark In / Mark Out**.
   - **Cut video + voice**, **Delete voice only**, **Voice volume**, **Music volume** (sirf selected part ke liye).
   - Music add / replace, volume, loop, fade out, “start at playhead”.
   - **Who stays on top?** Voice on top (bolte waqt music apne aap dheemi), Music on top, ya Equal.
   - Har edit ko Undo / Redo / Restore kar sakte hain. Original recording kabhi kharab nahi hoti.
   - **Continue recording** se usi take ke aage aur record karein.
   - Clips ki list Edits mein — kisi clip ko Skip / Include kar sakte hain.
6. **Export** → quality chunein: Low 480p · Medium 720p · High 1080p · YouTube 1440p · Professional 4K. Fast mode mein video phone par seedha banti hai (still slides wale hisse bahut tez). Phir Play full screen, Save ya Share.
7. **Intro & outro** (Home screen): ek clip ek baar save karein, Start / End mark karein — har export mein apne aap pehle ya aakhir mein judegi.

## Technical notes

- Recording pixels capture nahi karti — har action (slide change, zoom, stroke) ek chhota event ban kar save hota hai, aur voice alag audio file. Isliye cuts aur edits lossless hain aur export full 1080p mein hota hai.
- Sab data phone ke andar IndexedDB mein rehta hai. Koi server nahi.
- Fast export WebCodecs + [Mediabunny](https://mediabunny.dev) (MPL-2.0, `js/vendor/`) se hota hai. Jo frame badalta nahi use dobara encode nahi karte, isliye slides wali video apni length se kai guna tez banti hai. Purane browser mein real-time fallback.
- Export ke dauran screen on rakhein.

Code: `js/recorder.js` (studio), `js/editor.js` (timeline), `js/export.js` (final video), `js/renderer.js` (stage drawing + replay), `js/mix.js` (cuts, volume, music mix), `js/media.js` (clips).

## Feature list (har update mein sab chalu rehne chahiye)

`npm test` (aur GitHub par "Feature check") inn sab ko asli browser mein chala kar check karta hai:

- **Home:** projects, install card, version + update check, settings (swipe, background, countdown, auto-save), Intro & outro library (Start / End / Off)
- **Project:** rename, images, video/audio clips, White/Black blank page, item menu (rotate, blank before/after, delete), ☰ drag order, sort, Select → Select all → multi delete, script + word count, 16:9 / 9:16
- **Recorder:** record / pause / stop, countdown, teleprompter (speed, size, move, auto-scroll, hide), voice effect chips, pen, highlighter, line, arrow, circle, square, tick, cross, laser, colours, sizes, undo, delete all marks, zoom (pinch / wheel), pan, fit, rotate, swipe (pen flick, move tool, two fingers, on script), prev / next / arrow keys, + Page while recording with auto pen colour, clip auto-pause, continue recording
- **Editor:** preview play, full-screen preview, timeline zoom / fit / tap / long-press select, Video / Voice / Music tracks, ✂ cut start/end, Delete, Volume, Undo / Redo / Restore, clip Skip / Include, voice effects + fine tune, voice volume, music (add, volume, start, loop, fade, priority, dip), rename / delete take
- **Export:** Low / Medium / High / YouTube / 4K, saved clips at Start / After #N / End, fast encoder + real-time fallback, play full screen, Save (Documents/SlideVox), Share
