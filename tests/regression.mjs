// Full feature check for SlideVox Studio. Runs the real app in headless
// Chromium (fake microphone) and ticks off every feature. Any failure makes
// the command exit with code 1, so CI turns red.
//
//   node tests/regression.mjs
//
// Needs the `playwright` package and its Chromium.
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'slidevox-test-'));
const SHOTS = process.env.SHOTS_DIR || path.join(TMP, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });

// ---------- tiny static server ----------
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json', '.json': 'application/json' };
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (p.endsWith('/')) p += 'index.html';
  const f = path.join(ROOT, p);
  if (!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': TYPES[path.extname(f)] || 'application/octet-stream', 'cache-control': 'no-store' });
  fs.createReadStream(f).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const URL0 = `http://127.0.0.1:${server.address().port}/`;

// ---------- results ----------
const results = [];
let page;
async function check(name, fn) {
  try {
    const note = await fn();
    results.push({ name, ok: true });
    console.log(`  ✓ ${name}${note ? ` — ${note}` : ''}`);
  } catch (e) {
    results.push({ name, ok: false, err: e.message.split('\n')[0] });
    console.log(`  ✗ ${name} — ${e.message.split('\n')[0]}`);
    try { await page.screenshot({ path: path.join(SHOTS, `fail-${results.length}.png`) }); } catch { /* ignore */ }
  }
}
const expect = (cond, msg) => { if (!cond) throw new Error(msg); };

const browser = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });

// ---------- fixtures (made fresh each run) ----------
const F = (n) => path.join(TMP, n);
{
  const p = await browser.newPage();
  const cols = ['#2d4a3e', '#5a3d2b', '#3b3550'];
  for (let i = 0; i < 3; i++) {
    await p.setViewportSize({ width: 1600, height: 1000 });
    await p.setContent(`<body style="margin:0;background:${cols[i]};color:#fff;font:bold 120px sans-serif;display:flex;align-items:center;justify-content:center;height:100vh">Slide ${i + 1}</body>`);
    await p.screenshot({ path: F(`slide${i + 1}.png`) });
  }
  await p.goto(URL0 + 'index.html');
  const b64 = await p.evaluate(async () => {
    const cv = document.createElement('canvas'); cv.width = 640; cv.height = 360; const c = cv.getContext('2d');
    const ac = new AudioContext(); const o = ac.createOscillator(); const d = ac.createMediaStreamDestination(); o.connect(d); o.start();
    const mr = new MediaRecorder(new MediaStream([...cv.captureStream(30).getVideoTracks(), ...d.stream.getAudioTracks()]), { mimeType: 'video/webm;codecs=vp8,opus' });
    const ch = []; mr.ondataavailable = (e) => ch.push(e.data); const done = new Promise((r) => { mr.onstop = r; }); mr.start();
    const t0 = performance.now();
    await new Promise((res) => { const f = () => { const t = (performance.now() - t0) / 1000; c.fillStyle = '#7a2e1f'; c.fillRect(0, 0, 640, 360); c.fillStyle = '#fff'; c.font = 'bold 60px sans-serif'; c.fillText('CLIP ' + t.toFixed(1), 150, 200); if (t < 2.5) requestAnimationFrame(f); else res(); }; f(); });
    mr.stop(); await done;
    const u = new Uint8Array(await new Blob(ch).arrayBuffer()); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s);
  });
  fs.writeFileSync(F('clip.webm'), Buffer.from(b64, 'base64'));
  await p.close();
  const sr = 44100, n = sr * 5, buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write('WAVE', 8); buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sr, 24); buf.writeUInt32LE(sr * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin(2 * Math.PI * 220 * i / sr) * 0.3 * 32767), 44 + i * 2);
  fs.writeFileSync(F('music.wav'), buf);
}

