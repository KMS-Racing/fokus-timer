// Live-Welt und Seed-Vorschau: BlueMap im iframe, an derselben Stelle wie die Claim-Karte.
//
// Im Code von BlueMap 5.29 nachgeprüft (nicht geraten):
// - Die Adresse #karte:x:y:z:abstand:drehung:winkel:neigung:ortho:modus wird beim Laden gelesen und bei jeder
//   Änderung übernommen (BlueMap hört auf "hashchange") – die Karte lädt dafür NICHT neu.
// - Die Kamera hat 75° Blickwinkel. Von oben (winkel 0) sieht man bei Abstand d etwa 1,53 × d Blöcke in der Höhe.
// - Von oben erlaubt BlueMap höchstens ~2000 Blöcke Abstand (MapControls.getMaxDistanceForPerspectiveAngle).
// Die iframes bleiben die ganze Zeit im Dokument (nur versteckt) – ein iframe aus dem DOM zu nehmen würde
// BlueMap jedes Mal komplett neu laden.
import { map, centerOn, clampZoom, isDiscovered, lastMapHeight } from './map.js';
import { load, store } from './store.js';
import { dimName, el, fill, safeUrl, toast } from './util.js';

const FOV_DEG = 75;
const VIEW_RATIO = 2 * Math.tan((FOV_DEG / 2) * Math.PI / 180);
const MIN_DISTANCE = 30;
export const MAX_DISTANCE = 2000;
export const SHOW_POINT_DISTANCE = 150;

export const bm = {
  framesLoaded: {}, sent: {}, target: {}, statusClosed: {}, reach: {}, pendingHash: {}, loadingTimer: {},
  active: null, // 'live' | 'seed' | null
};

const panes = {};

export function blueMapPane(kind) {
  if (panes[kind]) return panes[kind].root;
  const frame = el('iframe', { id: kind + 'Frame', class: 'map-frame hidden', title: kind === 'live' ? 'Live-Welt (BlueMap)' : 'Seed-Vorschau (BlueMap)',
    referrerpolicy: 'no-referrer', allow: 'fullscreen' });
  frame.addEventListener('load', () => hideLoading(kind));
  const emptyBox = el('div', { id: kind + 'Empty', class: 'map-empty hidden' });
  const status = el('div', { id: kind + 'Status', class: 'map-status hidden', role: 'status' });
  const loading = el('div', { id: kind + 'Loading', class: 'map-loading hidden', role: 'status' });
  const root = el('div', { class: 'map-pane', id: kind + 'Pane' }, frame, emptyBox, status, loading);
  panes[kind] = { root, frame, emptyBox, status, loading };
  return root;
}

export function mapUrl(kind) {
  const maps = store.me && store.me.maps;
  if (!maps) return '';
  if (kind === 'seed') return maps.seedUrl;
  if (maps.liveUrl) return maps.liveUrl;
  // Nichts eingetragen, aber BlueMap läuft: BlueMaps Standard-Port 8100 auf demselben Rechner probieren.
  if (maps.blueMapDetected && location.protocol === 'http:') return `http://${location.hostname}:8100/`;
  return '';
}

/** Höhe des Kartenbereichs. Claim-Karte und 3D-Karte sind gleich groß, deshalb passt derselbe Ausschnitt. */
function areaHeight() {
  const h = lastMapHeight();
  if (h > 50) return h;
  const p = panes.live && panes.live.root.clientHeight;
  return p || 600;
}

/** Welcher BlueMap-Abstand zeigt ungefähr denselben Ausschnitt wie die Claim-Karte? */
export function distanceForZoom(zoom) {
  const visibleBlocks = areaHeight() / zoom;
  return Math.round(Math.min(MAX_DISTANCE, Math.max(MIN_DISTANCE, visibleBlocks / VIEW_RATIO)));
}

export function zoomForDistance(distance) {
  return clampZoom(areaHeight() / (distance * VIEW_RATIO));
}

export function claimViewTarget() {
  return { dim: map.dim, x: Math.round(map.view.x), z: Math.round(map.view.z), distance: distanceForZoom(map.view.zoom) };
}

export function blueMapId(dim) {
  const ids = (store.me && store.me.maps && store.me.maps.mapIds) || {};
  return ids[dim];
}

function blueMapHash(mapId, t) {
  return `${mapId}:${t.x}:64:${t.z}:${t.distance}:0:0:0:0:perspective`;
}

/** Hat sich die Claim-Karte seit dem letzten Abgleich so bewegt, dass BlueMap nachziehen soll? */
function viewChangedSince(sent, t) {
  if (!sent) return true;
  if (sent.dim !== t.dim) return true;
  const moved = Math.hypot(sent.x - t.x, sent.z - t.z);
  const zoomed = Math.abs(Math.log(t.distance / sent.distance));
  return moved > t.distance * 0.25 || zoomed > Math.log(1.3);
}

