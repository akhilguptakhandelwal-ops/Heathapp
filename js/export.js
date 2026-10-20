// Builds the final video: intro clips + take (with inserted clips) + outro clips.
// Fast path: WebCodecs encoder (Mediabunny) renders frames as fast as the phone
// can encode — usually several times faster than real time.
// Fallback: real-time MediaRecorder for browsers without WebCodecs.
import { db } from './db.js';
import { Painter, ImageCache, Player, isClip } from './renderer.js';
import { startMix, decodeBlob, pickMime, VIDEO_MIMES } from './mix.js';
import { mediabunny } from './media.js';

export const FPS = 30;

export const PRESETS = [
  { id: 'low', label: 'Low', sub: '480p · smallest file', short: 480, br: 1_200_000 },
  { id: 'medium', label: 'Medium', sub: '720p HD', short: 720, br: 3_000_000 },
  { id: 'high', label: 'High', sub: '1080p Full HD', short: 1080, br: 7_000_000 },
  { id: 'youtube', label: 'YouTube', sub: '1440p 2K · best for upload', short: 1440, br: 14_000_000 },
  { id: 'pro', label: 'Professional', sub: '4K Ultra HD', short: 2160, br: 30_000_000 },
];

const even = (v) => Math.round(v / 2) * 2;

export function presetSize(p, rec) {
  const long = even(p.short * 16 / 9);
  return rec.h > rec.w ? { w: p.short, h: long } : { w: long, h: p.short };
}

// Where clip items were "visited" during recording -> they play there.
export function insertionPoints(rec) {
  const out = [];
  let slides = null, cur = null;
  for (const ev of rec.events) {
    let item = null;
    if (ev.k === 'snap') { slides = ev.st.slides; item = slides[ev.st.i]; } else if (ev.k === 'slides') { slides = ev.slides; item = slides[ev.i]; } else if (ev.k === 'slide' && slides) item = slides[ev.i];
    else continue;
    if (item && item.id !== cur && isClip(item)) out.push({ t: ev.t, item, key: `${ev.t}|${item.id}` });
    cur = item?.id ?? null;
  }
  return out;
}

// The image/clip list as recorded, and when each item first appeared.
export function recordedSequence(rec) {
  let slides = [];
  const first = new Map();
  for (const ev of rec.events) {
    let item = null;
    if (ev.k === 'snap') { slides = ev.st.slides; item = slides[ev.st.i]; } else if (ev.k === 'slides') { slides = ev.slides; item = slides[ev.i]; } else if (ev.k === 'slide') item = slides[ev.i];
    if (item && !first.has(item.id)) first.set(item.id, ev.t);
  }
  return { slides, first };
}

// extras: [{ item, where: 'start' | 'end' | k }] — k = after item number k+1 of the sequence.
export function compose(rec, M, edl, extras = []) {
  const pieces = [];
  let out = 0;
  const add = (p) => { if (p.dur > 0.01) { p.out0 = out; pieces.push(p); out += p.dur; } };
  for (const x of extras.filter((e) => e.where === 'start')) add({ kind: 'clip', item: x.item, dur: x.item.dur });
  const pts = insertionPoints(rec)
    .filter((p) => !(edl.skipClips || []).includes(p.key))
    .map((p) => ({ ...p, T: M.toOut(p.t) }));
  const { slides, first } = recordedSequence(rec);
  const tail = extras.filter((e) => e.where === 'end');
  for (const x of extras.filter((e) => typeof e.where === 'number')) {
    const next = slides[x.where + 1];
    const t = next ? first.get(next.id) : undefined;
    if (t === undefined) tail.unshift(x);
    else pts.push({ t, T: M.toOut(t), item: x.item, key: `extra-${x.item.id}` });
  }
  pts.sort((a, b) => a.T - b.T);
  let T = 0;
  for (const p of pts) {
    if (p.T > T + 0.001) { add({ kind: 'take', a: T, b: p.T, dur: p.T - T }); T = p.T; }
    add({ kind: 'clip', item: p.item, dur: p.item.dur });
  }
  if (M.outD > T + 0.001) add({ kind: 'take', a: T, b: M.outD, dur: M.outD - T });
  for (const x of tail) add({ kind: 'clip', item: x.item, dur: x.item.dur });
  return { pieces, total: out };
}