const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, permissions: ['microphone'] });
page = await ctx.newPage();
const p = page;
const errors = [];
p.on('pageerror', (e) => errors.push(e.message));
p.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const pick = async (trigger, files) => { const [fc] = await Promise.all([p.waitForEvent('filechooser'), trigger()]); await fc.setFiles(files); };
const portrait = () => p.setViewportSize({ width: 390, height: 844 });
const landscape = () => p.setViewportSize({ width: 844, height: 390 });
const stage = () => p.locator('canvas.stage').boundingBox();
const drag = async (x0, y0, x1, y1, steps = 12, delay = 20) => {
  await p.mouse.move(x0, y0); await p.mouse.down();
  for (let i = 1; i <= steps; i++) { await p.mouse.move(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps); if (delay) await p.waitForTimeout(delay); }
  await p.mouse.up();
};
const tool = async (id) => { await p.click('.rail-right .rail-btn >> nth=0'); await p.click(`.fly-item[data-tool="${id}"]`); };

console.log('SlideVox regression check');
await p.goto(URL0);
await p.evaluate(() => { localStorage.clear(); localStorage.setItem('slidevox.settings', JSON.stringify({ countdown: false })); });
await p.reload();

// ---------- Home ----------
console.log('Home');
await check('Home screen loads with install card and version', async () => {
  await p.waitForSelector('text=New project');
  expect(await p.isVisible('.install-card:not(.hidden)'), 'install card missing');
  expect((await p.textContent('.version-foot')).includes('Web app'), 'version missing');
});
await check('Settings: swipe, background, countdown, auto-save, update check', async () => {
  await p.click('[title="Settings"]');
  for (const t of ['Swipe on the image', 'Background behind images', '3-2-1 countdown', 'Save every exported video', 'Version']) expect(await p.isVisible(`.modal >> text=${t}`), `missing: ${t}`);
  await p.click('.modal .btn.primary');
});
await check('Intro / outro library: add clip, set Start/End/Off', async () => {
  await pick(() => p.click('text=Add clip'), [F('clip.webm')]);
  await p.waitForSelector('.lib-row');
  await p.click('.lib-row .seg button >> text=Start');
  await p.waitForSelector('.lib-row >> text=ticked, at start');
  await p.click('.lib-row .seg button >> text=End');
});