/**
 * Zeigt eine 3D-Karte an derselben Stelle wie die Claim-Karte. Hat man sich in BlueMap selbst bewegt und die
 * Claim-Karte seitdem nicht angefasst, bleibt BlueMap, wo man war.
 */
export function openBlueMap(kind) {
  bm.active = kind;
  blueMapPane(kind);
  const target = claimViewTarget();
  const mapId = blueMapId(target.dim);
  const needsMove = !bm.framesLoaded[kind] || viewChangedSince(bm.sent[kind], target);
  const shown = ensureFrame(kind, mapId && needsMove ? blueMapHash(mapId, target) : null);
  if (shown && mapId && needsMove) {
    bm.sent[kind] = target;
    bm.statusClosed[kind] = false;
  }
  bm.target[kind] = target;
  updateMapStatus(kind);
  if (kind === 'live') load.blueMapStatus().then(() => updateMapStatus('live')).catch(() => { /* Status ist nur ein Extra */ });
}

export function closeBlueMap() {
  bm.active = null;
}

/**
 * Zeigt das iframe. Beim ersten Mal wird geprüft, ob BlueMap überhaupt antwortet – sonst gäbe es nur eine
 * leere Fläche ohne Erklärung. Gibt true zurück, wenn die Karte angezeigt wird (oder gerade lädt).
 */
export function ensureFrame(kind, hash) {
  const p = panes[kind];
  const url = safeUrl(mapUrl(kind) || '');
  if (!url) { p.frame.classList.add('hidden'); showMessage(kind, null); return false; }
  if (location.protocol === 'https:' && url.startsWith('http:')) {
    showMessage(kind, 'Karte blockiert', 'Diese Seite läuft über https://, die Karte über http://. Browser blockieren das. Der Admin muss BlueMap auch über https:// erreichbar machen.');
    return false;
  }
  if (hash) bm.pendingHash[kind] = hash;
  const reach = bm.reach[kind];
  if (reach === 'ok') {
    p.emptyBox.classList.add('hidden');
    p.frame.classList.remove('hidden');
    if (bm.pendingHash[kind] || !bm.framesLoaded[kind]) {
      const h = bm.pendingHash[kind];
      if (!bm.framesLoaded[kind]) startLoading(kind);
      p.frame.src = h ? url.split('#')[0] + '#' + h : url;
      bm.framesLoaded[kind] = true;
      bm.pendingHash[kind] = null;
    }
    return true;
  }
  if (reach !== 'checking') probe(kind, url);
  return true;
}

/** Fragt BlueMap einmal an. "no-cors" reicht: Wir wollen nur wissen, ob überhaupt jemand antwortet. */
async function probe(kind, url) {
  const p = panes[kind];
  bm.reach[kind] = 'checking';
  p.frame.classList.add('hidden');
  p.emptyBox.classList.add('hidden');
  showLoading(kind, '3D-Karte wird geprüft …');
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 6000);
  let result = 'ok';
  try {
    await fetch(url, { mode: 'no-cors', cache: 'no-store', signal: ctrl.signal });
  } catch {
    result = ctrl.signal.aborted ? 'timeout' : 'unreachable';
  } finally {
    clearTimeout(timer);
  }
  hideLoading(kind);
  bm.reach[kind] = result;
  if (result === 'ok') {
    if (bm.active === kind) { ensureFrame(kind); updateMapStatus(kind); }
    return;
  }
  const name = kind === 'live' ? 'Die Live-Karte (BlueMap)' : 'Die Seed-Vorschau';
  showMessage(kind, result === 'timeout' ? 'BlueMap antwortet nicht' : 'BlueMap ist nicht erreichbar',
    `${name} unter ${url} ${result === 'timeout' ? 'hat nicht innerhalb von 6 Sekunden geantwortet' : 'ist gerade nicht erreichbar'}. ` +
    'Läuft der Server mit BlueMap? Ist die Adresse richtig? Die Claim-Karte und alles andere funktionieren trotzdem.', true);
}

function showMessage(kind, title, text, retry) {
  const p = panes[kind];
  p.frame.classList.add('hidden');
  p.status.classList.add('hidden');
  if (title) {
    fill(p.emptyBox, el('h2', { text: title }), el('p', { text }),
      retry ? el('button', { class: 'primary', onclick: () => { bm.reach[kind] = null; ensureFrame(kind); } }, 'Erneut versuchen') : null);
  } else {
    fill(p.emptyBox, el('h2', { text: kind === 'live' ? 'Keine Live-Karte eingerichtet' : 'Keine Seed-Vorschau eingerichtet' }),
      el('p', { text: kind === 'live'
        ? 'Der Admin kann in config/world-planner.json bei liveMapUrl die BlueMap-Adresse eintragen. Ohne BlueMap funktionieren Claim-Karte, Marker und alles andere trotzdem.'
        : 'Die Seed-Vorschau braucht einen zweiten Server mit demselben Seed und BlueMap. Der Admin trägt dessen Adresse bei seedMapUrl ein.' }));
  }
  p.emptyBox.classList.remove('hidden');
}

