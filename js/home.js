import { h, uid, promptBox, confirmBox, modal, toast } from './util.js';
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
          h('div', { class: 'muted small' }, `${p.slides.length} images · ${recs.length} takes · ${new Date(p.updated || p.created).toLocaleDateString()}`)),
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
        h('label', null, 'Video quality'),
        seg('res', [['1080', '1080p (Full HD)'], ['720', '720p (smaller file)']]),
        h('label', null, 'Background behind images'),
        seg('bg', [['#0e0c0a', 'Charcoal'], ['#ffffff', 'White'], ['#f3ede2', 'Paper'], ['#1f2a24', 'Forest']]),
        h('label', null, '3-2-1 countdown before recording'),
        seg('countdown', [[true, 'On'], [false, 'Off']]),
        h('p', { class: 'muted small' }, 'Tip: open this page in Chrome (Android) or Safari (iPhone) and use “Add to Home screen” to install it like an app.')),
      buttons: [{ label: 'Done', kind: 'primary', value: true }],
    });
  }

  root.append(h('div', { class: 'page' },
    h('header', { class: 'topbar' },
      h('div', { class: 'brand' }, h('span', { class: 'brand-mark' }, icon('film', 20)), h('span', null, 'SlideVox Studio')),
      h('button', { class: 'icon-btn ghost', title: 'Settings', onclick: openSettings }, icon('gear'))),
    h('main', { class: 'content' },
      h('section', { class: 'hero' },
        h('h1', null, 'Make explainer videos from your phone'),
        h('p', { class: 'muted' }, 'Add images → paste your script → record voice while you zoom, tick and circle → cut, add music, export.')),
      h('button', { class: 'btn primary big', onclick: newProject }, icon('plus'), 'New project'),
      h('h2', { class: 'section-title' }, 'Your projects'),
      list)));

  await render();
  if (new URLSearchParams(location.search).has('welcome')) toast('Welcome!');
  return () => urls.forEach((u) => URL.revokeObjectURL(u));
}