// ---------- Project ----------
console.log('Project');
await check('Create project', async () => {
  await p.click('text=New project'); await p.fill('.modal input', 'Regression'); await p.click('.modal .btn.primary');
  await p.waitForSelector('.add-row');
});
await check('Rename project', async () => {
  await p.click('.title-btn'); await p.fill('.modal input', 'Regression test'); await p.click('.modal .btn.primary');
  await p.waitForSelector('.title-btn >> text=Regression test');
});
await check('Add images (multiple, in order)', async () => {
  await pick(() => p.click('.add-row .btn >> text=Images'), [F('slide1.png'), F('slide2.png'), F('slide3.png')]);
  await p.waitForSelector('.item-row >> nth=2');
});
await check('Add video clip to the sequence', async () => {
  await pick(() => p.click('.add-row .btn >> text=Video / audio'), [F('clip.webm')]);
  await p.waitForSelector('.item-row >> nth=3');
  expect((await p.textContent('.item-row >> nth=3')).includes('Video clip'), 'not a clip');
});
await check('Add audio clip to the sequence', async () => {
  await pick(() => p.click('.add-row .btn >> text=Video / audio'), [F('music.wav')]);
  await p.waitForSelector('.item-row >> nth=4');
  expect((await p.textContent('.item-row >> nth=4')).includes('Audio clip'), 'not audio');
});
await check('Blank page (white) from the top button', async () => {
  await p.click('.add-row .btn >> text=Blank page'); await p.click('.modal .btn.white-btn');
  await p.waitForSelector('.item-row >> nth=5');
});
await check('Item menu: rotate, add black page before, delete', async () => {
  await p.click('.item-row >> nth=0 >> [title="More"]'); await p.click('.menu-list .btn >> text=Rotate');
  await p.click('.item-row >> nth=1 >> [title="More"]'); await p.click('.menu-list .btn >> text=Add blank page before'); await p.click('.modal .btn.black-btn');
  await p.waitForSelector('.item-row >> nth=6');
  await p.click('.item-row >> nth=0 >> [title="More"]'); await p.click('.menu-list .btn >> text=Rotate');
  await p.click('.item-row >> nth=0 >> [title="More"]'); await p.click('.menu-list .btn >> text=Rotate');
  await p.click('.item-row >> nth=0 >> [title="More"]'); await p.click('.menu-list .btn >> text=Rotate');
  return (await p.locator('.item-name').allTextContents()).join(', ');
});
await check('Drag ☰ to reorder', async () => {
  const before = await p.locator('.item-name').allTextContents();
  const hd = await p.locator('.item-row >> nth=6').locator('.drag-handle').boundingBox();
  const r3 = await p.locator('.item-row >> nth=3').boundingBox();
  await drag(hd.x + 20, hd.y + 20, hd.x + 20, r3.y + 8, 12, 0);
  await p.waitForTimeout(300);
  const after = await p.locator('.item-name').allTextContents();
  expect(after.join() !== before.join(), 'order unchanged');
});
await check('Sort by name', async () => { await p.click('.card-head .btn >> text=Sort'); await p.waitForTimeout(200); });
await check('Multi-select: select all / none, delete several', async () => {
  await p.click('.card-head .btn >> text=Select');
  await p.click('.multi-bar .btn >> text=Select all');
  expect((await p.textContent('.multi-bar b')).startsWith('7'), 'select all failed');
  await p.click('.multi-bar .btn >> text=Select none');
  // keep slides, clips and blanks; delete the audio clip only
  const names = await p.locator('.item-name').allTextContents();
  await p.click(`.item-row >> nth=${names.indexOf('music.wav')}`);
  await p.click('.multi-bar .btn >> text=Delete'); await p.click('.modal .btn.danger');
  await p.waitForTimeout(300);
  expect(!(await p.locator('.item-name').allTextContents()).includes('music.wav'), 'not deleted');
});
await check('Put items in a known order (slide1, black, slide2, clip, slide3, white)', async () => {
  // order via drag is checked above; set a deterministic order for recording
  await p.evaluate(async () => {
    const r = indexedDB.open('slidevox');
    await new Promise((res) => { r.onsuccess = res; });
    const d = r.result;
    const st = d.transaction('projects', 'readwrite').objectStore('projects');
    const all = await new Promise((res) => { const q = st.getAll(); q.onsuccess = () => res(q.result); });
    const pr = all.find((x) => x.name === 'Regression test');
    const by = (n) => pr.slides.find((s) => s.name === n);
    pr.slides = [by('slide1.png'), by('Black page'), by('slide2.png'), by('clip.webm'), by('slide3.png'), by('White page')];
    pr.slides[0].rot = 0;
    st.put(pr);
    await new Promise((res) => { st.transaction.oncomplete = res; });
  });
  await p.reload(); await p.waitForSelector('.item-row >> nth=5');
});
await check('Script: type and word count', async () => {
  await p.fill('.script-area', 'Namaste. Aaj SIP ki baat.\n\nPehla point compounding.\n\nDoosra point expense ratio.');
  await p.waitForSelector('#script-card >> text=words');
});
await check('Video shape: 16:9 and 9:16 options', async () => {
  await p.click('text=Portrait 9:16'); await p.click('text=Landscape 16:9');
  expect(await p.isVisible('.seg button.on >> text=Landscape 16:9'), 'aspect not set');
});

