// The stage: one image + zoom/pan/rotate + drawings, rendered to a canvas.
// Recording does not capture pixels. It logs small events (slide change, zoom,
// pen strokes). The same events are replayed here for preview and export, so
// cuts and re-edits never lose quality.
import { db } from './db.js';
import { loadImage } from './util.js';

const COS_SIN = [[1, 0], [0, 1], [-1, 0], [0, -1]];

export function slideLite(s) {
  return { id: s.id, imageId: s.imageId, w: s.w, h: s.h };
}

export function fitView(r = 0) {
  return { s: 1, x: 0, y: 0, r };
}

export function emptyState(slides) {
  return {
    slides: slides.map(slideLite),
    i: 0,
    v: fitView(slides[0]?.rot || 0),
    ann: {},
    laser: null,
  };
}

function fitScale(W, H, img, r) {
  const odd = r % 2 === 1;
  const iw = odd ? img.h : img.w;
  const ih = odd ? img.w : img.h;
  return Math.min(W / iw, H / ih);
}

// Matrix that maps image pixels -> stage pixels.
export function viewMatrix(W, H, img, v) {
  const sc = fitScale(W, H, img, v.r) * v.s;
  const [cs, sn] = COS_SIN[v.r % 4];
  const a = cs * sc, b = sn * sc, c = -sn * sc, d = cs * sc;
  const tx = W / 2 + v.x, ty = H / 2 + v.y;
  const e = tx - a * img.w / 2 - c * img.h / 2;
  const f = ty - b * img.w / 2 - d * img.h / 2;
  return { a, b, c, d, e, f, sc };
}

export function applyM(m, [x, y]) {
  return [m.a * x + m.c * y + m.e, m.b * x + m.d * y + m.f];
}

export function invertM(m, [x, y]) {
  const dx = x - m.e, dy = y - m.f;
  const det = m.a * m.d - m.b * m.c;
  return [(m.d * dx - m.c * dy) / det, (-m.b * dx + m.a * dy) / det];
}

function curList(st, create) {
  const sl = st.slides[st.i];
  if (!sl) return [];
  if (!st.ann[sl.id] && create) st.ann[sl.id] = [];
  return st.ann[sl.id] || [];
}

function findAnn(st, id) {
  const list = curList(st);
  for (let i = list.length - 1; i >= 0; i--) if (list[i].id === id) return list[i];
  return null;
}

const FREEHAND = new Set(['pen', 'hl']);

// Pure-ish reducer. Returns the (possibly new) state.
export function applyEvent(st, ev) {
  if (ev.k === 'snap') return structuredClone(ev.st);
  if (!st) return st;
  switch (ev.k) {
    case 'slide':
      st.i = ev.i;
      st.v = { ...ev.v };
      st.laser = null;
      break;
    case 'view':
      st.v = { ...ev.v };
      break;
    case 'a+':
      curList(st, true).push({ ...ev.a, pts: ev.a.pts.map((p) => [...p]) });
      break;
    case 'a~': {
      const a = findAnn(st, ev.id);
      if (!a) break;
      if (FREEHAND.has(a.tool)) a.pts.push([...ev.p]);
      else a.pts[1] = [...ev.p];
      break;
    }
    case 'aset': {
      const a = findAnn(st, ev.id);
      if (a) a.pts = ev.pts.map((p) => [...p]);
      break;
    }
    case 'adel': {
      const list = curList(st);
      const i = list.findIndex((a) => a.id === ev.id);
      if (i >= 0) list.splice(i, 1);
      break;
    }
    case 'undo':
      curList(st).pop();
      break;
    case 'clear': {
      const sl = st.slides[st.i];
      if (sl) st.ann[sl.id] = [];
      break;
    }
    case 'laser':
      st.laser = ev.p;
      break;
    default:
      break;
  }
  return st;
}

// Replays an event log; seeking forward is incremental.
export class Player {
  constructor(events) { this.ev = events; this.reset(); }
  reset() { this.st = null; this.i = 0; this.t = -Infinity; }
  seek(t) {
    if (t < this.t) this.reset();
    const ev = this.ev;
    while (this.i < ev.length && ev[this.i].t <= t) {
      this.st = applyEvent(this.st, ev[this.i]);
      this.i++;
    }
    this.t = t;
    return this.st;
  }
}

function box(p) {
  const [a, b] = p;
  return { x: Math.min(a[0], b[0]), y: Math.min(a[1], b[1]), w: Math.abs(b[0] - a[0]), h: Math.abs(b[1] - a[1]) };
}

