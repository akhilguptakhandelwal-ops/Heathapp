// App-wide preferences stored on this device.
const KEY = 'slidevox.settings';

const defaults = {
  autoSave: true,       // APK: save every export to Documents/SlideVox
  exportPreset: 'high',
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

// Recording canvas. Export can be any size; drawings are scaled.
export function stageSize(aspect = '16:9') {
  return aspect === '9:16' ? { w: 1080, h: 1920 } : { w: 1920, h: 1080 };
}
