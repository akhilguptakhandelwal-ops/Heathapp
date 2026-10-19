// Editor: preview, timeline, cuts, voice/music volume, music, export.
import { h, toast, fmt, clamp, uid, debounce, modal, promptBox, confirmBox, pickFiles, keepAwake, downloadBlob, shareBlob, extFor } from './util.js';
import { icon } from './icons.js';
import { db, deleteRecording } from './db.js';
import { Painter, Player } from './renderer.js';
import { defaultEdl, TimeMap, decodeBlob, analyzeVoice, analyzeMusic, computeCurves, startMix, pickMime, VIDEO_MIMES } from './mix.js';
import { openRecorder } from './recorder.js';

const PAD = 14;
const LANES = { ruler: [0, 18], video: [22, 30], voice: [58, 48], music: [112, 32] };
const TL_H = 150;
const C = {
  bg: '#16130f', lane: '#1f1a15', ruler: '#8c806d', block1: '#3d3327', block2: '#30281f', blockText: '#e9dcc6',
  voice: '#d39b47', voiceMute: '#4b4237', music: '#8aa47f', join: '#c94a32', sel: 'rgba(211,155,71,0.20)', selEdge: '#e2b066',
  head: '#f4ede2', label: 'rgba(22,19,15,0.85)',
};

function slideRanges(events, D) {
  const out = [];
  let slides = null, cur = null, n = 0, start = 0;
  const push = (t) => {
    if (cur == null || t - start <= 0) return;
    const last = out[out.length - 1];
    if (last && last.id === cur && Math.abs(last.b - start) < 1e-6) last.b = t;
    else out.push({ a: start, b: t, id: cur, n });
  };
  for (const ev of events) {
    if (ev.k === 'snap') {
      push(ev.t);
      slides = ev.st.slides;
      cur = slides[ev.st.i]?.id; n = ev.st.i + 1; start = ev.t;
    } else if (ev.k === 'slide' && slides) {
      push(ev.t);
      cur = slides[ev.i]?.id; n = ev.i + 1; start = ev.t;
    }
  }
  push(D);
  return out;
}