// Puts every piece's sound on one audio graph, starting at context time `at`.
async function scheduleAudio(ctx, dest, pieces, take, at) {
  const cache = new Map();
  const nodes = [];
  const mixes = [];
  for (const p of pieces) {
    if (p.kind === 'take') {
      mixes.push(startMix(ctx, dest, take.M, take.parts, take.musicBuf, take.edl, take.va, p.a, { at: at + p.out0, T1: p.b }));
    } else {
      let buf = cache.get(p.item.blobId);
      if (buf === undefined) {
        buf = null;
        try { const b = await db.getBlob(p.item.blobId); if (b) buf = await decodeBlob(ctx, b); } catch { /* clip has no sound */ }
        cache.set(p.item.blobId, buf);
      }
      if (!buf) continue;
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.connect(dest);
      src.start(at + p.out0, 0, Math.min(p.dur, buf.duration));
      nodes.push(src);
    }
  }
  return () => { mixes.forEach((m) => m.stop()); nodes.forEach((n) => { try { n.stop(); } catch { /* ignore */ } }); };
}

function drawContain(ctx, img, iw, ih, W, H, bg) {
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, W, H);
  if (!img) return;
  const k = Math.min(W / iw, H / ih);
  const w = iw * k, h = ih * k;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, (W - w) / 2, (H - h) / 2, w, h);
}

// Collects the MP4 bytes. Earlier parts become Blobs so big files can live on disk.
class FileBuilder {
  constructor() {
    this.parts = []; // {pos, len, data: Uint8Array | Blob}
    this.end = 0;
    this.stream = new WritableStream({ write: (c) => this.write(c.data, c.position) });
  }
  async write(data, pos) {
    data = new Uint8Array(data);
    if (pos >= this.end) {
      this.parts.push({ pos, len: data.length, data });
      this.end = pos + data.length;
      // keep the first part editable (the muxer patches its header at the end)
      for (let i = 1; i < this.parts.length - 1; i++) {
        const p = this.parts[i];
        if (p.data instanceof Uint8Array) p.data = new Blob([p.data]);
      }
      return;
    }
    for (const p of this.parts) {
      const a = Math.max(pos, p.pos), b = Math.min(pos + data.length, p.pos + p.len);
      if (b <= a) continue;
      if (p.data instanceof Blob) p.data = new Uint8Array(await p.data.arrayBuffer());
      p.data.set(data.subarray(a - pos, b - pos), a - p.pos);
    }
    if (pos + data.length > this.end) {
      const rest = data.subarray(this.end - pos);
      this.parts.push({ pos: this.end, len: rest.length, data: rest });
      this.end += rest.length;
    }
  }
  blob(type) {
    return new Blob(this.parts.sort((a, b) => a.pos - b.pos).map((p) => p.data), { type });
  }
}

async function openClipVideo(MB, item, W, H) {
  const blob = await db.getBlob(item.blobId);
  if (!blob) return null;
  const n = Math.max(1, Math.ceil(item.dur * FPS));
  try {
    const input = new MB.Input({ source: new MB.BlobSource(blob), formats: MB.ALL_FORMATS });
    const track = await input.getPrimaryVideoTrack();
    if (!track || !(await track.canDecode())) throw new Error('cannot decode');
    const first = await track.getFirstTimestamp();
    const sink = new MB.CanvasSink(track, { width: W, height: H, fit: 'contain', poolSize: 3 });
    const times = Array.from({ length: n }, (_, i) => first + i / FPS);
    const it = sink.canvasesAtTimestamps(times)[Symbol.asyncIterator]();
    let last = null;
    return {
      async next() {
        const r = await it.next();
        if (!r.done && r.value) last = r.value.canvas;
        return last;
      },
      close() { it.return?.(); input.dispose?.(); },
      fitted: true,
    };
  } catch {
    // Slow but universal: seek a <video> element frame by frame.
    const url = URL.createObjectURL(blob);
    const v = document.createElement('video');
    v.muted = true; v.playsInline = true; v.preload = 'auto'; v.src = url;
    await new Promise((res) => { v.onloadeddata = res; v.onerror = res; });
    let i = 0;
    return {
      async next() {
        const t = Math.min(i++ / FPS, Math.max(0, (v.duration || item.dur) - 0.05));
        if (Math.abs(v.currentTime - t) > 0.001) {
          await new Promise((res) => { v.onseeked = res; v.currentTime = t; setTimeout(res, 1500); });
        }
        return v;
      },
      close() { URL.revokeObjectURL(url); v.removeAttribute('src'); },
      fitted: false,
      el: v,
    };
  }
}

export async function canFastExport() {
  if (typeof VideoEncoder === 'undefined' || typeof AudioEncoder === 'undefined') return false;
  try {
    const MB = await mediabunny();
    const v = await MB.getFirstEncodableVideoCodec(['avc', 'hevc', 'vp9', 'av1'], { width: 1280, height: 720 });
    const a = await MB.getFirstEncodableAudioCodec(['aac', 'opus'], { numberOfChannels: 2, sampleRate: 48000 });
    return !!(v && a);
  } catch {
    return false;
  }
}