// ---------- Recorder ----------
console.log('Recorder');
await landscape();
await check('Open recorder (landscape) with script panel', async () => {
  await p.click('text=Create video & record voice');
  await p.waitForSelector('canvas.stage');
  await p.waitForTimeout(500);
  expect(await p.isVisible('.script-panel'), 'script hidden');
});
await check('Teleprompter controls: speed, size, move, auto-scroll', async () => {
  for (const t of ['Faster', 'Slower', 'Bigger text', 'Smaller text', 'Move panel', 'Move panel', 'Auto-scroll']) await p.click(`.script-panel [title="${t}"]`);
});
await check('Voice effect chips in the tools menu', async () => {
  await p.click('.rail-right .rail-btn >> nth=0');
  await p.click('.fly-chips .chip >> text=Energetic');
  expect(await p.isVisible('.fly-toggle >> text=Swipe to change image: ON'), 'swipe toggle missing');
  await p.click('.fly-item[data-tool="pen"]');
});
await check('Record starts (mic)', async () => {
  await p.click('.rec-btn'); await p.waitForTimeout(800);
  expect((await p.getAttribute('.rec-screen', 'class')).includes('is-rec'), 'not recording');
});
let sb;
await check('Pen, highlighter, line, arrow, circle, square, tick, cross, laser', async () => {
  sb = await stage();
  const x = sb.x + sb.width * 0.3, y = sb.y + sb.height * 0.5;
  await drag(x, y, x + 80, y + 20);
  for (const t of ['hl', 'line', 'arrow', 'circle', 'rect']) { await tool(t); await drag(x, y - 60, x + 70, y - 20, 6); }
  await tool('tick'); await p.mouse.click(x + 120, y - 40);
  await tool('cross'); await p.mouse.click(x + 160, y - 40);
  await tool('laser'); await drag(x, y, x + 60, y, 6);
  await tool('pen');
});
await check('Colours and sizes', async () => {
  await p.click('[title="Colour & size"]'); await p.click('.color-dot >> nth=1');
  await p.click('[title="Colour & size"]'); await p.click('.size-dot >> nth=2');
  await p.click('[title="Colour & size"]'); await p.click('.color-dot >> nth=0');
});
await check('Undo mark, delete all marks', async () => {
  await p.click('[title="Undo last mark"]'); await p.click('[title="Delete all marks"]');
});
await check('Zoom (wheel + pinch-style), fit, rotate', async () => {
  await p.mouse.move(sb.x + sb.width / 2, sb.y + sb.height / 2);
  for (let i = 0; i < 3; i++) await p.mouse.wheel(0, -100);
  await tool('move'); await drag(sb.x + 200, sb.y + 150, sb.x + 260, sb.y + 170, 6); // pan while zoomed
  await p.click('[title="Fit to screen"]'); await p.waitForTimeout(300);
  await p.click('[title="Rotate image"]'); await p.click('[title="Rotate image"]'); await p.click('[title="Rotate image"]'); await p.click('[title="Rotate image"]');
  await tool('pen');
});
await check('Swipe (pen flick) → next is the black page, pen turns white', async () => {
  await p.waitForTimeout(500);
  const y = sb.y + sb.height * 0.85; // left/bottom: away from the script panel
  await drag(sb.x + sb.width * 0.5, y, sb.x + sb.width * 0.05, y, 3, 0);
  await p.waitForTimeout(300);
  expect((await p.textContent('.slide-count')).startsWith('2/'), `count ${await p.textContent('.slide-count')}`);
  const c = await p.evaluate(() => getComputedStyle(document.querySelector('.swatch')).backgroundColor);
  expect(c === 'rgb(255, 255, 255)', `pen ${c}`);
  await drag(sb.x + 150, sb.y + 150, sb.x + 260, sb.y + 180); // white stroke on black
});
await check('Pause / resume', async () => {
  await p.click('[title="Pause"]'); expect((await p.getAttribute('.rec-screen', 'class')).includes('is-paused'), 'not paused');
  await p.click('[title="Resume"]'); expect((await p.getAttribute('.rec-screen', 'class')).includes('is-rec'), 'not resumed');
});
await check('Next button → slide 2, pen colour restored', async () => {
  await p.waitForTimeout(600);
  await p.click('[title="Next image"]');
  const c = await p.evaluate(() => getComputedStyle(document.querySelector('.swatch')).backgroundColor);
  expect(c !== 'rgb(255, 255, 255)', 'pen not restored');
});
await check('Move-tool swipe → video clip pauses recording', async () => {
  await p.waitForTimeout(600);
  await tool('move');
  await drag(sb.x + sb.width * 0.5, sb.y + sb.height * 0.85, sb.x + sb.width * 0.05, sb.y + sb.height * 0.85, 6, 0);
  await p.waitForTimeout(300);
  expect(await p.isVisible('.clip-note'), 'clip note missing');
  expect((await p.getAttribute('.rec-screen', 'class')).includes('is-paused'), 'not auto-paused');
});
await check('Swipe on → recording resumes', async () => {
  await drag(sb.x + sb.width * 0.5, sb.y + sb.height * 0.85, sb.x + sb.width * 0.05, sb.y + sb.height * 0.85, 6, 0);
  await p.waitForTimeout(300);
  expect((await p.getAttribute('.rec-screen', 'class')).includes('is-rec'), 'not resumed');
  await tool('pen');
});
await check('+ Page during recording (white) → black pen', async () => {
  await p.waitForTimeout(600);
  await p.click('.page-btn'); await p.click('.modal .btn.white-btn'); await p.waitForTimeout(300);
  const c = await p.evaluate(() => getComputedStyle(document.querySelector('.swatch')).backgroundColor);
  expect(c === 'rgb(20, 18, 16)', `pen ${c}`);
  await drag(sb.x + 200, sb.y + 200, sb.x + 320, sb.y + 160);
});
await check('Previous button and keyboard arrows', async () => {
  await p.click('[title="Previous image"]'); await p.keyboard.press('ArrowRight');
});
await check('Script hide / show', async () => {
  await p.click('.script-panel [title="Hide script"]'); await p.click('[title="Script on/off"]');
  expect(await p.isVisible('.script-panel'), 'script not back');
});
await check('Stop → editor opens', async () => {
  await p.waitForTimeout(800);
  await p.click('.rec-btn');
  await p.waitForSelector('canvas.tl', { timeout: 20000 });
});

