// Full-screen look at one picture (no recording). Single tap closes it.
import { viewMatrix, fitView } from './renderer.js';
import { db } from './db.js';
import { loadImage, h } from './util.js';

export async function openPeek({ imageId, w, h: ih, rot = 0, bitmap = null, label = '' }) {
  const img = bitmap || await loadImage(await db.getBlob(imageId));
  const cv = h('canvas', { class: 'peek-cv' });
  const wrap = h('div', { class: 'peek', role: 'dialog' }, cv,
    h('div', { class: 'peek-hint' }, `${label ? `${label} · ` : ''}Tap anywhere to close`));
  document.body.append(wrap);
  const draw = () => {
    const dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(window.innerWidth * dpr);
    cv.height = Math.round(window.innerHeight * dpr);
    const c = cv.getContext('2d');
    c.fillStyle = '#000';
    c.fillRect(0, 0, cv.width, cv.height);
    const m = viewMatrix(cv.width, cv.height, { w, h: ih }, fitView(rot));
    c.setTransform(m.a, m.b, m.c, m.d, m.e, m.f);
    c.imageSmoothingQuality = 'high';
    c.drawImage(img, 0, 0, w, ih);
  };
  draw();
  window.addEventListener('resize', draw);
  return new Promise((resolve) => {
    // Close on the next tap (ignore the tail of the double tap that opened it).
    const opened = performance.now();
    wrap.addEventListener('pointerup', (e) => {
      e.stopPropagation();
      if (performance.now() - opened < 250) return;
      window.removeEventListener('resize', draw);
      wrap.remove();
      resolve();
    });
  });
}
