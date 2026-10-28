import { h, uid, debounce, promptBox, confirmBox, pickFiles, prepareImage, toast, fmt, enterImmersive, modal } from './util.js';
import { icon } from './icons.js';
import { db, blobsUsedBy, deleteRecording } from './db.js';
import { importClip, makeBlankPage, askBlankColor } from './media.js';
import { openPeek } from './peek.js';

// Drag rows by their handle to reorder. Works with touch and mouse.
export function makeSortable(list, onMove) {
  list.addEventListener('pointerdown', (e) => {
    const handle = e.target.closest('.drag-handle');
    if (!handle) return;
    e.preventDefault();
    const row = handle.closest('.item-row');
    const rows = [...list.querySelectorAll('.item-row')];
    const from = rows.indexOf(row);
    const rects = rows.map((r) => r.getBoundingClientRect());
    const startY = e.clientY;
    const startScroll = window.scrollY;
    let to = from;
    row.classList.add('dragging');
    navigator.vibrate?.(10);
    try { handle.setPointerCapture(e.pointerId); } catch { /* ignore */ }
    let scrollTimer = null;

    const update = (clientY) => {
      const dy = clientY - startY + (window.scrollY - startScroll);
      row.style.transform = `translateY(${dy}px)`;
      const y = rects[from].top + rects[from].height / 2 + dy;
      to = from;
      for (let i = 0; i < rects.length; i++) {
        const mid = rects[i].top + rects[i].height / 2;
        if (i < from && y < mid) { to = i; break; }
        if (i > from && y > mid) to = i;
      }
      const hgt = rects[from].height + 8;
      rows.forEach((r, i) => {
        if (r === row) return;
        let shift = 0;
        if (from < to && i > from && i <= to) shift = -hgt;
        if (from > to && i >= to && i < from) shift = hgt;
        r.style.transform = shift ? `translateY(${shift}px)` : '';
      });
    };
    let lastY = e.clientY;
    const move = (ev) => {
      lastY = ev.clientY;
      update(lastY);
      clearInterval(scrollTimer);
      const edge = 70;
      if (ev.clientY < edge || ev.clientY > window.innerHeight - edge) {
        const dir = ev.clientY < edge ? -1 : 1;
        scrollTimer = setInterval(() => { window.scrollBy(0, dir * 10); update(lastY); }, 16);
      }
    };
    const up = () => {
      clearInterval(scrollTimer);
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
      handle.removeEventListener('pointercancel', up);
      rows.forEach((r) => { r.style.transform = ''; });
      row.classList.remove('dragging');
      if (to !== from) onMove(from, to);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
    handle.addEventListener('pointercancel', up);
  });
}

const KIND_LABEL = { image: 'Image', video: 'Video clip', audio: 'Audio clip' };

export async function projectScreen(root, { projectId }) {
  const p = await db.get('projects', projectId);
  if (!p) { location.hash = '#/'; return null; }
  p.aspect ||= '16:9';
  const urls = new Map();
  const saveNow = () => { p.updated = Date.now(); return db.put('projects', p); };
  const save = debounce(saveNow, 400);

  const title = h('button', { class: 'title-btn', onclick: rename }, p.name, icon('edit', 16));
  const list = h('div', { class: 'item-list' });
  const recList = h('div', { class: 'rec-list' });
  const countEl = h('span', { class: 'muted small' });
  // Multi-select (for deleting many items at once)
  let selecting = false;
  const picked = new Set();
  const selectBtn = h('button', { class: 'btn small ghost', onclick: () => setSelecting(!selecting) }, icon('check', 16), 'Select');
  const selBar = h('div', { class: 'multi-bar hidden' });
  function setSelecting(on) {
    selecting = on;
    picked.clear();
    selectBtn.classList.toggle('hidden', on);
    renderItems();
  }
  function renderSelBar() {
    selBar.classList.toggle('hidden', !selecting);
    if (!selecting) return;
    selBar.innerHTML = '';
    const all = picked.size === p.slides.length && p.slides.length > 0;
    selBar.append(
      h('span', { class: 'grow' }, h('b', null, `${picked.size} selected`)),
      h('button', { class: 'btn small', onclick: () => { if (all) picked.clear(); else p.slides.forEach((x) => picked.add(x.id)); renderItems(); } }, all ? 'Select none' : 'Select all'),
      h('button', { class: 'btn small danger', disabled: !picked.size, onclick: deletePicked }, icon('trash', 16), 'Delete'),
      h('button', { class: 'btn small ghost', onclick: () => setSelecting(false) }, 'Done'));
  }
  const cta = h('button', { class: 'btn record big', onclick: startRecording }, icon('mic'), 'Create video & record voice');
  makeSortable(list, (from, to) => {
    const [s] = p.slides.splice(from, 1);
    p.slides.splice(to, 0, s);
    save();
    renderItems();
  });

  async function rename() {
    const n = await promptBox('Rename project', p.name);
    if (n) { p.name = n; title.firstChild.textContent = n; saveNow(); }
  }

  async function urlFor(imageId) {
    if (urls.has(imageId)) return urls.get(imageId);
    const blob = await db.getBlob(imageId);
    const u = blob ? URL.createObjectURL(blob) : '';
    urls.set(imageId, u);
    return u;
  }

  async function renderItems() {
    list.innerHTML = '';
    list.classList.toggle('selecting', selecting);
    renderSelBar();
    const imgs = p.slides.filter((s) => (s.kind || 'image') === 'image').length;
    const clips = p.slides.length - imgs;
    countEl.textContent = p.slides.length ? `${imgs} images${clips ? ` · ${clips} clips` : ''}` : '';
    cta.disabled = !p.slides.length;
    if (!p.slides.length) {
      list.append(h('div', { class: 'empty small-empty' }, icon('image', 30), h('p', { class: 'muted' }, 'Add images, then drag ☰ to arrange the order.')));
      return;
    }
    for (let i = 0; i < p.slides.length; i++) {
      const s = p.slides[i];
      const kind = s.kind || 'image';
      const img = h('img', { src: await urlFor(s.imageId), alt: '', style: { transform: `rotate(${(s.rot || 0) * 90}deg)` } });
      if (selecting) {
        const on = picked.has(s.id);
        list.append(h('div', {
          class: `item-row kind-${kind} pickable ${on ? 'picked' : ''}`,
          onclick: () => { if (picked.has(s.id)) picked.delete(s.id); else picked.add(s.id); renderItems(); },
        },
        h('span', { class: `pick-box ${on ? 'on' : ''}` }, on ? icon('check', 18) : null),
        h('span', { class: 'item-no' }, String(i + 1)),
        h('div', { class: 'item-thumb' }, img),
        h('div', { class: 'item-meta' }, h('div', { class: 'item-name' }, s.name || `Image ${i + 1}`))));
        continue;
      }
      list.append(h('div', { class: `item-row kind-${kind}` },
        h('button', { class: 'drag-handle', title: 'Drag to reorder', 'aria-label': 'Drag to reorder' }, icon('grip', 22)),
        h('span', { class: 'item-no' }, String(i + 1)),
        h('div', { class: 'item-thumb', title: 'Tap to see it big', onclick: () => openPeek({ imageId: s.imageId, w: s.w, h: s.h, rot: s.rot || 0, label: `${i + 1}. ${s.name || ''}` }) },
          img, kind !== 'image' && h('span', { class: 'item-badge' }, icon(kind === 'video' ? 'film' : 'music', 14))),
        h('div', { class: 'item-meta' },
          h('div', { class: 'item-name' }, s.name || `Image ${i + 1}`),
          h('div', { class: 'muted small' }, s.blank ? 'Blank page' : kind === 'image' ? `${s.w}×${s.h}` : `${KIND_LABEL[kind]} · ${fmt(s.dur)}`)),
        h('button', { class: 'icon-btn ghost', title: 'More', onclick: () => itemMenu(i) }, icon('more', 20)),
        h('button', { class: 'icon-btn ghost', title: 'Delete', onclick: () => removeItem(i) }, icon('trash', 18))));
    }
  }

  // Frees the stored files of removed items unless a take still needs them.
  async function cleanup(items) {
    const recs = await db.byIndex('recordings', 'projectId', p.id);
    const used = new Set();
    recs.forEach((r) => blobsUsedBy(r).forEach((id) => used.add(id)));
    for (const s of items) {
      if (!used.has(s.imageId)) await db.delBlob(s.imageId);
      if (s.blobId && !used.has(s.blobId)) await db.delBlob(s.blobId);
    }
  }

  async function removeItem(i) {
    if (!(await confirmBox(`Remove item ${i + 1}?`, 'Remove'))) return;
    const [s] = p.slides.splice(i, 1);
    await saveNow();
    await cleanup([s]);
    renderItems();
  }

  async function deletePicked() {
    const n = picked.size;
    if (!n || !(await confirmBox(`Delete ${n} item${n > 1 ? 's' : ''}?`))) return;
    const gone = p.slides.filter((x) => picked.has(x.id));
    p.slides = p.slides.filter((x) => !picked.has(x.id));
    await saveNow();
    await cleanup(gone);
    toast(`${n} item${n > 1 ? 's' : ''} deleted`);
    setSelecting(false);
  }

  async function addBlank(at = p.slides.length) {
    const color = await askBlankColor(modal, h);
    if (!color) return;
    const item = await makeBlankPage(color, p.aspect);
    p.slides.splice(at, 0, item);
    await saveNow();
    toast(`${item.name} added at position ${at + 1}`);
    renderItems();
  }

  function itemMenu(i) {
    const s = p.slides[i];
    // Each menu button closes the sheet, then runs its action.
    const act = (fn) => (e) => { e.currentTarget.closest('.modal-wrap').querySelector('.modal-actions .btn').click(); fn(); };
    modal({
      title: `${i + 1}. ${s.name || 'Item'}`,
      body: h('div', { class: 'menu-list' },
        (s.kind || 'image') === 'image' && !s.blank && h('button', { class: 'btn', onclick: act(() => { s.rot = ((s.rot || 0) + 1) % 4; save(); renderItems(); }) }, icon('rotate', 18), 'Rotate'),
        h('button', { class: 'btn', onclick: act(() => addBlank(i)) }, icon('page', 18), 'Add blank page before'),
        h('button', { class: 'btn', onclick: act(() => addBlank(i + 1)) }, icon('page', 18), 'Add blank page after'),
        h('button', { class: 'btn danger-soft', onclick: act(() => removeItem(i)) }, icon('trash', 18), 'Delete')),
      buttons: [{ label: 'Close', value: null }],
    });
  }

  async function addImages() {
    const files = await pickFiles('image/*', true);
    if (!files.length) return;
    toast(`Adding ${files.length} image${files.length > 1 ? 's' : ''}…`, 8000);
    let added = 0;
    for (const f of files) {
      try {
        const img = await prepareImage(f);
        const imageId = await db.putBlob(img.blob);
        p.slides.push({ id: uid(), kind: 'image', imageId, w: img.w, h: img.h, rot: 0, name: f.name });
        added++;
      } catch (e) {
        console.error(e);
        toast(`Could not read ${f.name}`);
      }
    }
    await saveNow();
    toast(`${added} image${added === 1 ? '' : 's'} added`);
    renderItems();
  }

  async function addClips() {
    const files = await pickFiles('video/*,audio/*', true);
    if (!files.length) return;
    toast('Adding clip…', 8000);
    let added = 0;
    for (const f of files) {
      try {
        p.slides.push(await importClip(f));
        added++;
      } catch (e) {
        console.error(e);
        toast(`Could not open ${f.name}`, 3500);
      }
    }
    await saveNow();
    if (added) toast(`${added} clip${added === 1 ? '' : 's'} added — drag ☰ to place it`, 3500);
    renderItems();
  }

  function sortByName() {
    p.slides.sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { numeric: true }));
    save();
    renderItems();
    toast('Sorted by file name');
  }

  // Script / teleprompter text
  const scriptArea = h('textarea', {
    class: 'script-area', placeholder: 'Paste your script here. It shows only to you while recording — it never appears in the video.',
    oninput: () => { p.script = scriptArea.value; wordCount(); save(); },
  });
  scriptArea.value = p.script || '';
  const words = h('span', { class: 'muted small' });
  function wordCount() {
    const n = (p.script || '').trim().split(/\s+/).filter(Boolean).length;
    words.textContent = n ? `${n} words · about ${fmt(n / 2.3)} to read` : '';
  }
  wordCount();
  const scriptCard = h('section', { class: 'card', id: 'script-card' },
    h('div', { class: 'card-head' },
      h('h2', null, icon('text', 18), 'Script'),
      words,
      h('button', {
        class: 'btn small', onclick: async () => {
          try {
            const t = await navigator.clipboard.readText();
            if (!t) return toast('Clipboard is empty');
            scriptArea.value = scriptArea.value ? `${scriptArea.value}\n\n${t}` : t;
            p.script = scriptArea.value; wordCount(); save();
            toast('Script pasted');
          } catch { scriptArea.focus(); toast('Long-press in the box and choose Paste'); }
        },
      }, icon('paste', 16), 'Paste')),
    scriptArea);

  const aspectSeg = h('div', { class: 'seg' }, [['16:9', 'Landscape 16:9 (YouTube)'], ['9:16', 'Portrait 9:16 (Shorts / Reels)']].map(([v, label]) =>
    h('button', {
      class: p.aspect === v ? 'on' : '',
      onclick: (e) => {
        p.aspect = v; save();
        [...aspectSeg.children].forEach((b) => b.classList.toggle('on', b === e.currentTarget));
      },
    }, label)));

  async function renderRecs() {
    const recs = (await db.byIndex('recordings', 'projectId', p.id)).sort((a, b) => b.created - a.created);
    recList.innerHTML = '';
    if (!recs.length) {
      recList.append(h('p', { class: 'muted small' }, 'No takes yet. Your recordings will appear here for editing.'));
      return;
    }
    for (const r of recs) {
      recList.append(h('div', { class: 'rec-row', onclick: () => { location.hash = `#/edit/${r.id}`; } },
        h('span', { class: 'rec-ic' }, icon('film', 20)),
        h('div', { class: 'grow' },
          h('div', { class: 'rec-name' }, r.name),
          h('div', { class: 'muted small' }, `${fmt(r.duration)} · ${r.h > r.w ? '9:16' : '16:9'} · ${new Date(r.created).toLocaleString()}${r.exportId ? ' · exported' : ''}`)),
        h('button', { class: 'btn small' }, icon('edit', 16), 'Edit'),
        h('button', {
          class: 'icon-btn ghost', title: 'Delete take',
          onclick: async (e) => {
            e.stopPropagation();
            if (await confirmBox(`Delete ${r.name}?`)) { await deleteRecording(r); renderRecs(); }
          },
        }, icon('trash', 18))));
    }
  }

  async function startRecording() {
    if (!p.slides.length) return toast('Add images first');
    await saveNow();
    enterImmersive(p.aspect === '9:16' ? 'portrait' : 'landscape'); // must run inside the tap
    location.hash = `#/rec/${p.id}`;
  }

  root.append(h('div', { class: 'page' },
    h('header', { class: 'topbar' },
      h('a', { class: 'icon-btn ghost', href: '#/', title: 'Back' }, icon('back')),
      title,
      h('span', { class: 'grow' })),
    h('main', { class: 'content' },
      h('div', { class: 'add-row four' },
        h('button', { class: 'btn', onclick: addImages }, icon('image', 18), 'Images'),
        h('button', { class: 'btn', onclick: addClips }, icon('film', 18), 'Video / audio'),
        h('button', { class: 'btn', onclick: () => addBlank() }, icon('page', 18), 'Blank page'),
        h('button', { class: 'btn', onclick: () => { scriptCard.scrollIntoView({ behavior: 'smooth' }); setTimeout(() => scriptArea.focus(), 300); } }, icon('text', 18), 'Script')),
      h('section', { class: 'card' },
        h('div', { class: 'card-head' },
          h('h2', null, icon('image', 18), 'Sequence'),
          countEl,
          selectBtn,
          h('button', { class: 'btn small ghost', onclick: sortByName, title: 'Sort by file name' }, icon('sort', 16), 'Sort')),
        selBar,
        list,
        h('div', { class: 'btn-row' },
          h('button', { class: 'btn small', onclick: addImages }, icon('plus', 16), 'Add images'),
          h('button', { class: 'btn small', onclick: addClips }, icon('plus', 16), 'Add video / audio'),
          h('button', { class: 'btn small', onclick: () => addBlank() }, icon('plus', 16), 'Blank page')),
        h('p', { class: 'muted small hint-line' }, 'Hold ☰ and drag to change the order. Video/audio clips play in the final video at the point you reach them (Next ▶) while recording. Tap a picture to see it big.')),
      scriptCard,
      h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('h2', null, icon('fit', 18), 'Video shape')),
        aspectSeg),
      cta,
      h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('h2', null, icon('film', 18), 'Takes')),
        recList))));

  await renderItems();
  await renderRecs();
  return () => { save.flush(); urls.forEach((u) => u && URL.revokeObjectURL(u)); };
}