// Which presets this phone's encoder can handle.
export async function supportedPresets(rec) {
  const ok = new Set();
  const fast = await canFastExport();
  if (!fast) { PRESETS.slice(0, 4).forEach((p) => ok.add(p.id)); return { ok, fast }; }
  const MB = await mediabunny();
  for (const p of PRESETS) {
    const { w, h } = presetSize(p, rec);
    try {
      const c = await MB.getFirstEncodableVideoCodec(['avc', 'hevc', 'vp9', 'av1'], { width: w, height: h, bitrate: p.br });
      if (c) ok.add(p.id);
    } catch { /* not supported */ }
  }
  return { ok, fast };
}

export async function fastExport({ rec, take, comp, preset, onProgress, cancelled }) {
  const MB = await mediabunny();
  const { w: W, h: H } = presetSize(preset, rec);
  const vcodec = await MB.getFirstEncodableVideoCodec(['avc', 'hevc', 'vp9', 'av1'], { width: W, height: H, bitrate: preset.br });
  const acodec = await MB.getFirstEncodableAudioCodec(['aac', 'opus'], { numberOfChannels: 2, sampleRate: 48000, bitrate: 160_000 });
  if (!vcodec || !acodec) throw new Error('This phone cannot encode this quality. Try a lower one.');

  const { pieces, total } = comp;
  onProgress(0, 'Mixing sound…');
  const SR = 48000;
  const off = new OfflineAudioContext(2, Math.max(1, Math.ceil(total * SR)), SR);
  await scheduleAudio(off, off.destination, pieces, take, 0);
  const audio = await off.startRendering();
  if (cancelled()) throw new Error('CANCELLED');

  const cv = document.createElement('canvas');
  cv.width = W; cv.height = H;
  const ctx = cv.getContext('2d');
  const cache = new ImageCache(6);
  const painter = new Painter(cv, rec.bg, cache, { w: rec.w, h: rec.h });
  const player = new Player(rec.events);

  const file = new FileBuilder();
  const output = new MB.Output({
    format: new MB.Mp4OutputFormat({ fastStart: false }),
    target: new MB.StreamTarget(file.stream, { chunked: true, chunkSize: 4 * 1024 * 1024 }),
  });
  const vsrc = new MB.CanvasSource(cv, { codec: vcodec, bitrate: preset.br, keyFrameInterval: 2 });
  const asrc = new MB.AudioBufferSource({ codec: acodec, bitrate: 160_000 });
  output.addVideoTrack(vsrc, { frameRate: FPS });
  output.addAudioTrack(asrc);
  await output.start();

  // Audio goes in 1-second slices, interleaved with video.
  let audioPos = 0;
  const feedAudio = async (until) => {
    const end = Math.min(audio.length, Math.ceil(until * SR));
    while (audioPos < end) {
      const len = Math.min(SR, end - audioPos);
      const slice = new AudioBuffer({ length: len, numberOfChannels: 2, sampleRate: SR });
      for (let c = 0; c < 2; c++) slice.copyToChannel(audio.getChannelData(c).subarray(audioPos, audioPos + len), c);
      await asrc.add(slice);
      audioPos += len;
    }
  };

  const N = Math.max(1, Math.ceil(total * FPS));
  let pi = -1, piece = null, clip = null;
  // While nothing on screen changes (a still slide while you talk) the
  // previous frame is simply held longer — this is what makes export fast.
  let lastSig = null, lastAdded = -Infinity;
  const started = performance.now();
  const report = (f, t) => {
    const speed = t / Math.max(0.001, (performance.now() - started) / 1000);
    onProgress(f / N, `Encoding ${Math.round((f / N) * 100)}% · ${speed.toFixed(1)}× faster than real time`);
  };
  try {
    for (let f = 0; f < N; f++) {
      if (cancelled()) throw new Error('CANCELLED');
      const t = f / FPS;
      while (pi < pieces.length - 1 && (pi < 0 || t >= piece.out0 + piece.dur)) {
        clip?.close(); clip = null;
        piece = pieces[++pi];
        if (piece.kind === 'clip' && piece.item.kind === 'video') clip = await openClipVideo(MB, piece.item, W, H);
      }
      const lt = t - piece.out0;
      let sig = null;
      if (piece.kind === 'take') {
        const st = player.seek(take.M.toSrc(Math.min(piece.a + lt, piece.b)));
        sig = `${pi}:${player.i}`;
        if (sig === lastSig && t - lastAdded < 1 && f < N - 1) {
          await feedAudio(t + 1);
          if (f % 30 === 0) { report(f, t); await new Promise((r) => setTimeout(r, 0)); }
          continue;
        }
        await painter.ensure(st);
        painter.draw(st);
      } else if (clip) {
        const frame = await clip.next();
        if (clip.fitted) drawContain(ctx, frame, W, H, W, H, '#000');
        else drawContain(ctx, frame, frame?.videoWidth || W, frame?.videoHeight || H, W, H, '#000');
      } else {
        const img = await cache.get(piece.item.imageId);
        drawContain(ctx, img, piece.item.w, piece.item.h, W, H, rec.bg);
      }
      await feedAudio(t + 1);
      await vsrc.add(t, 1 / FPS);
      lastSig = sig;
      lastAdded = t;
      if (f % 10 === 0) { report(f, t); await new Promise((r) => setTimeout(r, 0)); }
    }
    await feedAudio(total + 1);
    vsrc.close();
    asrc.close();
    onProgress(1, 'Finishing…');
    await output.finalize();
  } catch (e) {
    clip?.close();
    try { await output.cancel(); } catch { /* ignore */ }
    throw e;
  }
  clip?.close();
  return file.blob('video/mp4');
}

