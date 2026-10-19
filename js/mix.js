// Edit decision list (cuts, volume ranges, music) + audio mixing.
import { clamp } from './util.js';

export function defaultEdl() {
  return {
    cuts: [],        // [[a,b]] in source seconds, removed from video + voice
    gains: [],       // [{id, gid, track:'voice'|'music', a, b, g}] in source seconds
    voiceVol: 1,
    music: null,     // {blobId, name, start, vol, loop, fadeOut}
    priority: 'voice', // 'voice' | 'music' | 'equal'
    duck: 0.3,       // how far the lower track dips
  };
}

export function mergeRanges(rs) {
  const s = rs.filter(([a, b]) => b - a > 0.001).map(([a, b]) => [a, b]).sort((x, y) => x[0] - y[0]);
  const out = [];
  for (const r of s) {
    const last = out[out.length - 1];
    if (last && r[0] <= last[1] + 0.001) last[1] = Math.max(last[1], r[1]);
    else out.push(r);
  }
  return out;
}

// Maps between source time (what was recorded) and output time (after cuts).
export class TimeMap {
  constructor(D, cuts) {
    this.D = D;
    this.cuts = mergeRanges(cuts.map(([a, b]) => [clamp(a, 0, D), clamp(b, 0, D)]));
    const segs = [];
    let pos = 0, o = 0;
    for (const [a, b] of this.cuts) {
      if (a > pos) { segs.push({ a: pos, b: a, o }); o += a - pos; }
      pos = Math.max(pos, b);
    }
    if (D > pos) { segs.push({ a: pos, b: D, o }); o += D - pos; }
    this.segs = segs;
    this.outD = o;
  }
  toSrc(T) {
    for (const s of this.segs) if (T < s.o + (s.b - s.a)) return s.a + Math.max(0, T - s.o);
    const l = this.segs[this.segs.length - 1];
    return l ? l.b : 0;
  }
  toOut(t) {
    for (const s of this.segs) {
      if (t < s.a) return s.o;
      if (t <= s.b) return s.o + t - s.a;
    }
    return this.outD;
  }
  outRangeToSrc(A, B) {
    const res = [];
    for (const s of this.segs) {
      const len = s.b - s.a;
      const x = Math.max(A, s.o), y = Math.min(B, s.o + len);
      if (y - x > 0.0005) res.push([s.a + x - s.o, s.a + y - s.o]);
    }
    return res;
  }
  joins() { return this.segs.slice(1).map((s) => s.o); }
}

export async function decodeBlob(ctx, blob) {
  const ab = await blob.arrayBuffer();
  return await new Promise((res, rej) => {
    const p = ctx.decodeAudioData(ab, res, rej);
    if (p && p.then) p.then(res, rej);
  });
}

// Waveform peaks + "is the person speaking" flags, 50 per second.
export function analyzeVoice(parts, D, rate = 50) {
  const n = Math.ceil(D * rate) + 2;
  const peaks = new Float32Array(n), rms = new Float32Array(n);
  for (const { start, buf } of parts) {
    const data = buf.getChannelData(0);
    const per = buf.sampleRate / rate;
    const b0 = Math.round(start * rate);
    for (let k = 0; k * per < data.length; k++) {
      const idx = b0 + k;
      if (idx >= n) break;
      const i0 = Math.floor(k * per), i1 = Math.min(data.length, Math.floor((k + 1) * per));
      let pk = 0, sum = 0;
      for (let i = i0; i < i1; i++) {
        const v = data[i];
        const av = v < 0 ? -v : v;
        if (av > pk) pk = av;
        sum += v * v;
      }
      peaks[idx] = Math.max(peaks[idx], pk);
      rms[idx] = Math.max(rms[idx], Math.sqrt(sum / Math.max(1, i1 - i0)));
    }
  }
  let mx = 0.0001;
  for (const v of peaks) if (v > mx) mx = v;
  const sorted = Array.from(rms).filter((v) => v > 0).sort((a, b) => a - b);
  const noise = sorted[Math.floor(sorted.length * 0.15)] || 0;
  const loud = sorted[Math.floor(sorted.length * 0.9)] || 0;
  const th = Math.max(0.006, noise + (loud - noise) * 0.2);
  const active = new Uint8Array(n);
  const hang = Math.round(0.4 * rate), pre = Math.round(0.15 * rate);
  let hold = 0;
  for (let i = 0; i < n; i++) {
    if (rms[i] > th) { active[i] = 1; hold = hang; } else if (hold > 0) { active[i] = 1; hold--; }
  }
  hold = 0;
  for (let i = n - 1; i >= 0; i--) {
    if (rms[i] > th) hold = pre; else if (hold > 0) { active[i] = 1; hold--; }
  }
  return { rate, peaks, peakMax: mx, active };
}

export function analyzeMusic(buf, rate = 20) {
  const data = buf.getChannelData(0);
  const per = buf.sampleRate / rate;
  const n = Math.ceil(data.length / per);
  const peaks = new Float32Array(n);
  let mx = 0.0001;
  for (let k = 0; k < n; k++) {
    let pk = 0;
    const i1 = Math.min(data.length, Math.floor((k + 1) * per));
    for (let i = Math.floor(k * per); i < i1; i += 4) { const v = Math.abs(data[i]); if (v > pk) pk = v; }
    peaks[k] = pk;
    if (pk > mx) mx = pk;
  }
  for (let k = 0; k < n; k++) peaks[k] /= mx;
  return { rate, peaks, duration: buf.duration };
}