export async function editorScreen(root, { recId }) {
  const rec = await db.get('recordings', recId);
  if (!rec) { location.hash = '#/'; return null; }
  const project = await db.get('projects', rec.projectId);
  let edl = { ...defaultEdl(), ...(rec.edl || {}) };

  const loading = h('div', { class: 'loading' }, h('div', { class: 'spinner' }), 'Loading take…');
  root.append(loading);

  const actx = new AudioContext();
  const parts = [];
  for (const p of rec.audioParts) {
    try {
      const blob = await db.getBlob(p.id);
      if (blob) parts.push({ start: p.start, buf: await decodeBlob(actx, blob) });
    } catch (e) {
      console.error(e);
      toast('Could not read part of the voice recording', 4000);
    }
  }
  const D = rec.duration;
  const va = analyzeVoice(parts, D);
  const ranges = slideRanges(rec.events, D);

  let musicBuf = null, musicInfo = null, musicLoaded = null;
  async function syncMusic() {
    const id = edl.music?.blobId || null;
    if (id === musicLoaded) return;
    musicLoaded = id;
    musicBuf = null; musicInfo = null;
    if (!id) return;
    try {
      const b = await db.getBlob(id);
      if (b) { musicBuf = await decodeBlob(actx, b); musicInfo = analyzeMusic(musicBuf); }
    } catch {
      toast('This music file could not be read', 4000);
    }
  }
  await syncMusic();

  let M = new TimeMap(D, edl.cuts);
  let env = null;
  const recalcEnv = () => { env = computeCurves(M, edl, va, !!(musicBuf && edl.music), 0, 20); };
  recalcEnv();

  // ---------- preview ----------
  const images = new Map();
  const allSlides = [];
  for (const ev of rec.events) if (ev.k === 'snap') allSlides.push(...ev.st.slides);
  const cv = h('canvas', { class: 'preview-cv', width: rec.w, height: rec.h });
  const painter = new Painter(cv, rec.bg, images);
  await painter.load(allSlides);
  const player = new Player(rec.events);

  let T = 0;
  let playing = false, mix = null;
  let sel = null;
  const undoStack = [], redoStack = [];

  const saveRec = debounce(() => { rec.edl = edl; rec.updated = Date.now(); return db.put('recordings', rec); }, 400);

  function renderFrame() { painter.draw(player.seek(M.toSrc(T))); }

  // ---------- playback ----------
  function play() {
    if (!M.outD) return;
    if (T >= M.outD - 0.05) T = 0;
    actx.resume();
    mix = startMix(actx, actx.destination, M, parts, musicBuf, edl, va, T);
    playing = true;
    syncTransport();
  }
  function pause() {
    if (mix) { T = clamp(mix.time(), 0, M.outD); mix.stop(); mix = null; }
    playing = false;
    syncTransport();
  }
  function seek(t) {
    T = clamp(t, 0, M.outD);
    if (playing) { mix.stop(); mix = startMix(actx, actx.destination, M, parts, musicBuf, edl, va, T); }
    renderFrame();
    drawTL();
    updateTime();
  }

  // ---------- edits ----------
  async function changed() {
    await syncMusic();
    M = new TimeMap(D, edl.cuts);
    recalcEnv();
    T = clamp(T, 0, M.outD);
    if (sel) { sel.a = clamp(sel.a, 0, M.outD); sel.b = clamp(sel.b, 0, M.outD); if (sel.b - sel.a < 0.05) sel = null; }
    saveRec();
    clampScroll();
    renderPanels();
    renderSel();
    renderFrame();
    drawTL();
    updateTime();
    if (playing) { mix.stop(); mix = startMix(actx, actx.destination, M, parts, musicBuf, edl, va, T); }
  }
  function commit(fn) {
    undoStack.push(JSON.stringify(edl));
    redoStack.length = 0;
    fn(edl);
    changed();
  }
  function undo() {
    if (!undoStack.length) return toast('Nothing to undo');
    redoStack.push(JSON.stringify(edl));
    edl = JSON.parse(undoStack.pop());
    changed();
  }
  function redo() {
    if (!redoStack.length) return toast('Nothing to redo');
    undoStack.push(JSON.stringify(edl));
    edl = JSON.parse(redoStack.pop());
    changed();
  }

  function requireSel() {
    if (!sel) { toast('First select a part: long-press on the timeline and drag, or use Mark In / Mark Out'); return false; }
    return true;
  }
  function cutSel() {
    if (!requireSel()) return;
    const a = sel.a;
    const len = sel.b - sel.a;
    commit((e) => e.cuts.push(...M.outRangeToSrc(sel.a, sel.b)));
    sel = null;
    T = a;
    toast(`Cut ${len.toFixed(1)}s from video + voice`);
  }
  function gainSel(track, g, label) {
    if (!requireSel()) return;
    const gid = uid();
    const src = M.outRangeToSrc(sel.a, sel.b);
    commit((e) => src.forEach(([a, b]) => e.gains.push({ id: uid(), gid, track, a, b, g })));
    toast(label);
  }
  async function askVolume(title, start) {
    let val = start;
    const out = h('b', null, `${val}%`);
    const input = h('input', { type: 'range', min: 0, max: 300, step: 5, value: val, class: 'range', oninput: () => { val = +input.value; out.textContent = `${val}%`; } });
    const quick = h('div', { class: 'quick' }, [0, 50, 100, 150, 200].map((q) =>
      h('button', { class: 'btn small', onclick: () => { input.value = q; input.dispatchEvent(new Event('input')); } }, `${q}%`)));
    const ok = await modal({
      title,
      body: h('div', { class: 'form' }, h('div', { class: 'vol-row' }, input, out), quick,
        h('p', { class: 'muted small' }, '100% = normal. 0% = silent. Above 100% = louder.')),
      buttons: [{ label: 'Cancel', value: false }, { label: 'Apply', kind: 'primary', value: true }],
    });
    return ok ? val / 100 : null;
  }

  // ---------- DOM ----------
  const playBtn = h('button', { class: 'play-btn', title: 'Play / pause', onclick: () => (playing ? pause() : play()) });
  const timeEl = h('div', { class: 'time mono' });
  const undoBtn = h('button', { class: 'icon-btn', title: 'Undo', onclick: undo }, icon('undo'));
  const redoBtn = h('button', { class: 'icon-btn', title: 'Redo', onclick: redo }, icon('redo'));
  const tl = h('canvas', { class: 'tl' });
  const selInfo = h('div', { class: 'sel-info muted small' });
  const actions = h('div', { class: 'sel-actions' },
    h('button', { class: 'btn danger-soft', onclick: cutSel }, icon('scissors', 18), 'Cut video + voice'),
    h('button', { class: 'btn', onclick: () => gainSel('voice', 0, 'Voice removed in this part (video stays)') }, icon('mute', 18), 'Delete voice only'),
    h('button', {
      class: 'btn', onclick: async () => {
        if (!requireSel()) return;
        const g = await askVolume('Voice volume for selected part', 150);
        if (g != null) gainSel('voice', g, `Voice set to ${Math.round(g * 100)}% here`);
      },
    }, icon('volume', 18), 'Voice volume'),
    h('button', {
      class: 'btn', onclick: async () => {
        if (!requireSel()) return;
        if (!edl.music) return toast('Add music first (below)');
        const g = await askVolume('Music volume for selected part', 50);
        if (g != null) gainSel('music', g, `Music set to ${Math.round(g * 100)}% here`);
      },
    }, icon('music', 18), 'Music volume'));
  const mixCard = h('section', { class: 'card' });
  const editsCard = h('section', { class: 'card' });
  const exportRow = h('div', { class: 'export-row' });

  const nameBtn = h('button', {
    class: 'title-btn', onclick: async () => {
      const n = await promptBox('Rename take', rec.name);
      if (n) { rec.name = n; nameBtn.firstChild.textContent = n; saveRec(); }
    },
  }, rec.name, icon('edit', 16));

  loading.remove();
  root.append(h('div', { class: 'page editor' },
    h('header', { class: 'topbar' },
      h('a', { class: 'icon-btn ghost', href: `#/p/${rec.projectId}`, title: 'Back' }, icon('back')),
      nameBtn,
      h('span', { class: 'grow' }),
      h('button', { class: 'btn primary', onclick: exportVideo }, icon('download', 18), 'Export')),
    h('div', { class: 'ed-main' },
      h('div', { class: 'ed-left' },
        h('div', { class: 'preview' }, cv),
        h('div', { class: 'transport' },
          h('button', { class: 'icon-btn', title: 'Go to start', onclick: () => seek(0) }, icon('toStart')),
          playBtn, timeEl, h('span', { class: 'grow' }), undoBtn, redoBtn)),
      h('div', { class: 'ed-right' },
        h('section', { class: 'card tl-card' },
          h('div', { class: 'tl-wrap' }, tl),
          h('div', { class: 'tl-tools' },
            h('button', { class: 'icon-btn', title: 'Zoom out', onclick: () => zoom(1 / 1.6) }, icon('zout', 20)),
            h('button', { class: 'icon-btn', title: 'Zoom in', onclick: () => zoom(1.6) }, icon('zin', 20)),
            h('button', { class: 'btn small ghost', onclick: () => { pps = fitPps(); scrollX = 0; drawTL(); } }, 'Fit'),
            h('span', { class: 'grow' }),
            h('span', { class: 'muted small hint' }, 'Tap = jump · Long-press + drag = select')),
          h('div', { class: 'sel-bar' },
            h('button', { class: 'btn small', onclick: markIn }, '[ Mark In'),
            h('button', { class: 'btn small', onclick: markOut }, 'Mark Out ]'),
            h('button', { class: 'btn small ghost', onclick: () => { sel = null; renderSel(); drawTL(); } }, 'Clear'),
            selInfo),
          actions),
        mixCard,
        editsCard,
        h('section', { class: 'card' },
          h('div', { class: 'card-head' }, h('h2', null, icon('film', 18), 'Take')),
          exportRow,
          h('div', { class: 'btn-row' },
            h('button', { class: 'btn', onclick: () => { saveRec.flush(); openRecorder(rec.projectId, rec.id); } }, icon('mic', 18), 'Continue recording'),
            h('button', {
              class: 'btn ghost danger-text', onclick: async () => {
                if (await confirmBox(`Delete ${rec.name}?`)) { await deleteRecording(rec); location.hash = `#/p/${rec.projectId}`; }
              },
            }, icon('trash', 18), 'Delete take')))))));

  function syncTransport() {
    playBtn.innerHTML = '';
    playBtn.append(icon(playing ? 'pause' : 'play', 26));
  }
  function updateTime() {
    timeEl.textContent = `${fmt(T, true)} / ${fmt(M.outD, true)}`;
    undoBtn.disabled = !undoStack.length;
    redoBtn.disabled = !redoStack.length;
  }

  // ---------- selection ----------
  function markIn() {
    sel = { a: T, b: sel && sel.b > T + 0.05 ? sel.b : Math.min(M.outD, T + 3) };
    renderSel(); drawTL();
  }
  function markOut() {
    sel = { a: sel && sel.a < T - 0.05 ? sel.a : Math.max(0, T - 3), b: T };
    if (sel.b - sel.a < 0.05) sel = null;
    renderSel(); drawTL();
  }
  function renderSel() {
    actions.classList.toggle('disabled', !sel);
    selInfo.textContent = sel ? `Selected ${fmt(sel.a, true)} → ${fmt(sel.b, true)} (${(sel.b - sel.a).toFixed(1)}s)` : 'Nothing selected';
  }

  // ---------- mix panel ----------
  function slider({ label, min, max, step, get, set, show }) {
    let before = null;
    const out = h('b', null, show(get()));
    const input = h('input', {
      type: 'range', class: 'range', min, max, step, value: get(),
      onpointerdown: () => { before = JSON.stringify(edl); },
      onfocus: () => { before = before || JSON.stringify(edl); },
      oninput: () => { set(+input.value); out.textContent = show(+input.value); recalcEnv(); drawTL(); },
      onchange: () => {
        undoStack.push(before || JSON.stringify(edl));
        redoStack.length = 0;
        before = null;
        changed();
      },
    });
    return h('div', { class: 'slider' }, h('div', { class: 'slider-head' }, h('span', null, label), out), input);
  }
  const pct = (v) => `${Math.round(v * 100)}%`;

  async function addMusic() {
    const [f] = await pickFiles('audio/*');
    if (!f) return;
    toast('Loading music…');
    try {
      await decodeBlob(actx, f);
    } catch {
      return toast('This audio file is not supported. Try MP3 or M4A.', 4000);
    }
    const blobId = await db.putBlob(f);
    commit((e) => {
      const prev = e.music;
      e.music = { blobId, name: f.name, start: prev?.start || 0, vol: prev?.vol ?? 0.35, loop: prev?.loop ?? true, fadeOut: prev?.fadeOut ?? true };
    });
  }

  function renderPanels() {
    mixCard.innerHTML = '';
    const m = edl.music;
    const seg = (opts, cur, onPick) => h('div', { class: 'seg' }, opts.map(([v, label]) =>
      h('button', { class: cur === v ? 'on' : '', onclick: () => onPick(v) }, label)));
    mixCard.append(
      h('div', { class: 'card-head' }, h('h2', null, icon('volume', 18), 'Sound mix')),
      slider({ label: 'Voice volume (whole video)', min: 0, max: 2, step: 0.05, get: () => edl.voiceVol, set: (v) => { edl.voiceVol = v; }, show: pct }),
      h('div', { class: 'music-row' },
        icon('music', 20),
        h('div', { class: 'grow' },
          h('div', null, m ? m.name : 'No music'),
          h('div', { class: 'muted small' }, m ? `Starts at ${fmt(m.start, true)}${m.loop ? ' · loops' : ''}` : 'Add background music from your phone')),
        h('button', { class: 'btn small', onclick: addMusic }, m ? 'Replace' : icon('plus', 16), m ? null : 'Add music'),
        m && h('button', { class: 'icon-btn ghost', title: 'Remove music', onclick: () => commit((e) => { e.music = null; e.gains = e.gains.filter((g) => g.track !== 'music'); }) }, icon('trash', 18))));
    if (m) {
      mixCard.append(
        slider({ label: 'Music volume (whole video)', min: 0, max: 1, step: 0.05, get: () => edl.music.vol, set: (v) => { edl.music.vol = v; }, show: pct }),
        h('div', { class: 'btn-row' },
          h('button', { class: 'btn small', onclick: () => commit((e) => { e.music.start = Math.round(T * 10) / 10; }) }, 'Start music at playhead'),
          h('button', { class: `btn small ${m.loop ? 'on' : ''}`, onclick: () => commit((e) => { e.music.loop = !e.music.loop; }) }, m.loop ? icon('check', 14) : null, 'Loop'),
          h('button', { class: `btn small ${m.fadeOut !== false ? 'on' : ''}`, onclick: () => commit((e) => { e.music.fadeOut = e.music.fadeOut === false; }) }, m.fadeOut !== false ? icon('check', 14) : null, 'Fade out at end')),
        h('label', { class: 'field-label' }, 'Who stays on top?'),
        seg([['voice', 'Voice on top'], ['music', 'Music on top'], ['equal', 'Equal']], edl.priority, (v) => commit((e) => { e.priority = v; })),
        h('p', { class: 'muted small' }, edl.priority === 'voice'
          ? 'Music automatically gets quieter whenever you speak.'
          : edl.priority === 'music' ? 'Voice gets quieter while music plays.' : 'No automatic adjustment.'),
        edl.priority !== 'equal' && slider({ label: 'Lower track plays at', min: 0, max: 1, step: 0.05, get: () => edl.duck, set: (v) => { edl.duck = v; }, show: pct }));
    }

    // edits list
    editsCard.innerHTML = '';
    editsCard.append(h('div', { class: 'card-head' }, h('h2', null, icon('scissors', 18), 'Edits'), h('span', { class: 'muted small' }, `Original ${fmt(D, true)} → final ${fmt(M.outD, true)}`)));
    const rows = [];
    for (const [a, b] of M.cuts) {
      rows.push(h('div', { class: 'edit-row' },
        h('span', { class: 'tag cut' }, 'Cut'),
        h('span', { class: 'grow' }, `${(b - a).toFixed(1)}s removed at ${fmt(M.toOut(a), true)}`),
        h('button', { class: 'btn small ghost', onclick: () => commit((e) => { e.cuts = e.cuts.filter(([x, y]) => y <= a + 1e-6 || x >= b - 1e-6); }) }, 'Restore')));
    }
    const groups = new Map();
    for (const g of edl.gains) { if (!groups.has(g.gid)) groups.set(g.gid, []); groups.get(g.gid).push(g); }
    for (const [gid, list] of groups) {
      const g0 = list[0];
      const a = Math.min(...list.map((x) => x.a)), b = Math.max(...list.map((x) => x.b));
      const oa = M.toOut(a), ob = M.toOut(b);
      if (ob - oa < 0.01) continue;
      rows.push(h('div', { class: 'edit-row' },
        h('span', { class: `tag ${g0.track}` }, g0.track === 'voice' ? 'Voice' : 'Music'),
        h('span', { class: 'grow' }, `${g0.g === 0 ? 'Muted' : `${Math.round(g0.g * 100)}%`} · ${fmt(oa, true)} → ${fmt(ob, true)}`),
        h('button', { class: 'btn small ghost', onclick: () => commit((e) => { e.gains = e.gains.filter((x) => x.gid !== gid); }) }, 'Remove')));
    }
    if (!rows.length) rows.push(h('p', { class: 'muted small' }, 'No edits yet. Select a part on the timeline to cut it or change its volume.'));
    editsCard.append(...rows);

    // export row
    exportRow.innerHTML = '';
    if (rec.exportId) {
      exportRow.append(
        h('span', { class: 'muted small grow' }, 'Last exported video is saved on this phone.'),
        h('button', { class: 'btn small', onclick: () => withExport((b) => downloadBlob(b, fileName(b))) }, icon('download', 16), 'Save'),
        h('button', { class: 'btn small', onclick: () => withExport((b) => shareBlob(b, fileName(b))) }, icon('share', 16), 'Share'));
    }
  }

  async function withExport(fn) {
    const b = await db.getBlob(rec.exportId);
    if (b) fn(b); else toast('Export not found — export again');
  }
  const fileName = (b) => `${(project?.name || 'video')} - ${rec.name}`.replace(/[^\w\- ]+/g, '').trim() + `.${extFor(b.type)}`;

  // ---------- timeline ----------
  const tctx = tl.getContext('2d');
  let tlW = 300, dpr = 1, pps = 0, scrollX = 0;
  const X = (t) => PAD + t * pps - scrollX;
  const Tx = (x) => (x - PAD + scrollX) / pps;
  const fitPps = () => Math.max(1, (tlW - PAD * 2) / Math.max(1, M.outD));
  function clampScroll() {
    const max = Math.max(0, M.outD * pps + PAD * 2 - tlW);
    scrollX = clamp(scrollX, 0, max);
  }
  function zoom(k) {
    const center = Tx(tlW / 2);
    pps = clamp(pps * k, fitPps(), 600);
    scrollX = PAD + center * pps - tlW / 2;
    clampScroll();
    drawTL();
  }
  function sizeTL() {
    dpr = window.devicePixelRatio || 1;
    tlW = tl.clientWidth || 300;
    tl.width = Math.round(tlW * dpr);
    tl.height = Math.round(TL_H * dpr);
    if (!pps) pps = fitPps();
    pps = Math.max(pps, fitPps());
    clampScroll();
    drawTL();
  }

  function srcRangeToOut(a, b) {
    const out = [];
    for (const s of M.segs) {
      const x = Math.max(a, s.a), y = Math.min(b, s.b);
      if (y > x) out.push([s.o + x - s.a, s.o + y - s.a]);
    }
    return out;
  }

  function drawTL() {
    const c = tctx;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.fillStyle = C.bg;
    c.fillRect(0, 0, tlW, TL_H);
    const x0 = Math.max(0, X(0)), x1 = Math.min(tlW, X(M.outD));
    for (const k of ['video', 'voice', 'music']) {
      const [y, hh] = LANES[k];
      c.fillStyle = C.lane;
      c.fillRect(x0, y, Math.max(0, x1 - x0), hh);
    }
    // ruler
    const steps = [0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600];
    const step = steps.find((s) => s * pps >= 64) || 600;
    c.fillStyle = C.ruler;
    c.strokeStyle = C.ruler;
    c.font = '10px "JetBrains Mono", ui-monospace, monospace';
    c.textBaseline = 'top';
    c.lineWidth = 1;
    for (let t = Math.floor(Tx(0) / step) * step; t <= Math.min(M.outD, Tx(tlW)); t += step) {
      if (t < 0) continue;
      const x = Math.round(X(t)) + 0.5;
      c.beginPath(); c.moveTo(x, 12); c.lineTo(x, 18); c.stroke();
      c.fillText(fmt(t), x + 3, 2);
    }
    // video blocks
    {
      const [y, hh] = LANES.video;
      c.font = '600 11px Manrope, system-ui, sans-serif';
      c.textBaseline = 'middle';
      for (const r of ranges) {
        for (const [a, b] of srcRangeToOut(r.a, r.b)) {
          const xa = X(a), xb = X(b);
          if (xb < 0 || xa > tlW) continue;
          c.fillStyle = r.n % 2 ? C.block1 : C.block2;
          c.fillRect(xa + 0.5, y + 1, Math.max(1, xb - xa - 1), hh - 2);
          if (xb - xa > 18) { c.fillStyle = C.blockText; c.fillText(String(r.n), Math.max(xa, 0) + 6, y + hh / 2); }
        }
      }
    }
    // voice waveform
    {
      const [y, hh] = LANES.voice;
      const mid = y + hh / 2;
      for (let px = Math.floor(x0); px < x1; px++) {
        const ta = Tx(px), tb = Tx(px + 1);
        const sa = M.toSrc(ta);
        let sb = M.toSrc(tb);
        if (sb < sa || sb - sa > (tb - ta) * 2) sb = sa + (tb - ta);
        const i0 = Math.floor(sa * va.rate), i1 = Math.max(i0 + 1, Math.ceil(sb * va.rate));
        let pk = 0;
        for (let i = i0; i < i1 && i < va.peaks.length; i++) if (va.peaks[i] > pk) pk = va.peaks[i];
        const gv = env.v[Math.min(env.v.length - 1, Math.floor(ta * env.rate))];
        const amp = Math.min(1, (pk / va.peakMax) * gv);
        c.fillStyle = gv < 0.02 ? C.voiceMute : C.voice;
        const hgt = Math.max(1, amp * (hh / 2 - 2));
        c.fillRect(px, mid - hgt, 1, hgt * 2);
      }
    }
    // music waveform
    {
      const [y, hh] = LANES.music;
      if (musicInfo && edl.music) {
        const m = edl.music;
        for (let px = Math.floor(x0); px < x1; px++) {
          const t = Tx(px);
          if (t < m.start) continue;
          let mt = t - m.start;
          if (m.loop !== false) mt %= musicInfo.duration; else if (mt > musicInfo.duration) continue;
          const pk = musicInfo.peaks[Math.min(musicInfo.peaks.length - 1, Math.floor(mt * musicInfo.rate))];
          const gm = env.m[Math.min(env.m.length - 1, Math.floor(t * env.rate))];
          const hgt = Math.max(1, Math.min(1, pk * gm * 1.6) * (hh - 4));
          c.fillStyle = C.music;
          c.fillRect(px, y + hh - 2 - hgt, 1, hgt);
        }
      } else {
        c.fillStyle = '#5d5447';
        c.font = '11px Manrope, system-ui, sans-serif';
        c.textBaseline = 'middle';
        c.fillText('No music', Math.max(x0, 0) + 52, y + hh / 2);
      }
    }
    // volume-range markers
    for (const g of edl.gains) {
      const [y] = LANES[g.track];
      const hh = LANES[g.track][1];
      for (const [a, b] of srcRangeToOut(g.a, g.b)) {
        c.fillStyle = g.g === 0 ? 'rgba(201,74,50,0.16)' : 'rgba(244,237,226,0.07)';
        c.fillRect(X(a), y, X(b) - X(a), hh);
        c.fillStyle = g.g === 0 ? '#c94a32' : '#efe3cf';
        c.fillRect(X(a), y, X(b) - X(a), 2);
      }
    }
    // cut joins
    c.strokeStyle = C.join;
    c.fillStyle = C.join;
    c.lineWidth = 1.5;
    for (const j of M.joins()) {
      const x = X(j);
      if (x < -5 || x > tlW + 5) continue;
      c.setLineDash([3, 3]);
      c.beginPath(); c.moveTo(x, 20); c.lineTo(x, TL_H - 4); c.stroke();
      c.setLineDash([]);
      c.beginPath(); c.moveTo(x - 5, 18); c.lineTo(x + 5, 18); c.lineTo(x, 25); c.fill();
    }
    // selection
    if (sel) {
      const xa = X(sel.a), xb = X(sel.b);
      c.fillStyle = C.sel;
      c.fillRect(xa, 20, xb - xa, TL_H - 24);
      c.strokeStyle = C.selEdge;
      c.lineWidth = 2;
      for (const x of [xa, xb]) {
        c.beginPath(); c.moveTo(x, 20); c.lineTo(x, TL_H - 4); c.stroke();
        c.fillStyle = C.selEdge;
        c.beginPath(); c.arc(x, TL_H - 10, 7, 0, Math.PI * 2); c.fill();
      }
    }
    // playhead
    const px = X(T);
    c.strokeStyle = C.head;
    c.fillStyle = C.head;
    c.lineWidth = 2;
    c.beginPath(); c.moveTo(px, 4); c.lineTo(px, TL_H); c.stroke();
    c.beginPath(); c.moveTo(px - 6, 0); c.lineTo(px + 6, 0); c.lineTo(px, 8); c.fill();
    // lane labels
    c.font = '700 9px Manrope, system-ui, sans-serif';
    c.textBaseline = 'middle';
    for (const [k, txt] of [['video', 'VIDEO'], ['voice', 'VOICE'], ['music', 'MUSIC']]) {
      const [y] = LANES[k];
      c.fillStyle = C.label;
      c.fillRect(2, y + 2, 42, 14);
      c.fillStyle = '#b9ab95';
      c.fillText(txt, 6, y + 9);
    }
  }

  // timeline touch: tap = seek, drag = scroll, long-press+drag = select, drag handles = adjust
  let tg = null;
  tl.addEventListener('pointerdown', (e) => {
    const r = tl.getBoundingClientRect();
    const x = e.clientX - r.left;
    try { tl.setPointerCapture(e.pointerId); } catch { /* ignore */ }
    if (sel && Math.abs(x - X(sel.a)) < 22) tg = { mode: 'a' };
    else if (sel && Math.abs(x - X(sel.b)) < 22) tg = { mode: 'b' };
    else {
      tg = { mode: 'pan', x0: x, s0: scrollX, moved: false };
      tg.timer = setTimeout(() => {
        if (tg && tg.mode === 'pan' && !tg.moved) {
          const t = clamp(Tx(x), 0, M.outD);
          tg = { mode: 'select', anchor: t };
          sel = { a: t, b: t };
          navigator.vibrate?.(12);
          renderSel(); drawTL();
        }
      }, 420);
    }
  });
  tl.addEventListener('pointermove', (e) => {
    if (!tg) return;
    const r = tl.getBoundingClientRect();
    const x = e.clientX - r.left;
    const t = clamp(Tx(x), 0, M.outD);
    if (tg.mode === 'pan') {
      const dx = x - tg.x0;
      if (Math.abs(dx) > 6) { tg.moved = true; clearTimeout(tg.timer); }
      if (tg.moved) { scrollX = tg.s0 - dx; clampScroll(); drawTL(); }
    } else if (tg.mode === 'select') {
      sel = { a: Math.min(tg.anchor, t), b: Math.max(tg.anchor, t) };
      edgeScroll(x); renderSel(); drawTL();
    } else {
      sel[tg.mode] = t;
      edgeScroll(x); renderSel(); drawTL();
    }
  });
  function edgeScroll(x) {
    if (x > tlW - 24) scrollX += 8; else if (x < 24) scrollX -= 8;
    clampScroll();
  }
  const tlUp = (e) => {
    if (!tg) return;
    clearTimeout(tg.timer);
    if (tg.mode === 'pan' && !tg.moved && e.type === 'pointerup') {
      const r = tl.getBoundingClientRect();
      seek(Tx(e.clientX - r.left));
    } else if (sel) {
      if (sel.a > sel.b) sel = { a: sel.b, b: sel.a };
      if (sel.b - sel.a < 0.05) sel = null;
      renderSel(); drawTL();
    }
    tg = null;
  };
  tl.addEventListener('pointerup', tlUp);
  tl.addEventListener('pointercancel', tlUp);
  tl.addEventListener('wheel', (e) => {
    e.preventDefault();
    if (e.ctrlKey || Math.abs(e.deltaY) > Math.abs(e.deltaX)) zoom(e.deltaY < 0 ? 1.2 : 1 / 1.2);
    else { scrollX += e.deltaX; clampScroll(); drawTL(); }
  }, { passive: false });

  const ro = new ResizeObserver(sizeTL);
  ro.observe(tl);

  function onKey(e) {
    if (e.target.closest('input,textarea')) return;
    if (e.key === ' ') { e.preventDefault(); playing ? pause() : play(); }
    else if ((e.ctrlKey || e.metaKey) && e.key === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); }
    else if (e.key === 'i') markIn();
    else if (e.key === 'o') markOut();
    else if (e.key === 'Delete' || e.key === 'Backspace') cutSel();
  }
  window.addEventListener('keydown', onKey);

  // ---------- main loop ----------
  let raf = 0;
  const loop = () => {
    if (playing && mix) {
      T = mix.time();
      if (T >= M.outD) { T = M.outD; pause(); }
      renderFrame();
      const px = X(T);
      if (px > tlW * 0.85 || px < 0) { scrollX += px - tlW * 0.3; clampScroll(); }
      drawTL();
      updateTime();
    }
    raf = requestAnimationFrame(loop);
  };
  raf = requestAnimationFrame(loop);

  // ---------- export ----------
  async function exportVideo() {
    pause();
    const mime = pickMime(VIDEO_MIMES);
    if (!mime || !HTMLCanvasElement.prototype.captureStream) {
      toast('This browser cannot export video. Please use Chrome or Safari.', 4000);
      return;
    }
    let cancelled = false;
    const ecv = h('canvas', { class: 'export-cv', width: rec.w, height: rec.h });
    const ep = new Painter(ecv, rec.bg, images);
    const pl = new Player(rec.events);
    const fill = h('div', { class: 'bar-fill' });
    const label = h('div', { class: 'muted small' }, 'Starting…');
    const cancelBtn = h('button', { class: 'btn', onclick: () => { cancelled = true; } }, 'Cancel');
    const box = h('div', { class: 'modal-wrap' }, h('div', { class: 'modal export-modal' },
      h('h3', null, 'Exporting video'),
      ecv,
      h('div', { class: 'bar' }, fill),
      label,
      h('p', { class: 'muted small' }, 'Keep this screen open until it finishes. Export runs at normal speed.'),
      h('div', { class: 'modal-actions' }, cancelBtn)));
    document.body.append(box);

    ep.draw(pl.seek(M.toSrc(0)));
    const vstream = ecv.captureStream(30);
    const dest = actx.createMediaStreamDestination();
    const stream = new MediaStream([...vstream.getVideoTracks(), ...dest.stream.getAudioTracks()]);
    let mr;
    try {
      mr = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: rec.h >= 1080 ? 8_000_000 : 5_000_000, audioBitsPerSecond: 160_000 });
    } catch (err) {
      box.remove();
      toast(`Export failed: ${err.message}`, 5000);
      return;
    }
    const chunks = [];
    mr.ondataavailable = (ev) => { if (ev.data && ev.data.size) chunks.push(ev.data); };
    const stopped = new Promise((res) => { mr.onstop = res; });
    await actx.resume();
    const wake = await keepAwake();
    mr.start(500);
    const m = startMix(actx, dest, M, parts, musicBuf, edl, va, 0);
    await new Promise((res) => {
      const step = () => {
        if (cancelled) return res();
        const t = m.time();
        ep.draw(pl.seek(M.toSrc(Math.min(t, M.outD))));
        const k = M.outD ? Math.min(1, t / M.outD) : 1;
        fill.style.width = `${(k * 100).toFixed(1)}%`;
        label.textContent = `${fmt(t)} / ${fmt(M.outD)}`;
        if (t >= M.outD + 0.3) return res();
        requestAnimationFrame(step);
      };
      step();
    });
    m.stop();
    if (mr.state !== 'inactive') mr.stop();
    await stopped;
    vstream.getTracks().forEach((tr) => tr.stop());
    wake?.release?.().catch(() => {});
    if (cancelled) { box.remove(); toast('Export cancelled'); return; }

    const blob = new Blob(chunks, { type: mime.split(';')[0] });
    if (rec.exportId) await db.delBlob(rec.exportId);
    rec.exportId = await db.putBlob(blob);
    saveRec.flush();
    renderPanels();
    const name = fileName(blob);
    box.querySelector('h3').textContent = 'Video ready';
    label.textContent = `${name} · ${(blob.size / 1e6).toFixed(1)} MB`;
    const actionsEl = box.querySelector('.modal-actions');
    actionsEl.innerHTML = '';
    actionsEl.append(
      h('button', { class: 'btn', onclick: () => box.remove() }, 'Close'),
      h('button', { class: 'btn', onclick: () => downloadBlob(blob, name) }, icon('download', 18), 'Save'),
      h('button', { class: 'btn primary', onclick: () => shareBlob(blob, name) }, icon('share', 18), 'Share'));
  }

  syncTransport();
  renderPanels();
  renderSel();
  renderFrame();
  sizeTL();
  updateTime();

  return async () => {
    pause();
    cancelAnimationFrame(raf);
    ro.disconnect();
    window.removeEventListener('keydown', onKey);
    rec.edl = edl;
    await saveRec.flush();
    actx.close().catch(() => {});
  };
}