export function drawAnn(ctx, a) {
  const p = a.pts;
  if (!p.length) return;
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = a.color;
  ctx.fillStyle = a.color;
  ctx.lineWidth = a.w;
  if (a.tool === 'hl') ctx.globalAlpha = 0.38;
  ctx.beginPath();
  switch (a.tool) {
    case 'pen':
    case 'hl':
      if (p.length === 1) {
        ctx.arc(p[0][0], p[0][1], a.w / 2, 0, Math.PI * 2);
        ctx.fill();
        break;
      }
      ctx.moveTo(p[0][0], p[0][1]);
      for (let i = 1; i < p.length - 1; i++) {
        const mx = (p[i][0] + p[i + 1][0]) / 2, my = (p[i][1] + p[i + 1][1]) / 2;
        ctx.quadraticCurveTo(p[i][0], p[i][1], mx, my);
      }
      ctx.lineTo(p[p.length - 1][0], p[p.length - 1][1]);
      ctx.stroke();
      break;
    case 'line':
    case 'arrow': {
      if (p.length < 2) break;
      const [x0, y0] = p[0], [x1, y1] = p[1];
      ctx.moveTo(x0, y0);
      ctx.lineTo(x1, y1);
      ctx.stroke();
      if (a.tool === 'arrow') {
        const ang = Math.atan2(y1 - y0, x1 - x0);
        const len = Math.max(a.w * 4, Math.hypot(x1 - x0, y1 - y0) * 0.18);
        ctx.beginPath();
        ctx.moveTo(x1 - len * Math.cos(ang - 0.45), y1 - len * Math.sin(ang - 0.45));
        ctx.lineTo(x1, y1);
        ctx.lineTo(x1 - len * Math.cos(ang + 0.45), y1 - len * Math.sin(ang + 0.45));
        ctx.stroke();
      }
      break;
    }
    case 'rect': {
      if (p.length < 2) break;
      const b = box(p);
      ctx.rect(b.x, b.y, b.w, b.h);
      ctx.stroke();
      break;
    }
    case 'circle': {
      if (p.length < 2) break;
      const b = box(p);
      ctx.ellipse(b.x + b.w / 2, b.y + b.h / 2, Math.max(1, b.w / 2), Math.max(1, b.h / 2), 0, 0, Math.PI * 2);
      ctx.stroke();
      break;
    }
    case 'tick': {
      if (p.length < 2) break;
      const b = box(p);
      ctx.moveTo(b.x, b.y + b.h * 0.56);
      ctx.lineTo(b.x + b.w * 0.36, b.y + b.h);
      ctx.lineTo(b.x + b.w, b.y);
      ctx.stroke();
      break;
    }
    case 'cross': {
      if (p.length < 2) break;
      const b = box(p);
      ctx.moveTo(b.x, b.y);
      ctx.lineTo(b.x + b.w, b.y + b.h);
      ctx.moveTo(b.x + b.w, b.y);
      ctx.lineTo(b.x, b.y + b.h);
      ctx.stroke();
      break;
    }
    default:
      break;
  }
  ctx.restore();
}

export class Painter {
  constructor(canvas, bg = '#0e0c0a', images = new Map()) {
    this.cv = canvas;
    this.ctx = canvas.getContext('2d');
    this.bg = bg;
    this.imgs = images;
  }

  async load(slides) {
    for (const s of slides) {
      if (this.imgs.has(s.imageId)) continue;
      const blob = await db.getBlob(s.imageId);
      if (blob) this.imgs.set(s.imageId, await loadImage(blob));
    }
  }

  draw(st) {
    const { ctx, cv } = this;
    const W = cv.width, H = cv.height;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.fillStyle = this.bg;
    ctx.fillRect(0, 0, W, H);
    const sl = st?.slides[st.i];
    if (!sl) return;
    const m = viewMatrix(W, H, sl, st.v);
    ctx.setTransform(m.a, m.b, m.c, m.d, m.e, m.f);
    const img = this.imgs.get(sl.imageId);
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    if (img) ctx.drawImage(img, 0, 0, sl.w, sl.h);
    for (const a of st.ann[sl.id] || []) drawAnn(ctx, a);
    if (st.laser) {
      const [x, y] = applyM(m, st.laser);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      const r = W / 90;
      const g = ctx.createRadialGradient(x, y, 0, x, y, r * 2.2);
      g.addColorStop(0, 'rgba(255,70,50,1)');
      g.addColorStop(0.35, 'rgba(255,70,50,0.85)');
      g.addColorStop(1, 'rgba(255,70,50,0)');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(x, y, r * 2.2, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#fff4ef';
      ctx.beginPath();
      ctx.arc(x, y, r * 0.35, 0, Math.PI * 2);
      ctx.fill();
    }
  }
}