// ---------- Editor ----------
console.log('Editor');
await portrait(); await p.waitForTimeout(500);
const tl = async () => { await p.locator('canvas.tl').scrollIntoViewIfNeeded(); return p.locator('canvas.tl').boundingBox(); };
const tapTL = async (f) => { const r = await tl(); await p.mouse.click(r.x + 14 + (r.width - 28) * f, r.y + 120); await p.waitForTimeout(120); };
const dur = async () => (await p.textContent('.time')).split('/')[1].trim();
let full;
await check('Preview plays and pauses', async () => {
  full = await dur();
  await p.click('.play-btn'); await p.waitForTimeout(700); await p.click('.play-btn');
  expect(!(await p.textContent('.time')).startsWith('0:00.0'), 'did not play');
});
await check('Timeline zoom in / out / fit, tap to jump', async () => {
  await p.click('[title="Zoom in"]'); await p.click('[title="Zoom out"]'); await p.click('.tl-tools .btn >> text=Fit');
  await tapTL(0.5);
});
await check('Clip marker + Skip / Include in edits', async () => {
  await p.waitForSelector('.edit-row >> text=clip.webm');
  await p.click('.edit-row >> text=clip.webm >> xpath=.. >> text=Skip');
  await p.click('.edit-row >> text=clip.webm >> xpath=.. >> text=Include');
});
await check('Track buttons: Video / Voice / Music', async () => {
  await p.click('.track-voice'); await p.click('.track-music'); await p.click('.track-video');
  expect((await p.textContent('.sel-info')).includes('Voice + Music'), await p.textContent('.sel-info'));
  await p.click('.track-music');
});
await check('Scissors: voice-only delete', async () => {
  await tapTL(0.1); await p.click('.tool-big >> text=Cut here');
  await tapTL(0.2); await p.click('.tool-big >> text=Cut end');
  await p.click('.tool-big >> text=Delete');
  await p.waitForSelector('.edit-row >> text=Muted');
});
await check('Voice volume on a piece', async () => {
  await tapTL(0.3); await p.click('.tool-big >> text=Cut here'); await tapTL(0.35); await p.click('.tool-big >> text=Cut end');
  await p.click('.tool-big >> text=Volume'); await p.click('.modal .quick .btn >> text=150%'); await p.click('.modal .btn.primary');
  await p.waitForSelector('.edit-row >> text=150%');
});
await check('Long-press + drag select, video cut shortens, Undo restores, Redo', async () => {
  await p.click('.track-video'); await p.click('.track-voice');
  const r = await tl();
  await p.mouse.move(r.x + r.width * 0.55, r.y + 120); await p.mouse.down(); await p.waitForTimeout(600);
  await p.mouse.move(r.x + r.width * 0.7, r.y + 120, { steps: 5 }); await p.mouse.up();
  await p.click('.tool-big >> text=Delete');
  const cut = await dur();
  expect(cut !== full, 'no cut');
  await p.click('.tool-big >> text=Undo'); expect((await dur()) === full, 'undo failed');
  await p.click('.tool-big >> text=Redo'); expect((await dur()) === cut, 'redo failed');
  await p.click('.edit-row >> text=Cut >> xpath=.. >> text=Restore');
  expect((await dur()) === full, 'restore failed');
});
await check('Voice effect presets + fine tune', async () => {
  await p.click('.chip >> text=Deep'); await p.click('.chip >> text=Bass boost'); await p.click('.chip >> text=Echo');
  await p.click('text=Fine tune'); await p.waitForSelector('.fx-sliders');
  await p.click('.chip >> text=Energetic');
});
await check('Music: add, volume, start at playhead, loop, fade, priority, dip', async () => {
  await pick(() => p.click('.music-row .btn'), [F('music.wav')]);
  await p.waitForSelector('text=Who stays on top?');
  await p.click('text=Start music at playhead'); await p.click('.btn-row .btn >> text=Loop'); await p.click('.btn-row .btn >> text=Fade out at end');
  await p.click('.seg button >> text=Music on top'); await p.click('.seg button >> text=Equal'); await p.click('.seg button >> text=Voice on top');
  await p.click('.btn-row .btn >> text=Loop');
});
await check('Music volume on a piece', async () => {
  await p.click('.track-music'); await p.click('.track-video');
  await tapTL(0.4); await p.click('.tool-big >> text=Cut here'); await tapTL(0.5); await p.click('.tool-big >> text=Cut end');
  await p.click('.tool-big >> text=Volume'); await p.click('.modal .btn.primary');
  await p.waitForSelector('.edit-row .tag.music');
  await p.click('.track-video');
});
await check('Rename take', async () => {
  await p.click('.topbar .title-btn'); await p.fill('.modal input', 'Take A'); await p.click('.modal .btn.primary');
  await p.waitForSelector('.title-btn >> text=Take A');
});
let exported;
await check('Export dialog: 5 qualities, saved clip at Start / After / End', async () => {
  await p.click('.topbar .btn.primary');
  await p.waitForSelector('.preset');
  expect((await p.locator('.preset').count()) === 5, 'not 5 presets');
  await p.selectOption('.clip-row select', 'start'); await p.selectOption('.clip-row select', '0'); await p.selectOption('.clip-row select', 'end');
  await p.click('.preset >> nth=1');
  await p.click('.modal .btn.primary');
  await p.waitForSelector('text=Video ready', { timeout: 180000 });
  exported = await p.evaluate(async () => {
    const v = document.querySelector('.result-video');
    await new Promise((r) => (v.readyState >= 1 ? r() : (v.onloadedmetadata = r)));
    const b = await (await fetch(v.src)).arrayBuffer();
    const a = await new AudioContext().decodeAudioData(b.slice(0));
    const d = a.getChannelData(0); let s = 0; for (let i = 0; i < d.length; i++) s += d[i] * d[i];
    return { dur: v.duration, w: v.videoWidth, h: v.videoHeight, rms: Math.sqrt(s / d.length) };
  });
  expect(exported.w === 1280 && exported.h === 720, `size ${exported.w}x${exported.h}`);
  expect(exported.rms > 0.01, 'silent audio');
  return `${exported.dur.toFixed(1)}s ${exported.w}x${exported.h}`;
});
await check('Result: play full screen, save, share buttons', async () => {
  for (const t of ['Play full screen', 'Save', 'Share']) expect(await p.isVisible(`.modal-actions .btn >> text=${t}`), t);
  await p.click('.modal-actions .btn >> text=Close');
});
await check('Continue recording appends to the take', async () => {
  await landscape();
  await p.click('text=Continue recording');
  await p.waitForSelector('canvas.stage');
  await p.click('.rec-btn'); await p.waitForTimeout(1500); await p.click('.rec-btn');
  await p.waitForSelector('canvas.tl', { timeout: 20000 });
  await portrait();
});
await check('Full-screen preview button exists', async () => { expect(await p.isVisible('.preview-full'), 'missing'); });

