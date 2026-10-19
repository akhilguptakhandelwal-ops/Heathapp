// Small DOM + misc helpers shared by all screens.

export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];

export function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'html') el.innerHTML = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k in el && typeof v !== 'string') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  for (const c of kids.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return el;
}

export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
export const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
export const r2 = (v) => Math.round(v * 100) / 100;

export function fmt(sec, tenths = false) {
  if (!isFinite(sec)) sec = 0;
  sec = Math.max(0, sec);
  if (!tenths) sec = Math.round(sec);
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  let out = `${m}:${String(s).padStart(2, '0')}`;
  if (tenths) out += '.' + Math.floor((sec * 10) % 10);
  return out;
}

export function debounce(fn, ms) {
  let t;
  const d = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  d.flush = (...a) => { clearTimeout(t); return fn(...a); };
  return d;
}

let toastTimer;
export function toast(msg, ms = 2400) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), ms);
}

// Generic modal. `body` is a node; buttons: [{label, kind, value}]
export function modal({ title, body, buttons = [], dismissable = true }) {
  return new Promise((resolve) => {
    const close = (val) => { wrap.remove(); resolve(val); };
    const wrap = h('div', { class: 'modal-wrap', onclick: (e) => { if (dismissable && e.target === wrap) close(null); } },
      h('div', { class: 'modal' },
        title && h('h3', null, title),
        body,
        buttons.length && h('div', { class: 'modal-actions' },
          buttons.map((b) => h('button', { class: `btn ${b.kind || ''}`, onclick: () => close(typeof b.value === 'function' ? b.value() : b.value) }, b.label)))));
    document.body.append(wrap);
    const first = wrap.querySelector('input,textarea');
    if (first) setTimeout(() => first.focus(), 50);
  });
}

export function confirmBox(msg, okLabel = 'Delete') {
  return modal({
    title: msg,
    body: null,
    buttons: [{ label: 'Cancel', value: false }, { label: okLabel, kind: 'danger', value: true }],
  });
}

export function promptBox(title, value = '') {
  const input = h('input', { class: 'field', value, onkeydown: (e) => { if (e.key === 'Enter') e.target.closest('.modal').querySelector('.btn.primary').click(); } });
  return modal({
    title,
    body: input,
    buttons: [{ label: 'Cancel', value: null }, { label: 'Save', kind: 'primary', value: () => input.value.trim() }],
  });
}

export function pickFiles(accept, multiple = false) {
  return new Promise((resolve) => {
    const input = h('input', { type: 'file', accept, style: { display: 'none' } });
    if (multiple) input.multiple = true;
    input.addEventListener('change', () => { resolve([...input.files]); input.remove(); });
    document.body.append(input);
    input.click();
  });
}

export async function loadImage(blob) {
  try {
    return await createImageBitmap(blob);
  } catch {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.src = url;
    await img.decode();
    return img;
  }
}

// Keep images sharp but bounded so phones don't run out of memory.
export async function prepareImage(file, max = 2560) {
  const bmp = await loadImage(file);
  const w = bmp.width, h = bmp.height;
  if (w <= max && h <= max && file.size < 6e6 && /image\/(png|jpeg|webp)/.test(file.type)) {
    return { blob: file, w, h };
  }
  const k = Math.min(1, max / Math.max(w, h));
  const cw = Math.round(w * k), ch = Math.round(h * k);
  const cv = document.createElement('canvas');
  cv.width = cw; cv.height = ch;
  const ctx = cv.getContext('2d');
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bmp, 0, 0, cw, ch);
  const blob = await new Promise((res) => cv.toBlob(res, 'image/jpeg', 0.92));
  return { blob, w: cw, h: ch };
}

// Running inside the Android app (Capacitor) instead of a browser?
export const native = () => (window.Capacitor?.isNativePlatform?.() ? window.Capacitor : null);

export async function lockOrientation(orientation) {
  const so = native()?.Plugins?.ScreenOrientation;
  if (so) { try { await so.lock({ orientation }); } catch { /* ignore */ } return; }
  try { await screen.orientation?.lock?.(orientation); } catch { /* iOS etc. */ }
}

export async function unlockOrientation() {
  const so = native()?.Plugins?.ScreenOrientation;
  if (so) { try { await so.unlock(); } catch { /* ignore */ } return; }
  try { screen.orientation?.unlock?.(); } catch { /* ignore */ }
}

export async function enterImmersive(orientation = 'landscape') {
  if (native()) { await lockOrientation(orientation); return; }
  try {
    if (!document.fullscreenElement && document.documentElement.requestFullscreen) {
      await document.documentElement.requestFullscreen({ navigationUI: 'hide' });
    }
  } catch { /* not allowed here, fine */ }
  await lockOrientation(orientation);
}

export async function exitImmersive() {
  await unlockOrientation();
  try { if (document.fullscreenElement) await document.exitFullscreen(); } catch { /* ignore */ }
}

export async function keepAwake() {
  try { return await navigator.wakeLock?.request('screen'); } catch { return null; }
}

function base64Of(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1] || '');
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}

// Writes a blob to the phone's storage in pieces (big videos would not fit in one go).
async function nativeWrite(blob, path, directory) {
  const fs = native().Plugins.Filesystem;
  const CHUNK = 3 * 1024 * 1024; // multiple of 3, so base64 pieces join cleanly
  for (let off = 0; off < blob.size || off === 0; off += CHUNK) {
    const data = await base64Of(blob.slice(off, off + CHUNK));
    if (off === 0) await fs.writeFile({ path, data, directory, recursive: true });
    else await fs.appendFile({ path, data, directory });
    if (blob.size === 0) break;
  }
  return (await fs.getUri({ path, directory })).uri;
}

export async function downloadBlob(blob, name) {
  if (native()) {
    toast('Saving…', 8000);
    for (const dir of ['DOCUMENTS', 'EXTERNAL']) {
      try {
        await nativeWrite(blob, `SlideVox/${name}`, dir);
        toast(dir === 'DOCUMENTS' ? `Saved in Documents/SlideVox/${name}` : `Saved in the app's folder. Use Share to send it anywhere.`, 5000);
        return true;
      } catch (e) { console.error(e); }
    }
    toast('Could not save. Please use Share instead.', 4000);
    return false;
  }
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

export async function shareBlob(blob, name) {
  if (native()) {
    try {
      toast('Preparing…', 8000);
      const uri = await nativeWrite(blob, `share/${name}`, 'CACHE');
      await native().Plugins.Share.share({ title: name, files: [uri], dialogTitle: 'Share video' });
      return true;
    } catch (e) {
      if (!/cancel/i.test(String(e?.message))) { console.error(e); toast('Sharing failed — try Save', 3500); }
      return false;
    }
  }
  const file = new File([blob], name, { type: blob.type });
  if (navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: name }); return true; } catch { return false; }
  }
  downloadBlob(blob, name);
  return true;
}

export function extFor(mime) {
  if (/mp4/.test(mime)) return 'mp4';
  if (/webm/.test(mime)) return 'webm';
  if (/ogg/.test(mime)) return 'ogg';
  return 'bin';
}