// Plays everything once in real time and records it. Used only when the
// browser has no WebCodecs encoder.
export async function realtimeExport({ rec, take, comp, preset, onProgress, cancelled, previewCanvas }) {
  const mime = pickMime(VIDEO_MIMES);
  if (!mime || !HTMLCanvasElement.prototype.captureStream) throw new Error('This browser cannot export video. Please use Chrome or Safari.');
  const { w: W, h: H } = presetSize(preset, rec);
  const cv = previewCanvas || document.createElement('canvas');
  cv.width = W; cv.height = H;
  const ctx = cv.getContext('2d');
  const cache = new ImageCache(6);
  const painter = new Painter(cv, rec.bg, cache, { w: rec.w, h: rec.h });
  const player = new Player(rec.events);
  const { pieces, total } = comp;
  const actx = new AudioContext();
  await actx.resume();
  const dest = actx.createMediaStreamDestination();

  // Preload pictures and clip players.
  const videos = new Map();
  for (const p of pieces) {
    if (p.kind === 'clip' && p.item.kind === 'video' && !videos.has(p)) {
      const b = await db.getBlob(p.item.blobId);
      const v = document.createElement('video');
      v.muted = true; v.playsInline = true; v.preload = 'auto'; v.src = URL.createObjectURL(b);
      await new Promise((res) => { v.onloadeddata = res; v.onerror = res; });
      videos.set(p, v);
    } else if (p.kind === 'clip') await cache.get(p.item.imageId);
  }
  painter.draw(player.seek(0));
  const at = actx.currentTime + 0.4;
  const stopAudio = await scheduleAudio(actx, dest, pieces, take, at);
  const vstream = cv.captureStream(FPS);
  const stream = new MediaStream([...vstream.getVideoTracks(), ...dest.stream.getAudioTracks()]);
  const mr = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: preset.br, audioBitsPerSecond: 160_000 });
  const chunks = [];
  mr.ondataavailable = (e) => { if (e.data?.size) chunks.push(e.data); };
  const stopped = new Promise((r) => { mr.onstop = r; });
  mr.start(500);
  let playingClip = null;
  await new Promise((resolve) => {
    const step = () => {
      if (cancelled()) return resolve();
      const t = actx.currentTime - at;
      if (t >= total + 0.2) return resolve();
      const tc = Math.max(0, Math.min(total - 0.001, t));
      const p = pieces.find((x) => tc >= x.out0 && tc < x.out0 + x.dur) || pieces[pieces.length - 1];
      const v = videos.get(p);
      if (playingClip && playingClip !== v) { playingClip.pause(); playingClip = null; }
      if (p.kind === 'take') {
        const st = player.seek(take.M.toSrc(Math.min(p.a + tc - p.out0, p.b)));
        painter.draw(st);
      } else if (v) {
        if (playingClip !== v) { v.currentTime = Math.max(0, tc - p.out0); v.play().catch(() => {}); playingClip = v; }
        drawContain(ctx, v, v.videoWidth || W, v.videoHeight || H, W, H, '#000');
      } else {
        drawContain(ctx, cache.peek(p.item.imageId), p.item.w, p.item.h, W, H, rec.bg);
      }
      onProgress(Math.min(1, t / total), `Recording ${Math.round(Math.min(1, t / total) * 100)}% (real-time)`);
      requestAnimationFrame(step);
    };
    step();
  });
  stopAudio();
  mr.stop();
  await stopped;
  vstream.getTracks().forEach((tr) => tr.stop());
  videos.forEach((v) => { v.pause(); URL.revokeObjectURL(v.src); });
  actx.close().catch(() => {});
  if (cancelled()) throw new Error('CANCELLED');
  return new Blob(chunks, { type: mime.split(';')[0] });
}