function showLoading(kind, text) {
  const p = panes[kind];
  p.loading.textContent = text;
  p.loading.classList.remove('hidden');
}

function hideLoading(kind) {
  if (!panes[kind]) return;
  panes[kind].loading.classList.add('hidden');
  clearTimeout(bm.loadingTimer[kind]);
}

/** Ladeanzeige bis das iframe fertig ist – aber nie endlos: nach 15 Sekunden kommt ein Hinweis. */
function startLoading(kind) {
  showLoading(kind, '3D-Karte wird geladen …');
  clearTimeout(bm.loadingTimer[kind]);
  bm.loadingTimer[kind] = setTimeout(() => {
    hideLoading(kind);
    toast('Die 3D-Karte lädt ungewöhnlich lange. Eventuell ist der Server gerade stark beschäftigt.', true);
  }, 15000);
}

/** Hinweis-Leiste über der 3D-Karte: erklärt schwarze Flächen, statt sie wie einen Fehler wirken zu lassen. */
export function updateMapStatus(kind) {
  const p = panes[kind];
  if (!p) return;
  const box = p.status;
  const target = bm.target[kind];
  if (!target || !safeUrl(mapUrl(kind) || '') || bm.statusClosed[kind] || bm.reach[kind] !== 'ok') {
    box.classList.add('hidden');
    return;
  }
  const lines = [];
  if (!blueMapId(target.dim)) {
    lines.push({ warn: true, text: `Für ${dimName(target.dim)} kennt der Planer keine BlueMap-Karte – BlueMap zeigt deshalb die letzte Karte. Der Admin kann sie in blueMapIds eintragen.` });
  }
  if (kind === 'live') {
    const cx = Math.floor(target.x / 16), cz = Math.floor(target.z / 16);
    if (target.dim === map.dim && map.discoveredCount > 0 && !isDiscovered(cx, cz)) {
      lines.push({ warn: true, text: 'Hier war noch niemand: Dieser Teil der Welt ist noch nicht erzeugt, darum ist die 3D-Karte hier schwarz. Auf der Claim-Karte sind entdeckte Gebiete grau.' });
    }
    const st = store.blueMapStatus;
    if (st && st.available && st.renderQueue > 0) {
      lines.push({ text: `⏳ BlueMap rendert gerade (${st.renderQueue} ${st.renderQueue === 1 ? 'Aufgabe' : 'Aufgaben'} in der Warteschlange). Neue Gebiete erscheinen nach und nach.` });
    } else if (st && st.available && st.paused) {
      lines.push({ warn: true, text: 'BlueMap rendert auf dem Server gerade nicht (pausiert). Ein Admin kann es mit /bluemap start fortsetzen.' });
    }
  } else {
    lines.push({ text: 'Die Vorschau zeigt nur, was auf dem Vorschau-Server erzeugt und gerendert wurde – ohne Gebautes.' });
  }
  lines.push({ small: true, text: 'Schwarze Flächen = dort ist (noch) nichts gerendert. Das ist kein Fehler.' });
  fill(box,
    el('div', { class: 'grow' }, ...lines.map(l => el('div', { class: l.small ? 'small muted' : l.warn ? 'warn' : '', text: l.text }))),
    el('button', { class: 'small ghost', 'aria-label': 'Hinweis schließen', onclick: () => { bm.statusClosed[kind] = true; box.classList.add('hidden'); } }, '×'),
  );
  box.classList.remove('hidden');
}

/**
 * „In 3D zeigen“: stellt die Claim-Karte auf den Punkt und öffnet BlueMap nah daran.
 * navigate() kommt aus dem Router (wird übergeben, damit hier kein Kreis-Import entsteht).
 */
export function showIn3D(x, z, navigate, kind = 'live') {
  if (!mapUrl(kind)) { toast('Der Admin hat noch keine 3D-Karte eingerichtet.', true); return; }
  if (!blueMapId(map.dim)) { toast('Für ' + dimName(map.dim) + ' ist keine BlueMap-Karte eingestellt.', true); return; }
  centerOn(x, z, zoomForDistance(SHOW_POINT_DISTANCE));
  bm.sent[kind] = null; // erzwingt die Fahrt – auch wenn man sich in BlueMap vorher selbst bewegt hat
  if (bm.active === kind) openBlueMap(kind); else navigate('#/' + kind);
}
