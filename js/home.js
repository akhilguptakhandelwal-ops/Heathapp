import { h, uid, promptBox, confirmBox, modal, toast, pickFiles, fmt } from './util.js';
import { importClip } from './media.js';
import { icon } from './icons.js';
import { db, deleteProject } from './db.js';
import { settings } from './settings.js';

export async function homeScreen(root) {
  const urls = [];
  const list = h('div', { class: 'proj-list' });

  async function render() {
    const projects = (await db.all('projects')).sort((a, b) => (b.updated || 0) - (a.updated || 0));
    list.innerHTML = '';
    if (!projects.length) {
      list.append(h('div', { class: 'empty' },
        icon('folder', 40),
        h('p', null, 'No projects yet'),
        h('p', { class: 'muted' }, 'Create a project, add your images and script, then record.')));
      return;
    }
    for (const p of projects) {
      const thumb = h('div', { class: 'proj-thumb' });
      if (p.slides[0]) {
        const blob = await db.getBlob(p.slides[0].imageId);
        if (blob) {
          const u = URL.createObjectURL(blob);
          urls.push(u);
          thumb.append(h('img', { src: u, alt: '' }));
        }
      } else thumb.append(icon('image', 28));
      const recs = await db.byIndex('recordings', 'projectId', p.id);
      list.append(h('div', { class: 'proj-card', onclick: () => { location.hash = `#/p/${p.id}`; } },
        thumb,
        h('div', { class: 'proj-meta' },
          h('div', { class: 'proj-name' }, p.name),
          h('div', { class: 'muted small' }, `${p.slides.length} items · ${recs.length} takes · ${new Date(p.updated || p.created).toLocaleDateString()}`)),
        h('button', {
          class: 'icon-btn ghost', title: 'Delete project',
          onclick: async (e) => {
            e.stopPropagation();
            if (await confirmBox(`Delete “${p.name}” and all its takes?`)) { await deleteProject(p); render(); }
          },
        }, icon('trash', 20))));
    }
  }

  async function newProject() {
    const n = (await db.all('projects')).length + 1;
    const name = await promptBox('Project name', `Project ${n}`);
    if (name == null) return;
    const p = { id: uid(), name: name || `Project ${n}`, created: Date.now(), updated: Date.now(), slides: [], script: '' };
    await db.put('projects', p);
    location.hash = `#/p/${p.id}`;
  }

  function openSettings() {
    const seg = (key, opts) => h('div', { class: 'seg' }, opts.map(([v, label]) =>
      h('button', {
        class: settings[key] === v ? 'on' : '',
        onclick: (e) => { settings[key] = v; [...e.target.parentNode.children].forEach((b) => b.classList.toggle('on', b === e.target)); },
      }, label)));
    modal({
      title: 'Settings',
      body: h('div', { class: 'form' },
        h('label', null, 'Swipe on the image to go next / previous'),
        seg('swipe', [[true, 'On'], [false, 'Off']]),
        h('label', null, 'Background behind images'),
        seg('bg', [['#0e0c0a', 'Charcoal'], ['#ffffff', 'White'], ['#f3ede2', 'Paper'], ['#1f2a24', 'Forest']]),
        h('label', null, '3-2-1 countdown before recording'),
        seg('countdown', [[true, 'On'], [false, 'Off']]),
        h('p', { class: 'muted small' }, 'Tip: open this page in Chrome (Android) or Safari (iPhone) and use “Add to Home screen” to install it like an app.')),
      buttons: [{ label: 'Done', kind: 'primary', value: true }],
    });
  }

  // Intro / outro clips that are added to every export.
  const libList = h('div', { class: 'item-list' });
  async function renderLibrary() {
    const items = (await db.all('library')).sort((a, b) => a.created - b.created);
    libList.innerHTML = '';
    if (!items.length) {
      libList.append(h('p', { class: 'muted small' }, 'Save a short clip once (for example your channel intro or a “subscribe” ending). Mark it Start or End and it is added to every video you export.'));
      return;
    }
    for (const it of items) {
      const blob = await db.getBlob(it.imageId);
      const u = blob ? URL.createObjectURL(blob) : '';
      if (u) urls.push(u);
      const seg = h('div', { class: 'seg mini' }, [['start', 'Start'], ['end', 'End'], ['off', 'Off']].map(([v, label]) =>
        h('button', {
          class: it.pos === v ? 'on' : '',
          onclick: async () => { it.pos = v; await db.put('library', it); renderLibrary(); },
        }, label)));
      libList.append(h('div', { class: 'item-row lib-row' },
        h('div', { class: 'item-thumb' }, u && h('img', { src: u, alt: '' }), h('span', { class: 'item-badge' }, icon(it.kind === 'video' ? 'film' : 'music', 14))),
        h('div', { class: 'item-meta' },
          h('div', { class: 'item-name' }, it.name),
          h('div', { class: 'muted small' }, `${fmt(it.dur)} · ${it.pos === 'start' ? 'always first' : it.pos === 'end' ? 'always last' : 'not used'}`),
          seg),
        h('button', {
          class: 'icon-btn ghost', title: 'Delete',
          onclick: async () => {
            if (!(await confirmBox(`Delete “${it.name}”?`))) return;
            await db.delBlob(it.blobId); await db.delBlob(it.imageId); await db.del('library', it.id);
            renderLibrary();
          },
        }, icon('trash', 18))));
    }
  }
  async function addLibraryClip() {
    const [f] = await pickFiles('video/*,audio/*');
    if (!f) return;
    toast('Saving clip…', 6000);
    try {
      const item = await importClip(f);
      item.pos = (await db.all('library')).some((l) => l.pos === 'end') ? 'start' : 'end';
      item.created = Date.now();
      await db.put('library', item);
      toast(`Saved — it will play at the ${item.pos === 'end' ? 'end' : 'start'} of every video`, 3500);
    } catch (e) {
      console.error(e);
      toast(`Could not open ${f.name}`, 3500);
    }
    renderLibrary();
  }

  // "Install app" card: hidden once the app runs from the home screen.
  const installed = matchMedia('(display-mode: standalone)').matches || navigator.standalone;
  const isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent);
  const installCard = h('section', { class: 'card install-card' });
  function renderInstall() {
    installCard.innerHTML = '';
    if (installed) { installCard.classList.add('hidden'); return; }
    const prompt = window.__installPrompt;
    installCard.append(
      h('div', { class: 'install-row' },
        h('span', { class: 'brand-mark' }, icon('download', 20)),
        h('div', { class: 'grow' },
          h('b', null, 'Install on your phone'),
          h('div', { class: 'muted small' }, prompt
            ? 'Adds SlideVox to your home screen. Opens full screen, works offline.'
            : isIOS
              ? 'In Safari: tap Share (square with arrow) → “Add to Home Screen”.'
              : 'In Chrome: tap ⋮ menu → “Install app” or “Add to Home screen”.')),
        prompt && h('button', {
          class: 'btn primary small', onclick: async () => {
            prompt.prompt();
            const r = await prompt.userChoice.catch(() => null);
            if (r?.outcome === 'accepted') toast('Installing… find SlideVox on your home screen');
            window.__installPrompt = null;
            renderInstall();
          },
        }, 'Install')));
  }
  renderInstall();
  window.addEventListener('install-ready', renderInstall);

  root.append(h('div', { class: 'page' },
    h('header', { class: 'topbar' },
      h('div', { class: 'brand' }, h('span', { class: 'brand-mark' }, icon('film', 20)), h('span', null, 'SlideVox Studio')),
      h('button', { class: 'icon-btn ghost', title: 'Settings', onclick: openSettings }, icon('gear'))),
    h('main', { class: 'content' },
      h('section', { class: 'hero' },
        h('h1', null, 'Make explainer videos from your phone'),
        h('p', { class: 'muted' }, 'Add images → paste your script → record voice while you zoom, tick and circle → cut, add music, export.')),
      installCard,
      h('button', { class: 'btn primary big', onclick: newProject }, icon('plus'), 'New project'),
      h('h2', { class: 'section-title' }, 'Your projects'),
      list,
      h('section', { class: 'card' },
        h('div', { class: 'card-head' },
          h('h2', null, icon('film', 18), 'Intro & outro'),
          h('span', { class: 'muted small' }, 'added to every video'),
          h('button', { class: 'btn small', onclick: addLibraryClip }, icon('plus', 16), 'Add clip')),
        libList))));

  await render();
  await renderLibrary();
  return () => { window.removeEventListener('install-ready', renderInstall); urls.forEach((u) => URL.revokeObjectURL(u)); };
}
