// Full-screen landscape recording studio.
import { h, toast, fmt, clamp, uid, r2, debounce, enterImmersive, exitImmersive, keepAwake, modal } from './util.js';
import { makeBlankPage, askBlankColor } from './media.js';
import { openPeek } from './peek.js';
import { icon } from './icons.js';
import { db } from './db.js';
import { settings, stageSize } from './settings.js';
import { Painter, Player, emptyState, applyEvent, viewMatrix, applyM, invertM, fitView, slideLite, isClip } from './renderer.js';
import { defaultEdl, pickMime, AUDIO_MIMES, VOICE_PRESETS } from './mix.js';

const TOOLS = [
  ['move', 'hand', 'Move / zoom'],
  ['pen', 'pen', 'Pen'],
  ['hl', 'marker', 'Highlighter'],
  ['tick', 'tick', 'Tick'],
  ['cross', 'cross', 'Cross'],
  ['circle', 'circle', 'Circle'],
  ['rect', 'square', 'Square'],
  ['arrow', 'arrow', 'Arrow'],
  ['line', 'line', 'Line'],
  ['laser', 'laser', 'Laser pointer'],
];
const COLORS = ['#e8453c', '#f4b740', '#3bb26a', '#ffffff', '#141210', '#f07a2a', '#e45d9b'];
const SIZES = [4, 8, 14];
const SHAPES = new Set(['tick', 'cross', 'circle', 'rect', 'arrow', 'line']);

const rp = ([x, y]) => [Math.round(x * 10) / 10, Math.round(y * 10) / 10];
const rv = (v) => ({ s: Math.round(v.s * 1000) / 1000, x: r2(v.x), y: r2(v.y), r: v.r });
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];

