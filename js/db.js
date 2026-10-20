// Tiny IndexedDB wrapper. Everything stays on the phone.
import { uid } from './util.js';

const DB_NAME = 'slidevox';
const VERSION = 2;
let dbp;

function open() {
  if (dbp) return dbp;
  dbp = new Promise((res, rej) => {
    const r = indexedDB.open(DB_NAME, VERSION);
    r.onupgradeneeded = (e) => {
      const d = r.result;
      if (e.oldVersion < 1) {
        d.createObjectStore('projects', { keyPath: 'id' });
        const rs = d.createObjectStore('recordings', { keyPath: 'id' });
        rs.createIndex('projectId', 'projectId');
        d.createObjectStore('blobs', { keyPath: 'id' });
      }
      // Intro / outro clips that are added to every video.
      if (e.oldVersion < 2) d.createObjectStore('library', { keyPath: 'id' });
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  return dbp;
}

function run(store, mode, fn) {
  return open().then((d) => new Promise((res, rej) => {
    const t = d.transaction(store, mode);
    const r = fn(t.objectStore(store));
    let val;
    if (r) r.onsuccess = () => { val = r.result; };
    t.oncomplete = () => res(val);
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error);
  }));
}

export const db = {
  get: (s, id) => run(s, 'readonly', (st) => st.get(id)),
  all: (s) => run(s, 'readonly', (st) => st.getAll()),
  put: (s, o) => run(s, 'readwrite', (st) => st.put(o)),
  del: (s, id) => run(s, 'readwrite', (st) => st.delete(id)),
  byIndex: (s, idx, v) => run(s, 'readonly', (st) => st.index(idx).getAll(v)),
  async putBlob(blob, id = uid()) {
    await run('blobs', 'readwrite', (st) => st.put({ id, blob }));
    return id;
  },
  async getBlob(id) {
    if (!id) return null;
    const r = await run('blobs', 'readonly', (st) => st.get(id));
    return r?.blob || null;
  },
  delBlob: (id) => (id ? run('blobs', 'readwrite', (st) => st.delete(id)) : Promise.resolve()),
};

export async function askPersistentStorage() {
  try { await navigator.storage?.persist?.(); } catch { /* ignore */ }
}

// Image ids that a recording still needs (so we don't delete them).
export function blobsUsedBy(rec) {
  const ids = new Set();
  for (const ev of rec.events) {
    const list = ev.k === 'snap' ? ev.st.slides : ev.k === 'slides' ? ev.slides : null;
    if (!list) continue;
    for (const s of list) { ids.add(s.imageId); if (s.blobId) ids.add(s.blobId); }
  }
  return ids;
}

export async function deleteRecording(rec) {
  for (const p of rec.audioParts || []) await db.delBlob(p.id);
  if (rec.exportId) await db.delBlob(rec.exportId);
  if (rec.edl?.music?.blobId) await db.delBlob(rec.edl.music.blobId);
  await db.del('recordings', rec.id);
}

export async function deleteProject(p) {
  const recs = await db.byIndex('recordings', 'projectId', p.id);
  for (const r of recs) await deleteRecording(r);
  for (const s of p.slides) {
    await db.delBlob(s.imageId);
    if (s.blobId) await db.delBlob(s.blobId);
  }
  await db.del('projects', p.id);
}
