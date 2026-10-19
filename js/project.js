import { h, uid, debounce, promptBox, confirmBox, pickFiles, prepareImage, toast, fmt, enterImmersive } from './util.js';
import { icon } from './icons.js';
import { db, imagesUsedBy, deleteRecording } from './db.js';

export async function projectScreen(root, { projectId }) {
  const p = await db.get('projects', projectId);
  if (!p) { location.hash = '#/'; return null; }
  const urls = new Map();
  const saveNow = () => { p.updated = Date.now(); return db.put('projects', p); };
  const save = debounce(saveNow, 400);

  const title = h('button', { class: 'title-btn', onclick: rename }, p.name, icon('edit', 16));
  const grid = h('div', { class: 'slide-grid' });
  const recList = h('div', { class: 'rec-list' });
  const countEl = h('span', { class: 'muted small' });
  const cta = h('button', { class: 'btn record big', onclick: startRecording }, icon('mic'), 'Create video & record voice');

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

  async function renderSlides() {
    grid.innerHTML = '';
    countEl.textContent = p.slides.length ? `${p.slides.length} images` : '';
    cta.disabled = !p.slides.length;
    if (!p.slides.length) {
      grid.append(h('button', { class: 'add-tile', onclick: addImages }, icon('plus', 28), h('span', null, 'Add images')));
      return;
    }
    for (let i = 0; i < p.slides.length; i++) {
      const s = p.slides[i];
      const img = h('img', { src: await urlFor(s.imageId), alt: `Image ${i + 1}`, style: { transform: `rotate(${(s.rot || 0) * 90}deg)` } });
      grid.append(h('div', { class: 'slide-tile' },
        h('div', { class: 'slide-img' }, img),
        h('span', { class: 'slide-no' }, String(i + 1)),
        h('div', { class: 'slide-tools' },
          h('button', { title: 'Move left', disabled: i === 0, onclick: () => move(i, -1) }, icon('left', 18)),
          h('button', { title: 'Rotate', onclick: () => { s.rot = ((s.rot || 0) + 1) % 4; save(); renderSlides(); } }, icon('rotate', 18)),
          h('button', { title: 'Delete', onclick: () => removeSlide(i) }, icon('trash', 18)),
          h('button', { title: 'Move right', disabled: i === p.slides.length - 1, onclick: () => move(i, 1) }, icon('right', 18)))));
    }
    grid.append(h('button', { class: 'add-tile', onclick: addImages }, icon('plus', 28), h('span', null, 'Add more')));
  }

  function move(i, d) {
    const [s] = p.slides.splice(i, 1);
    p.slides.splice(i + d, 0, s);
    save();
    renderSlides();
  }

  async function removeSlide(i) {
    if (!(await confirmBox(`Remove image ${i + 1}?`, 'Remove'))) return;
    const [s] = p.slides.splice(i, 1);
    await saveNow();
    const recs = await db.byIndex('recordings', 'projectId', p.id);
    if (!recs.some((r) => imagesUsedBy(r).has(s.imageId))) await db.delBlob(s.imageId);
    renderSlides();
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
        p.slides.push({ id: uid(), imageId, w: img.w, h: img.h, rot: 0, name: f.name });
        added++;
      } catch (e) {
        console.error(e);
        toast(`Could not read ${f.name}`);
      }
    }
    await saveNow();
    toast(`${added} image${added === 1 ? '' : 's'} added`);
    renderSlides();
  }

  function sortByName() {
    p.slides.sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { numeric: true }));
    save();
    renderSlides();
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
          h('div', { class: 'muted small' }, `${fmt(r.duration)} · ${new Date(r.created).toLocaleString()}${r.exportId ? ' · exported' : ''}`)),
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
    enterImmersive(); // must run inside the tap so the browser allows fullscreen
    location.hash = `#/rec/${p.id}`;
  }

  root.append(h('div', { class: 'page' },
    h('header', { class: 'topbar' },
      h('a', { class: 'icon-btn ghost', href: '#/', title: 'Back' }, icon('back')),
      title,
      h('span', { class: 'grow' })),
    h('main', { class: 'content' },
      h('div', { class: 'add-row' },
        h('button', { class: 'btn', onclick: addImages }, icon('plus', 18), icon('image', 18), 'Add images'),
        h('button', { class: 'btn', onclick: () => { scriptCard.scrollIntoView({ behavior: 'smooth' }); setTimeout(() => scriptArea.focus(), 300); } }, icon('plus', 18), icon('text', 18), 'Add script')),
      h('section', { class: 'card' },
        h('div', { class: 'card-head' },
          h('h2', null, icon('image', 18), 'Images (in order)'),
          countEl,
          h('button', { class: 'btn small ghost', onclick: sortByName, title: 'Sort by file name' }, icon('sort', 16), 'Sort')),
        grid),
      scriptCard,
      cta,
      h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('h2', null, icon('film', 18), 'Takes')),
        recList))));

  await renderSlides();
  await renderRecs();
  return () => { save.flush(); urls.forEach((u) => u && URL.revokeObjectURL(u)); };
}
