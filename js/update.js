// Checks GitHub for a newer Android build and opens the download.
import { BUILD, CHANNEL, REPO, APK_URL } from './version.js';
import { native } from './util.js';

export async function latestBuild() {
  const r = await fetch(`https://api.github.com/repos/${REPO}/releases/tags/apk-latest`, { cache: 'no-store' });
  if (!r.ok) throw new Error(`GitHub said ${r.status}`);
  const j = await r.json();
  const m = /build (\d+)/i.exec(j.body || '');
  return m ? +m[1] : 0;
}

// Returns {current, latest, newer} — or null when not running as the APK.
export async function checkForUpdate() {
  if (CHANNEL !== 'apk') return null;
  const latest = await latestBuild();
  return { current: BUILD, latest, newer: latest > BUILD };
}

export async function openApkDownload() {
  const b = native()?.Plugins?.Browser;
  if (b) await b.open({ url: APK_URL });
  else window.open(APK_URL, '_blank');
}

export const versionLabel = () => (CHANNEL === 'apk' ? `Android app · build ${BUILD}` : 'Web app');