export async function recorderScreen(root, { projectId, appendId }) {
  const project = await db.get('projects', projectId);
  if (!project) { location.hash = '#/'; return null; }
  if (!project.slides.length) { toast('Add images first'); location.hash = `#/p/${projectId}`; return null; }
  const appendRec = appendId ? await db.get('recordings', appendId) : null;
  const { w: W, h: H } = appendRec ? { w: appendRec.w, h: appendRec.h } : stageSize(project.aspect);
  const vertical = H > W;
  const bg = appendRec?.bg || settings.bg;
  const rotOf = (id) => project.slides.find((s) => s.id === id)?.rot || 0;
  const saveProject = debounce(() => db.put('projects', project), 500);

  let st;
  if (appendRec) {
    st = structuredClone(new Player(appendRec.events).seek(Infinity));
    const curId = st.slides[st.i]?.id;
    st.slides = project.slides.map(slideLite);
    const ni = st.slides.findIndex((s) => s.id === curId);
    st.i = Math.max(0, ni);
    if (ni < 0) st.v = fitView(rotOf(st.slides[0].id));
    st.laser = null;
  } else {
    st = emptyState(project.slides);
  }

  // ---------- state ----------
  let tool = TOOLS.some((t) => t[0] === settings.tool) ? settings.tool : 'pen';
  let color = settings.color;
  let sizeIdx = settings.size;
  let recording = false, paused = false, finishing = false, done = false, autoPaused = false;
  let t0 = 0, pausedTotal = 0, pauseStart = 0;
  let log = [];
  let dirty = true;
  let mic = null, mr = null, chunks = [], meter = null, wake = null;

  const clock = () => {
    if (!recording) return 0;
    const now = paused ? pauseStart : performance.now();
    return (now - t0 - pausedTotal) / 1000;
  };

  function emit(ev) {
    st = applyEvent(st, ev);
    if (recording) {
      ev.t = Math.round(clock() * 1000) / 1000;
      log.push(ev);
    }
    dirty = true;
  }

  // ---------- DOM ----------
  const canvas = h('canvas', { class: 'stage', width: W, height: H });
  const painter = new Painter(canvas, bg);
  painter.cache.onload = () => { dirty = true; };
  const timerEl = h('div', { class: 'rec-timer' }, '0:00');
  const meterBar = h('div', { class: 'meter-fill' });
  const recBtn = h('button', { class: 'rec-btn', title: 'Record', onclick: () => (recording ? stopRec(true) : startRec()) }, icon('rec', 30));
  const pauseBtn = h('button', { class: 'rail-btn', title: 'Pause', disabled: true, onclick: togglePause }, icon('pause'));
  const counter = h('div', { class: 'slide-count' });
  const prevBtn = h('button', { class: 'rail-btn', title: 'Previous image', onclick: () => go(-1) }, icon('left', 26));
  const nextBtn = h('button', { class: 'rail-btn', title: 'Next image', onclick: () => go(1) }, icon('right', 26));
  const toolBtn = h('button', { class: 'rail-btn', title: 'Tools', onclick: () => toggleFly(toolFly, toolBtn) });
  const colorBtn = h('button', { class: 'rail-btn', title: 'Colour & size', onclick: () => toggleFly(colorFly, colorBtn) }, h('span', { class: 'swatch' }));
  const scriptBtn = h('button', { class: 'rail-btn', title: 'Script on/off', onclick: () => script.setVisible(!script.visible) }, icon('text'));
  const countdownEl = h('div', { class: 'countdown hidden' });
  const hint = h('div', { class: 'stage-hint' }, 'Double-tap a photo to see it big (before recording) · Two fingers: zoom · ◀ ▶ buttons: previous / next');
  const clipNote = h('div', { class: 'clip-note hidden' });

  const toolFly = h('div', { class: 'flyout hidden' },
    h('div', { class: 'fly-grid' }, TOOLS.map(([id, ic, label]) =>
      h('button', { class: 'fly-item', 'data-tool': id, title: label, onclick: () => { setTool(id); closeFly(); } }, icon(ic, 24), h('span', null, label)))),
    h('div', { class: 'fly-label' }, 'Voice effect for new takes (change later in the editor)'),
    h('div', { class: 'chips fly-chips' }, Object.entries(VOICE_PRESETS).map(([id, p]) =>
      h('button', {
        class: `chip ${(settings.voiceFx?.preset || 'natural') === id ? 'on' : ''}`,
        onclick: (e) => {
          settings.voiceFx = { ...p.fx };
          e.currentTarget.parentNode.querySelectorAll('.chip').forEach((c) => c.classList.toggle('on', c === e.currentTarget));
          toast(`Voice effect: ${p.label}`);
        },
      }, p.label))));
  const colorFly = h('div', { class: 'flyout hidden' },
    h('div', { class: 'fly-colors' }, COLORS.map((c) =>
      h('button', { class: 'color-dot', 'data-color': c, style: { background: c }, title: c, onclick: () => { setColor(c); closeFly(); } }))),
    h('div', { class: 'fly-sizes' }, SIZES.map((s, i) =>
      h('button', { class: 'size-dot', 'data-size': i, onclick: () => { setSize(i); closeFly(); } }, h('span', { style: { width: `${6 + i * 6}px`, height: `${6 + i * 6}px` } })))));

  const script = buildScript(project.script || '');
  const stageWrap = h('div', { class: 'stage-wrap' }, canvas, clipNote, script.el, countdownEl, hint);

  const leftRail = h('div', { class: 'rail rail-left' },
    h('button', { class: 'rail-btn', title: 'Back', onclick: leave }, icon('back')),
    timerEl,
    recBtn,
    pauseBtn,
    h('div', { class: 'meter' }, meterBar),
    h('div', { class: 'grow' }),
    prevBtn, counter, nextBtn);

  const rightRail = h('div', { class: 'rail rail-right' },
    toolBtn,
    colorBtn,
    h('button', { class: 'rail-btn', title: 'Undo last mark', onclick: () => emit({ k: 'undo' }) }, icon('undo')),
    h('button', { class: 'rail-btn danger', title: 'Delete all marks', onclick: () => emit({ k: 'clear' }) }, icon('trash')),
    h('button', { class: 'rail-btn', title: 'Rotate image', onclick: rotate }, icon('rotate')),
    h('button', { class: 'rail-btn', title: 'Fit to screen', onclick: () => animateView(fitView(st.v.r)) }, icon('fit')),
    h('button', { class: 'rail-btn page-btn', title: 'Add a blank page (white / black)', onclick: addPage }, icon('page')),
    scriptBtn);

  const rotateHint = h('div', { class: 'rotate-hint' },
    icon('rotate', 40), h('p', null, vertical ? 'This is a 9:16 video — hold your phone upright (portrait)' : 'Turn your phone sideways (landscape) for recording'),
    h('button', { class: 'btn small', onclick: (e) => e.target.closest('.rotate-hint').classList.add('dismissed') }, 'Continue anyway'));

  const screenEl = h('div', { class: `rec-screen ${vertical ? 'vertical' : ''}` }, leftRail, stageWrap, rightRail, toolFly, colorFly, rotateHint);
  root.append(screenEl);
  document.body.classList.add('no-scroll');

  // ---------- toolbar state ----------
  function setTool(id) {
    tool = id; settings.tool = id;
    const t = TOOLS.find((x) => x[0] === id);
    toolBtn.innerHTML = '';
    toolBtn.append(icon(t[1]));
    toolBtn.title = t[2];
    toolFly.querySelectorAll('.fly-item').forEach((b) => b.classList.toggle('on', b.dataset.tool === id));
  }
  function setColor(c) {
    color = c; settings.color = c;
    colorBtn.querySelector('.swatch').style.background = c;
    colorFly.querySelectorAll('.color-dot').forEach((b) => b.classList.toggle('on', b.dataset.color === c));
  }
  function setSize(i) {
    sizeIdx = i; settings.size = i;
    colorFly.querySelectorAll('.size-dot').forEach((b) => b.classList.toggle('on', +b.dataset.size === i));
  }
  setTool(tool); setColor(color); setSize(sizeIdx);

  function toggleFly(fly, btn) {
    const open = fly.classList.contains('hidden');
    closeFly();
    if (!open) return;
    fly.classList.remove('hidden');
    const r = btn.getBoundingClientRect();
    const fh = fly.offsetHeight, fw = fly.offsetWidth;
    if (vertical) {
      fly.style.top = `${Math.max(8, r.top - fh - 10)}px`;
      fly.style.left = `${clamp(r.left + r.width / 2 - fw / 2, 8, window.innerWidth - fw - 8)}px`;
    } else {
      fly.style.top = `${clamp(r.top + r.height / 2 - fh / 2, 8, window.innerHeight - fh - 8)}px`;
    }
  }
  function closeFly() { toolFly.classList.add('hidden'); colorFly.classList.add('hidden'); }

  function updateCounter() {
    counter.textContent = `${st.i + 1}/${st.slides.length}`;
    prevBtn.disabled = st.i === 0;
    nextBtn.disabled = st.i >= st.slides.length - 1;
  }

  function go(d) {
    const i = clamp(st.i + d, 0, st.slides.length - 1);
    if (i === st.i) { toast(d > 0 ? 'This is the last item' : 'This is the first item', 1200); return; }
    cancelGesture();
    emit({ k: 'slide', i, v: fitView(rotOf(st.slides[i].id)) });
    updateCounter();
    syncClip();
    syncPen();
  }

  // White page -> black pen, black page -> white pen; your colour comes back after.
  let colorBeforeBlank = null;
  function syncPen() {
    const sl = st.slides[st.i];
    if (sl?.blank) {
      if (colorBeforeBlank == null) colorBeforeBlank = color;
      const want = sl.blank === 'white' ? '#141210' : '#ffffff';
      if (color !== want) setColor(want);
      if (tool === 'move' || tool === 'laser') setTool('pen');
    } else if (colorBeforeBlank != null) {
      setColor(colorBeforeBlank);
      colorBeforeBlank = null;
    }
  }

  // Inserts a blank page right after the current one — also while recording.
  async function addPage() {
    closeFly();
    const wasRunning = recording && !paused;
    if (wasRunning) togglePause();
    const c = await askBlankColor(modal, h);
    if (c) {
      const item = await makeBlankPage(c, vertical ? '9:16' : '16:9');
      const at = st.i + 1;
      const pIdx = project.slides.findIndex((x) => x.id === st.slides[st.i]?.id);
      project.slides.splice(pIdx < 0 ? project.slides.length : pIdx + 1, 0, item);
      project.updated = Date.now();
      await db.put('projects', project);
      await painter.cache.get(item.imageId);
      const slides = st.slides.map((x) => ({ ...x }));
      slides.splice(at, 0, slideLite(item));
      cancelGesture();
      emit({ k: 'slides', slides, i: at, v: fitView(0) });
      updateCounter();
      syncClip();
      syncPen();
      toast(c === 'white' ? 'White page added — pen is black' : 'Black page added — pen is white', 2200);
    }
    if (wasRunning && recording && paused) togglePause();
  }

  // On a video/audio clip the voice recording pauses; the clip plays at this
  // point in the final video. Swiping on resumes recording.
  function syncClip() {
    const sl = st.slides[st.i];
    const clip = isClip(sl);
    clipNote.classList.toggle('hidden', !clip);
    if (clip) {
      clipNote.innerHTML = '';
      clipNote.append(icon(sl.kind === 'video' ? 'film' : 'music', 22),
        h('div', null,
          h('b', null, `${sl.kind === 'video' ? 'Video' : 'Audio'} clip · ${fmt(sl.dur)}`),
          h('div', null, recording ? 'Recording paused. This clip will play here in the final video. Press Next ▶ to continue.' : 'This clip will play here in the final video.')));
    }
    if (!recording) return;
    if (clip && !paused) { togglePause(); autoPaused = true; } else if (!clip && autoPaused) { autoPaused = false; if (paused) togglePause(); }
  }

  function rotate() {
    const r = (st.v.r + 1) % 4;
    emit({ k: 'view', v: fitView(r) });
    const s = project.slides.find((x) => x.id === st.slides[st.i].id);
    if (s) { s.rot = r; saveProject(); }
  }

  let anim = null;
  function animateView(target) {
    cancelAnimationFrame(anim);
    const from = { ...st.v };
    const start = performance.now();
    const step = () => {
      const k = Math.min(1, (performance.now() - start) / 260);
      const e = 1 - (1 - k) ** 3;
      emit({ k: 'view', v: rv({ s: from.s + (target.s - from.s) * e, x: from.x + (target.x - from.x) * e, y: from.y + (target.y - from.y) * e, r: target.r }) });
      if (k < 1) anim = requestAnimationFrame(step);
    };
    step();
  }

  // ---------- layout ----------
  function layout() {
    const r = stageWrap.getBoundingClientRect();
    const sc = Math.min(r.width / W, r.height / H);
    canvas.style.width = `${Math.floor(W * sc)}px`;
    canvas.style.height = `${Math.floor(H * sc)}px`;
  }
  const ro = new ResizeObserver(layout);
  ro.observe(stageWrap);

  // ---------- touch / pen input ----------
  const ptrs = new Map();
  let g = null; // current gesture

  const toStage = (e) => {
    const r = canvas.getBoundingClientRect();
    return [(e.clientX - r.left) * W / r.width, (e.clientY - r.top) * H / r.height];
  };
  const M = () => viewMatrix(W, H, st.slides[st.i], st.v);
  const toImg = (p) => rp(invertM(M(), p));

  // Double-tap (only before recording starts) opens the photo full screen.
  let lastTap = null;
  function noteTap(p, id) {
    if (recording) { lastTap = null; return; }
    const now = performance.now();
    if (lastTap && now - lastTap.t < 380 && dist(p, lastTap.p) < W * 0.06) {
      for (const x of [lastTap.id, id]) if (x) emit({ k: 'adel', id: x });
      lastTap = null;
      const sl = st.slides[st.i];
      if (sl) openPeek({ imageId: sl.imageId, w: sl.w, h: sl.h, rot: st.v.r, bitmap: painter.cache.peek(sl.imageId), label: `${st.i + 1}/${st.slides.length} ${sl.name || ''}` });
      return;
    }
    lastTap = { t: now, p, id };
  }

  function cancelGesture() {
    if (g?.type === 'draw') emit({ k: 'adel', id: g.id });
    if (g?.type === 'laser') emit({ k: 'laser', p: null });
    g = null;
  }

  function startPinch() {
    const [a, b] = [...ptrs.values()];
    g = { type: 'pinch', d0: Math.max(10, dist(a, b)), v0: { ...st.v }, q: invertM(M(), mid(a, b)), c0: mid(a, b), t0: performance.now(), c1: mid(a, b), d1: dist(a, b) };
  }

  function movePinch() {
    const [a, b] = [...ptrs.values()];
    const c = mid(a, b);
    g.c1 = c; g.d1 = dist(a, b);
    const v = { s: clamp(g.v0.s * dist(a, b) / g.d0, 0.4, 14), x: 0, y: 0, r: g.v0.r };
    const p0 = applyM(viewMatrix(W, H, st.slides[st.i], v), g.q);
    v.x = c[0] - p0[0];
    v.y = c[1] - p0[1];
    emit({ k: 'view', v: rv(v) });
  }

  canvas.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    closeFly();
    hint.classList.add('gone');
    try { canvas.setPointerCapture(e.pointerId); } catch { /* ignore */ }
    ptrs.set(e.pointerId, toStage(e));
    if (ptrs.size === 2) { cancelGesture(); startPinch(); return; }
    if (ptrs.size > 2) return;
    const p = ptrs.get(e.pointerId);
    if (tool === 'move') {
      g = { type: 'pan', last: p, start: p, t0: performance.now(), v0: { ...st.v } };
    } else if (tool === 'laser') {
      g = { type: 'laser' };
      emit({ k: 'laser', p: toImg(p) });
    } else {
      const sc = M().sc;
      let w = SIZES[sizeIdx] * (W / 1920) * 1.6 / sc;
      if (tool === 'hl') w *= 3.5;
      const id = uid();
      emit({ k: 'a+', a: { id, tool, color, w: Math.round(w * 100) / 100, pts: [toImg(p)] } });
      g = { type: 'draw', id, start: p, moved: false, sc, t0: performance.now(), last: p };
    }
  });

  canvas.addEventListener('pointermove', (e) => {
    if (!ptrs.has(e.pointerId) || !g) return;
    const evs = e.getCoalescedEvents?.() || [e];
    ptrs.set(e.pointerId, toStage(e));
    if (g.type === 'pinch') { if (ptrs.size >= 2) movePinch(); return; }
    const p = toStage(e);
    if (g.type === 'pan') {
      emit({ k: 'view', v: rv({ ...st.v, x: st.v.x + p[0] - g.last[0], y: st.v.y + p[1] - g.last[1] }) });
      g.last = p;
    } else if (g.type === 'laser') {
      emit({ k: 'laser', p: toImg(p) });
    } else if (g.type === 'draw') {
      g.last = p;
      if (!g.moved && dist(p, g.start) > 7) g.moved = true;
      if (!g.moved) return;
      if (SHAPES.has(tool)) emit({ k: 'a~', id: g.id, p: toImg(p) });
      else for (const ce of evs) emit({ k: 'a~', id: g.id, p: toImg(toStage(ce)) });
    }
  });

  function endPointer(e) {
    if (!ptrs.has(e.pointerId)) return;
    ptrs.delete(e.pointerId);
    if (!g) return;
    if (g.type === 'pinch') {
      if (ptrs.size < 2) g = { type: 'idle' };
    } else if (g.type === 'pan') {
      if (dist(g.last, g.start) < 8 && performance.now() - g.t0 < 300) noteTap(g.start, null);
      g = null;
    } else if (g.type === 'laser') {
      emit({ k: 'laser', p: null });
      g = null;
    } else if (g.type === 'draw') {
      const tapped = !g.moved && performance.now() - g.t0 < 300;
      if (!g.moved && SHAPES.has(tool)) {
        if (tool === 'tick' || tool === 'cross') {
          // A tap stamps a ready-made tick / cross.
          const s = (W / 1920) * 80 / g.sc;
          const [x, y] = invertM(M(), g.start);
          const hh = tool === 'tick' ? s * 0.8 : s;
          emit({ k: 'aset', id: g.id, pts: [rp([x - s / 2, y - hh / 2]), rp([x + s / 2, y + hh / 2])] });
        } else {
          emit({ k: 'adel', id: g.id });
        }
      }
      if (tapped) noteTap(g.start, g.id);
      g = null;
    } else {
      g = null;
    }
    if (ptrs.size === 0) g = null;
  }
  canvas.addEventListener('pointerup', endPointer);
  canvas.addEventListener('pointercancel', endPointer);

  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const c = toStage(e);
    const q = invertM(M(), c);
    const v = { ...st.v, s: clamp(st.v.s * (e.deltaY < 0 ? 1.12 : 1 / 1.12), 0.4, 14), x: 0, y: 0 };
    const p0 = applyM(viewMatrix(W, H, st.slides[st.i], v), q);
    v.x = c[0] - p0[0];
    v.y = c[1] - p0[1];
    emit({ k: 'view', v: rv(v) });
  }, { passive: false });

  function onKey(e) {
    if (e.target.closest('input,textarea')) return;
    if (e.key === 'ArrowRight' || e.key === 'PageDown') go(1);
    else if (e.key === 'ArrowLeft' || e.key === 'PageUp') go(-1);
    else if (e.key === 'z' && (e.ctrlKey || e.metaKey)) emit({ k: 'undo' });
    else if (e.key === ' ') { e.preventDefault(); if (recording) togglePause(); else startRec(); }
    else return;
    e.preventDefault();
  }
  window.addEventListener('keydown', onKey);

  // ---------- microphone ----------
  async function ensureMic() {
    if (mic) return mic;
    mic = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
    });
    try {
      const actx = new AudioContext();
      const src = actx.createMediaStreamSource(mic);
      const an = actx.createAnalyser();
      an.fftSize = 1024;
      src.connect(an);
      meter = { actx, an, buf: new Float32Array(an.fftSize), lvl: 0 };
    } catch { /* meter is optional */ }
    return mic;
  }
  ensureMic().catch(() => toast('Microphone permission is needed to record your voice', 4000));

  function countdown(n) {
    return new Promise((resolve) => {
      let k = n;
      countdownEl.classList.remove('hidden');
      const finish = () => { clearInterval(iv); countdownEl.classList.add('hidden'); countdownEl.onclick = null; resolve(); };
      countdownEl.onclick = finish;
      countdownEl.textContent = k;
      const iv = setInterval(() => {
        k--;
        if (k <= 0) finish(); else countdownEl.textContent = k;
      }, 800);
    });
  }

  async function startRec() {
    if (recording || finishing) return;
    try { await ensureMic(); } catch { toast('Allow microphone access, then try again', 4000); return; }
    meter?.actx.resume();
    closeFly();
    recBtn.disabled = true;
    if (settings.countdown) await countdown(3);
    const mime = pickMime(AUDIO_MIMES);
    try {
      mr = new MediaRecorder(mic, mime ? { mimeType: mime, audioBitsPerSecond: 128000 } : undefined);
    } catch {
      mr = new MediaRecorder(mic);
    }
    chunks = [];
    mr.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    await new Promise((res) => { mr.onstart = res; mr.start(1000); });
    t0 = performance.now();
    pausedTotal = 0;
    paused = false;
    recording = true;
    const snapSt = structuredClone(st);
    snapSt.laser = null;
    log = [{ t: 0, k: 'snap', st: snapSt }];
    wake = await keepAwake();
    recBtn.disabled = false;
    if (script.visible) script.play();
    syncUi();
    syncClip();
  }

  function togglePause() {
    if (!recording) return;
    autoPaused = false;
    if (!paused) {
      mr.pause();
      paused = true;
      pauseStart = performance.now();
      script.pause();
    } else {
      mr.resume();
      pausedTotal += performance.now() - pauseStart;
      paused = false;
    }
    syncUi();
  }

  async function stopRec(openEditor) {
    if (!recording || finishing) return null;
    finishing = true;
    const dur = clock();
    script.pause();
    const blob = await new Promise((res) => {
      mr.onstop = () => res(new Blob(chunks, { type: mr.mimeType || 'audio/webm' }));
      if (mr.state !== 'inactive') mr.stop(); else mr.onstop();
    });
    recording = false;
    paused = false;
    wake?.release?.().catch(() => {});
    syncUi();
    if (dur < 0.6 || !blob.size) { finishing = false; toast('Too short — not saved'); return null; }
    const id = await saveTake(blob, dur);
    finishing = false;
    done = true;
    if (openEditor) location.hash = `#/edit/${id}`;
    return id;
  }

  async function saveTake(blob, dur) {
    const audioId = await db.putBlob(blob);
    if (appendRec) {
      const off = appendRec.duration;
      for (const ev of log) ev.t = Math.round((ev.t + off) * 1000) / 1000;
      appendRec.events.push(...log);
      appendRec.audioParts.push({ id: audioId, start: off, mime: blob.type });
      appendRec.duration = Math.round((off + dur) * 1000) / 1000;
      appendRec.updated = Date.now();
      await db.put('recordings', appendRec);
      return appendRec.id;
    }
    const recs = await db.byIndex('recordings', 'projectId', projectId);
    const rec = {
      id: uid(), projectId, name: `Take ${recs.length + 1}`, created: Date.now(), updated: Date.now(),
      w: W, h: H, bg, duration: Math.round(dur * 1000) / 1000, events: log,
      audioParts: [{ id: audioId, start: 0, mime: blob.type }],
      edl: { ...defaultEdl(), voiceFx: { ...(settings.voiceFx || VOICE_PRESETS.natural.fx) } },
    };
    await db.put('recordings', rec);
    project.updated = Date.now();
    await db.put('projects', project);
    return rec.id;
  }

  function syncUi() {
    screenEl.classList.toggle('is-rec', recording && !paused);
    screenEl.classList.toggle('is-paused', recording && paused);
    recBtn.innerHTML = '';
    recBtn.append(icon(recording ? 'stop' : 'rec', recording ? 26 : 30));
    recBtn.title = recording ? 'Stop & edit' : 'Record';
    pauseBtn.disabled = !recording;
    pauseBtn.innerHTML = '';
    pauseBtn.append(icon(paused ? 'mic' : 'pause'));
    pauseBtn.title = paused ? 'Resume' : 'Pause';
  }

  async function leave() {
    if (recording) {
      await stopRec(true);
      return;
    }
    location.hash = appendRec ? `#/edit/${appendRec.id}` : `#/p/${projectId}`;
  }

  // ---------- teleprompter ----------
  function buildScript(text) {
    let visible = settings.scriptOn && !!text.trim();
    let playing = false, touching = false, acc = 0, touchTimer = 0;
    const body = h('div', { class: 'sp-body' });
    const paras = text.split(/\n\s*\n|\n/).map((t) => t.trim()).filter(Boolean);
    if (paras.length) {
      paras.forEach((t) => body.append(h('p', null, t)));
      body.append(h('div', { class: 'sp-end' }, '— end of script —'));
    } else {
      body.append(h('p', { class: 'muted' }, 'No script yet. Go back to the project and paste your script.'));
    }
    const speedEl = h('span', { class: 'sp-val' });
    const playBtn = h('button', { title: 'Auto-scroll', onclick: () => (playing ? pause() : play()) });
    const panel = h('div', { class: `script-panel pos-${settings.scriptPos}` },
      h('div', { class: 'sp-head' },
        playBtn,
        h('button', { title: 'Slower', onclick: () => setSpeed(settings.scriptSpeed - 1) }, icon('minus', 16)),
        speedEl,
        h('button', { title: 'Faster', onclick: () => setSpeed(settings.scriptSpeed + 1) }, icon('plus', 16)),
        h('span', { class: 'sp-sep' }),
        h('button', { title: 'Smaller text', onclick: () => setFont(settings.scriptFont - 2) }, 'A−'),
        h('button', { title: 'Bigger text', onclick: () => setFont(settings.scriptFont + 2) }, 'A+'),
        h('button', { title: 'Move panel', onclick: () => { settings.scriptPos = settings.scriptPos === 'top' ? 'bottom' : 'top'; panel.className = `script-panel pos-${settings.scriptPos}`; } }, icon('updown', 16)),
        h('button', { title: 'Hide script', onclick: () => setVisible(false) }, icon('close', 16))),
      body,
      h('div', { class: 'sp-note' }, 'Only you see this — not recorded'));

    body.addEventListener('pointerdown', () => { touching = true; clearTimeout(touchTimer); });
    const release = () => { clearTimeout(touchTimer); touchTimer = setTimeout(() => { touching = false; }, 700); };
    body.addEventListener('pointerup', release);
    body.addEventListener('pointercancel', release);
    body.addEventListener('touchend', release);

    function setSpeed(v) { settings.scriptSpeed = clamp(v, 1, 20); speedEl.textContent = settings.scriptSpeed; }
    function setFont(v) { settings.scriptFont = clamp(v, 14, 44); panel.style.setProperty('--fs', `${settings.scriptFont}px`); }
    function renderPlay() { playBtn.innerHTML = ''; playBtn.append(icon(playing ? 'pause' : 'play', 16)); }
    function play() { playing = true; renderPlay(); }
    function pause() { playing = false; renderPlay(); }
    function setVisible(v) {
      visible = v;
      settings.scriptOn = v;
      panel.classList.toggle('hidden', !v);
      scriptBtn.classList.toggle('on', v);
      if (!v) pause();
    }
    function tick(dt) {
      if (!visible || !playing || touching) return;
      acc += settings.scriptSpeed * 4.5 * dt * (settings.scriptFont / 22);
      const whole = Math.floor(acc);
      if (whole) { body.scrollTop += whole; acc -= whole; }
    }
    setSpeed(settings.scriptSpeed);
    setFont(settings.scriptFont);
    renderPlay();
    const api = { el: panel, tick, play, pause, setVisible, get visible() { return visible; } };
    queueMicrotask(() => setVisible(visible));
    return api;
  }

  // ---------- main loop ----------
  await painter.ensure(st);
  updateCounter();
  syncClip();
  syncPen();
  layout();
  let raf = 0, last = performance.now();
  const loop = (now) => {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    if (dirty) { painter.draw(st); dirty = false; }
    script.tick(dt);
    if (recording) timerEl.textContent = fmt(clock() + (appendRec?.duration || 0));
    if (meter) {
      meter.an.getFloatTimeDomainData(meter.buf);
      let s = 0;
      for (const v of meter.buf) s += v * v;
      const lvl = Math.min(1, Math.sqrt(s / meter.buf.length) * 4);
      meter.lvl = Math.max(lvl, meter.lvl * 0.9);
      meterBar.style.transform = `${vertical ? "scaleX" : "scaleY"}(${meter.lvl.toFixed(3)})`;
    }
    raf = requestAnimationFrame(loop);
  };
  raf = requestAnimationFrame(loop);
  if (appendRec) timerEl.textContent = fmt(appendRec.duration);

  // ---------- cleanup ----------
  return async () => {
    if (recording && !done) {
      const id = await stopRec(false);
      if (id) toast('Take saved');
    }
    cancelAnimationFrame(raf);
    cancelAnimationFrame(anim);
    ro.disconnect();
    window.removeEventListener('keydown', onKey);
    saveProject.flush();
    mic?.getTracks().forEach((t) => t.stop());
    meter?.actx.close().catch(() => {});
    wake?.release?.().catch(() => {});
    document.body.classList.remove('no-scroll');
    exitImmersive();
  };
}

// Used by the editor's "continue recording" button.
export function openRecorder(projectId, appendId, orientation = 'landscape') {
  enterImmersive(orientation);
  location.hash = appendId ? `#/rec/${projectId}/${appendId}` : `#/rec/${projectId}`;
}