// ---------- 9:16 + other export paths ----------
console.log('9:16 and fallback');
await check('9:16 project records in portrait and exports 9:16', async () => {
  await p.goto(URL0);
  await p.click('text=New project'); await p.click('.modal .btn.primary'); await p.waitForSelector('.add-row');
  await pick(() => p.click('.add-row .btn >> text=Images'), [F('slide1.png'), F('slide2.png')]);
  await p.waitForSelector('.item-row >> nth=1');
  await p.click('text=Portrait 9:16');
  await p.click('text=Create video & record voice'); await p.waitForSelector('.rec-screen.vertical');
  await p.click('.rec-btn'); await p.waitForTimeout(1500); await p.click('[title="Next image"]'); await p.waitForTimeout(1000); await p.click('.rec-btn');
  await p.waitForSelector('canvas.tl', { timeout: 20000 });
  await p.click('.topbar .btn.primary'); await p.waitForSelector('.preset'); await p.click('.preset >> nth=0'); await p.click('.modal .btn.primary');
  await p.waitForSelector('text=Video ready', { timeout: 120000 });
  const s = await p.evaluate(async () => { const v = document.querySelector('.result-video'); await new Promise((r) => (v.readyState >= 1 ? r() : (v.onloadedmetadata = r))); return [v.videoWidth, v.videoHeight]; });
  expect(s[0] === 480 && s[1] === 854, `size ${s}`);
  await p.click('.modal-actions .btn >> text=Close');
});
await check('Delete take and delete project', async () => {
  await p.click('.ed-right .btn >> text=Delete take'); await p.click('.modal .btn.danger');
  await p.waitForSelector('.add-row');
  await p.click('a[title="Back"]'); await p.waitForSelector('text=New project');
  const n = await p.locator('.proj-card').count();
  await p.click('.proj-card >> nth=0 >> [title="Delete project"]'); await p.click('.modal .btn.danger');
  await p.waitForTimeout(300);
  expect((await p.locator('.proj-card').count()) === n - 1, 'project not deleted');
});
{
  // Older phones without the fast encoder: real-time export path.
  const c2 = await browser.newContext({ viewport: { width: 844, height: 390 }, permissions: ['microphone'] });
  await c2.addInitScript(() => { delete window.VideoEncoder; delete window.AudioEncoder; });
  const q = await c2.newPage();
  q.on('pageerror', (e) => errors.push(e.message));
  page = q;
  await check('Real-time export fallback (no WebCodecs)', async () => {
    await q.goto(URL0);
    await q.evaluate(() => localStorage.setItem('slidevox.settings', JSON.stringify({ countdown: false })));
    await q.reload();
    await q.click('text=New project'); await q.click('.modal .btn.primary'); await q.waitForSelector('.add-row');
    const [fc] = await Promise.all([q.waitForEvent('filechooser'), q.click('.add-row .btn >> text=Images')]);
    await fc.setFiles([F('slide1.png')]);
    await q.waitForSelector('.item-row');
    await q.click('text=Create video & record voice'); await q.waitForSelector('canvas.stage');
    await q.click('.rec-btn'); await q.waitForTimeout(2000); await q.click('.rec-btn');
    await q.waitForSelector('canvas.tl', { timeout: 20000 });
    await q.click('.topbar .btn.primary'); await q.waitForSelector('.preset'); await q.click('.modal .btn.primary');
    await q.waitForSelector('text=Video ready', { timeout: 60000 });
  });
  await c2.close();
}

await check('No JavaScript errors during the whole run', async () => {
  expect(!errors.length, errors.slice(0, 3).join(' | '));
});

await browser.close();
server.close();
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} checks passed${bad.length ? ` — FAILED: ${bad.map((b) => b.name).join('; ')}` : ''}`);
if (bad.length) console.log(`Screenshots: ${SHOTS}`);
process.exit(bad.length ? 1 : 0);
