// App-wide preferences stored on this device.
const KEY = 'slidevox.settings';

const defaults = {
  res: '1080',          // '1080' | '720'
  bg: '#0e0c0a',        // letterbox colour behind images
  countdown: true,      // 3-2-1 before recording
  scriptSpeed: 6,       // 1..20
  scriptFont: 22,       // px
  scriptOn: true,
  scriptPos: 'top',     // 'top' | 'bottom'
  tool: 'pen',
  color: '#e8453c',
  size: 1,
};

let data = { ...defaults };
try { Object.assign(data, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch { /* ignore */ }

export const settings = new Proxy(data, {
  set(t, k, v) {
    t[k] = v;
    try { localStorage.setItem(KEY, JSON.stringify(t)); } catch { /* ignore */ }
    return true;
  },
});

export function stageSize() {
  return settings.res === '720' ? { w: 1280, h: 720 } : { w: 1920, h: 1080 };
}
