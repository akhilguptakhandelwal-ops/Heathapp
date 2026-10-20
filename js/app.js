import { $ } from './util.js';
import { askPersistentStorage } from './db.js';
import { homeScreen } from './home.js';
import { projectScreen } from './project.js';
import { recorderScreen } from './recorder.js';
import { editorScreen } from './editor.js';

const root = $('#app');
let cleanup = null;
let routing = Promise.resolve();

async function route() {
  if (cleanup) {
    const c = cleanup;
    cleanup = null;
    try { await c(); } catch (e) { console.error(e); }
  }
  root.innerHTML = '';
  root.className = '';
  const [, name, a, b] = (location.hash || '#/').slice(1).split('/');
  try {
    if (name === 'p' && a) cleanup = await projectScreen(root, { projectId: a });
    else if (name === 'rec' && a) cleanup = await recorderScreen(root, { projectId: a, appendId: b || null });
    else if (name === 'edit' && a) cleanup = await editorScreen(root, { recId: a });
    else cleanup = await homeScreen(root);
  } catch (e) {
    console.error(e);
    root.innerHTML = `<div class="crash"><h2>Something went wrong</h2><pre>${String(e?.message || e)}</pre><a class="btn" href="#/">Go home</a></div>`;
  }
}

window.addEventListener('hashchange', () => { routing = routing.then(route); });
routing = routing.then(route);
askPersistentStorage();

// Keep the browser's install prompt so the Home screen can offer a button.
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  window.__installPrompt = e;
  window.dispatchEvent(new Event('install-ready'));
});
window.addEventListener('appinstalled', () => { window.__installPrompt = null; window.dispatchEvent(new Event('install-ready')); });

const isNative = !!window.Capacitor?.isNativePlatform?.();
if (isNative) document.documentElement.classList.add('native');
if (!isNative && 'serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