export function rangeGain(gains, track, s) {
  let g = 1;
  for (const r of gains) if (r.track === track && s >= r.a && s < r.b) g = r.g;
  return g;
}

// Gain curves over output time, sampled `rate` times per second.
export function computeCurves(M, edl, va, hasMusic, T0, rate = 50, Tend = M.outD) {
  const T1 = M.outD;
  const n = Math.max(2, Math.ceil((Tend - T0) * rate) + 1);
  const v = new Float32Array(n), m = new Float32Array(n);
  const music = edl.music;
  const atk = 1 - Math.exp(-1 / (rate * 0.08));
  const rel = 1 - Math.exp(-1 / (rate * 0.45));
  let duck = null;
  for (let k = 0; k < n; k++) {
    const T = Math.min(Tend, T0 + k / rate);
    const s = M.toSrc(T);
    let vg = edl.voiceVol * rangeGain(edl.gains, 'voice', s);
    const speaking = va.active[Math.floor(s * va.rate)] === 1 && vg > 0.02;
    const musicOn = hasMusic && T >= music.start;
    let mg = 0;
    if (musicOn) {
      mg = music.vol * rangeGain(edl.gains, 'music', s);
      mg *= Math.min(1, (T - music.start) / 0.6);
      if (music.fadeOut !== false) mg *= clamp((T1 - T) / 2, 0, 1);
    }
    let target = 1;
    if (edl.priority === 'voice' && speaking && musicOn) target = edl.duck;
    if (edl.priority === 'music' && musicOn && mg > 0.01) target = edl.duck;
    if (duck === null) duck = target;
    duck += (target - duck) * (target < duck ? atk : rel);
    if (edl.priority === 'voice') mg *= duck;
    else if (edl.priority === 'music') vg *= duck;
    v[k] = vg;
    m[k] = mg;
  }
  return { v, m, rate, n };
}

// Schedules voice + music for take time T0..T1 into `dest`, starting at
// context time `at`. Used for preview (speakers) and export.
export function startMix(ctx, dest, M, parts, musicBuf, edl, va, T0, opts = {}) {
  const when0 = opts.at ?? ctx.currentTime + 0.15;
  const T1 = Math.min(opts.T1 ?? M.outD, M.outD);
  const master = ctx.createGain();
  const limiter = ctx.createDynamicsCompressor();
  limiter.threshold.value = -2;
  limiter.knee.value = 0;
  limiter.ratio.value = 20;
  limiter.attack.value = 0.003;
  limiter.release.value = 0.12;
  master.connect(limiter);
  limiter.connect(dest);

  const hasMusic = !!(musicBuf && edl.music);
  const curves = computeCurves(M, edl, va, hasMusic, T0, 50, T1);
  const span = (curves.n - 1) / curves.rate;
  const vGain = ctx.createGain();
  const mGain = ctx.createGain();
  vGain.connect(master);
  mGain.connect(master);
  vGain.gain.value = curves.v[0];
  mGain.gain.value = curves.m[0];
  if (span > 0.01) {
    try {
      vGain.gain.setValueCurveAtTime(curves.v, when0, span);
      mGain.gain.setValueCurveAtTime(curves.m, when0, span);
    } catch { /* very short tail, keep static gain */ }
  }

  const sources = [];
  for (const seg of M.segs) {
    const segLen = seg.b - seg.a;
    if (seg.o + segLen <= T0 || seg.o >= T1) continue;
    const startOut = Math.max(seg.o, T0);
    const srcStart = seg.a + startOut - seg.o;
    const srcEnd = Math.min(seg.b, seg.a + T1 - seg.o);
    for (const part of parts) {
      const pa = part.start, pb = pa + part.buf.duration;
      const ia = Math.max(srcStart, pa), ib = Math.min(srcEnd, pb);
      if (ib - ia < 0.005) continue;
      const node = ctx.createBufferSource();
      node.buffer = part.buf;
      node.connect(vGain);
      node.start(when0 + (seg.o + ia - seg.a) - T0, ia - pa, ib - ia);
      sources.push(node);
    }
  }

  if (hasMusic) {
    const ms = edl.music.start || 0;
    const mStart = Math.max(T0, ms);
    const off = mStart - ms;
    const loop = edl.music.loop !== false;
    if (mStart < T1 && (loop || off < musicBuf.duration)) {
      const node = ctx.createBufferSource();
      node.buffer = musicBuf;
      node.loop = loop;
      node.connect(mGain);
      node.start(when0 + mStart - T0, loop ? off % musicBuf.duration : off);
      node.stop(when0 + T1 - T0 + 0.02);
      sources.push(node);
    }
  }

  return {
    T0,
    when0,
    time: () => T0 + Math.max(0, ctx.currentTime - when0),
    stop() {
      for (const s of sources) { try { s.stop(); } catch { /* not started */ } try { s.disconnect(); } catch { /* ignore */ } }
      try { limiter.disconnect(); } catch { /* ignore */ }
    },
  };
}

export function pickMime(list) {
  if (typeof MediaRecorder === 'undefined') return '';
  for (const m of list) if (MediaRecorder.isTypeSupported?.(m)) return m;
  return '';
}

export const AUDIO_MIMES = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm', 'audio/ogg;codecs=opus'];
export const VIDEO_MIMES = [
  'video/mp4;codecs=avc1.640028,mp4a.40.2',
  'video/mp4;codecs=avc1.42E01F,mp4a.40.2',
  'video/mp4;codecs=avc1,mp4a',
  'video/mp4',
  'video/webm;codecs=vp9,opus',
  'video/webm;codecs=vp8,opus',
  'video/webm',
];
