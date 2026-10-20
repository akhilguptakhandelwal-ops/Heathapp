// Importing video / audio clips: duration, size and a thumbnail ("poster").
import { db } from './db.js';
import { uid } from './util.js';

let mbPromise = null;
// The encoder/decoder library is big, so it loads only when needed.
export function mediabunny() {
  if (!mbPromise) mbPromise = import('./vendor/mediabunny.min.mjs');
  return mbPromise;
}

function canvasBlob(cv, type = 'image/jpeg', q = 0.9) {
  return new Promise((res) => cv.toBlob(res, type, q));
}

function probeVideo(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const v = document.createElement('video');
    v.muted = true;
    v.playsInline = true;
    v.preload = 'auto';
    v.src = url;
    const fail = () => { URL.revokeObjectURL(url); reject(new Error('Video could not be opened')); };
    const timer = setTimeout(fail, 20000);
    v.onerror = () => { clearTimeout(timer); fail(); };
    v.onloadedmetadata = () => {
      v.currentTime = Math.min(0.5, (v.duration || 1) / 3);
    };
    v.onseeked = async () => {
      clearTimeout(timer);
      try {
        const w = v.videoWidth || 1280, h = v.videoHeight || 720;
        const k = Math.min(1, 1600 / Math.max(w, h));
        const cv = document.createElement('canvas');
        cv.width = Math.round(w * k); cv.height = Math.round(h * k);
        cv.getContext('2d').drawImage(v, 0, 0, cv.width, cv.height);
        const poster = await canvasBlob(cv);
        URL.revokeObjectURL(url);
        resolve({ dur: v.duration, w: cv.width, h: cv.height, poster });
      } catch (e) {
        URL.revokeObjectURL(url);
        reject(e);
      }
    };
  });
}

function probeAudio(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const a = new Audio();
    a.preload = 'metadata';
    a.src = url;
    a.onloadedmetadata = () => { URL.revokeObjectURL(url); resolve(a.duration); };
    a.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Audio could not be opened')); };
  });
}

// A simple title card shown while an audio-only clip plays.
export async function audioCard(name, w = 1600, h = 900) {
  const cv = document.createElement('canvas');
  cv.width = w; cv.height = h;
  const c = cv.getContext('2d');
  const g = c.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, '#1d1915');
  g.addColorStop(1, '#2c241b');
  c.fillStyle = g;
  c.fillRect(0, 0, w, h);
  c.strokeStyle = '#d39b47';
  c.lineWidth = w / 80;
  c.lineCap = 'round';
  const cx = w / 2, cy = h * 0.42, s = Math.min(w, h) / 6;
  c.beginPath();
  c.moveTo(cx - s * 0.3, cy + s * 0.55);
  c.lineTo(cx - s * 0.3, cy - s * 0.6);
  c.lineTo(cx + s * 0.6, cy - s * 0.8);
  c.lineTo(cx + s * 0.6, cy + s * 0.35);
  c.stroke();
  c.fillStyle = '#d39b47';
  c.beginPath(); c.arc(cx - s * 0.5, cy + s * 0.55, s * 0.22, 0, Math.PI * 2); c.fill();
  c.beginPath(); c.arc(cx + s * 0.4, cy + s * 0.35, s * 0.22, 0, Math.PI * 2); c.fill();
  c.fillStyle = '#f1eadf';
  c.font = `700 ${Math.round(h / 16)}px Manrope, system-ui, sans-serif`;
  c.textAlign = 'center';
  c.fillText(name.replace(/\.[a-z0-9]+$/i, '').slice(0, 40), cx, h * 0.78);
  return { blob: await canvasBlob(cv), w, h };
}

// Some files (e.g. screen recordings) report no duration; read it from the file.
async function exactDuration(file, fallback) {
  if (isFinite(fallback) && fallback > 0) return fallback;
  try {
    const MB = await mediabunny();
    const input = new MB.Input({ source: new MB.BlobSource(file), formats: MB.ALL_FORMATS });
    const d = await input.computeDuration();
    if (isFinite(d) && d > 0) return d;
  } catch { /* ignore */ }
  throw new Error('Could not read the length of this clip');
}

// Stores the clip and returns a sequence item / library entry.
export async function importClip(file) {
  const isVideo = file.type.startsWith('video/') || /\.(mp4|mov|m4v|webm|mkv|3gp)$/i.test(file.name);
  if (isVideo) {
    const info = await probeVideo(file);
    info.dur = await exactDuration(file, info.dur);
    const blobId = await db.putBlob(file);
    const imageId = await db.putBlob(info.poster);
    return { id: uid(), kind: 'video', name: file.name, blobId, imageId, w: info.w, h: info.h, dur: info.dur, rot: 0 };
  }
  const dur = await exactDuration(file, await probeAudio(file));
  const card = await audioCard(file.name);
  const blobId = await db.putBlob(file);
  const imageId = await db.putBlob(card.blob);
  return { id: uid(), kind: 'audio', name: file.name, blobId, imageId, w: card.w, h: card.h, dur, rot: 0 };
}

// A plain white or black page (stored like an image).
export async function makeBlankPage(color, aspect = '16:9') {
  const w = aspect === '9:16' ? 1080 : 1920, h = aspect === '9:16' ? 1920 : 1080;
  const cv = document.createElement('canvas');
  cv.width = w; cv.height = h;
  const c = cv.getContext('2d');
  c.fillStyle = color === 'white' ? '#ffffff' : '#000000';
  c.fillRect(0, 0, w, h);
  const blob = await canvasBlob(cv, 'image/png');
  const imageId = await db.putBlob(blob);
  return { id: uid(), kind: 'image', blank: color, imageId, w, h, rot: 0, name: color === 'white' ? 'White page' : 'Black page' };
}

// Ask "White page or Black page?" — resolves 'white' | 'black' | null.
export function askBlankColor(modal, h) {
  const pick = (c) => () => c;
  return modal({
    title: 'Add a blank page',
    body: h('div', { class: 'blank-pick' },
      h('div', { class: 'blank-swatch white' }, h('b', null, 'White page'), h('span', null, 'Pen turns black')),
      h('div', { class: 'blank-swatch black' }, h('b', null, 'Black page'), h('span', null, 'Pen turns white'))),
    buttons: [
      { label: 'Cancel', value: null },
      { label: 'White page', kind: 'white-btn', value: pick('white') },
      { label: 'Black page', kind: 'black-btn', value: pick('black') },
    ],
  });
}
