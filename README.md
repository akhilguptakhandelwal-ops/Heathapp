# SlideVox Studio

Phone se finance / explainer videos banane ki app. Images daalo, script paste karo, landscape mein record karo — voice ke saath zoom, tick, circle, arrow sab record hota hai. Phir cut karo, music lagao, export karo.

## Kaise chalayein

Yeh ek installable web app (PWA) hai — kisi build step ki zarurat nahi.

1. GitHub repo → **Settings → Pages → Source: GitHub Actions** select karein.
2. Har push par `.github/workflows/pages.yml` app ko deploy kar dega. Link milega: `https://<username>.github.io/Heathapp/`
3. Phone par link kholein (Android: Chrome, iPhone: Safari) → menu → **Add to Home screen**. Ab yeh normal app ki tarah full screen chalegi.

Local testing: `npx http-server -p 8080` then open `http://localhost:8080`.

## Workflow

1. **New project** → **Add images** (ek saath kai images, order mein). ◀ ▶ se order badlein, ⟳ se rotate.
2. **Add script** → text paste karein (teleprompter ke liye).
3. **Create video & record voice** → phone landscape, full screen.
   - Left side: Record / Stop, Pause, mic level, previous / next image.
   - Right side: Tools (pen, highlighter, tick, cross, circle, square, arrow, line, laser pointer, move), colour & size, undo, **delete all marks**, rotate, fit, script on/off.
   - Do ungliyon se zoom in / out / move — yeh bhi record hota hai.
   - Script panel sirf aapko dikhta hai, video mein nahi aata. Speed −/+, text size A−/A+, ungli se upar-niche scroll, ⇅ se panel upar/niche.
4. **Editor**
   - Timeline par tap = wahan jao. Long-press + drag = part select. Ya **Mark In / Mark Out**.
   - **Cut video + voice**, **Delete voice only**, **Voice volume**, **Music volume** (sirf selected part ke liye).
   - Music add / replace, volume, loop, fade out, “start at playhead”.
   - **Who stays on top?** Voice on top (bolte waqt music apne aap dheemi), Music on top, ya Equal.
   - Har edit ko Undo / Redo / Restore kar sakte hain. Original recording kabhi kharab nahi hoti.
   - **Continue recording** se usi take ke aage aur record karein.
5. **Export** → MP4 (ya WebM jo browser support kare). Save ya Share (WhatsApp, YouTube, Gallery).

## Technical notes

- Recording pixels capture nahi karti — har action (slide change, zoom, stroke) ek chhota event ban kar save hota hai, aur voice alag audio file. Isliye cuts aur edits lossless hain aur export full 1080p mein hota hai.
- Sab data phone ke andar IndexedDB mein rehta hai. Koi server nahi.
- Export real time mein hota hai (2 min ki video = ~2 min export). Export ke dauran screen on rakhein.

Code: `js/recorder.js` (studio), `js/editor.js` (timeline + export), `js/renderer.js` (stage drawing + replay), `js/mix.js` (cuts, volume, music mix).
