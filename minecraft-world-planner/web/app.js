'use strict';
// World Planner – Webseite. Spricht mit der Mod über /api/... (siehe ApiServer.java).
// Sicherheitsregel: Texte von Spielern immer mit textContent einsetzen, nie als HTML.

// ------------------------------------------------------------------ Helfer
const $ = id => document.getElementById(id);
const DIM_NAMES = { 'minecraft:overworld': 'Oberwelt', 'minecraft:the_nether': 'Nether', 'minecraft:the_end': 'Ende' };
const TYPE_NAMES = { city: 'Stadt', base: 'Basis', road: 'Straße', farm: 'Farm', resource: 'Rohstoffe', portal: 'Portal', other: 'Sonstiges' };
const STORE = { token: 'mcwp-token-v2', apiBase: 'mcwp-api-base-v2', view: 'mcwp-view-v2' };
const HEX = /^#[0-9a-f]{6}$/i;
const COLORS = { fog: '#0b0e13', found: '#2c3644', grid: 'rgba(255,255,255,0.06)' };

const dimName = d => DIM_NAMES[d] || d;
function storageGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
function storageSet(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* privater Modus */ } }

/** Baut ein HTML-Element. Kinder (Text oder Elemente) werden sicher angehängt. */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null && c !== false) node.append(c instanceof Node ? c : String(c));
  return node;
}
/** Ersetzt den Inhalt eines Elements. Anders als replaceChildren() wird null übersprungen statt als Text "null" angezeigt. */
function fill(node, ...children) {
  node.replaceChildren(...children.flat().filter(c => c != null && c !== false));
}
function swatch(color) {
  const s = el('span', { class: 'swatch' });
  if (HEX.test(color)) s.style.background = color;
  return s;
}
function hexA(hex, a) {
  const n = parseInt(HEX.test(hex) ? hex.slice(1) : '90a4ae', 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}
function safeUrl(value) {
  try { const u = new URL(value); return ['http:', 'https:'].includes(u.protocol) ? u.href : null; } catch { return null; }
}

let toastTimer;
function toast(msg, isError = false) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.toggle('error', isError);
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), isError ? 5000 : 2600);
}

/** Führt eine Server-Aktion aus und zeigt Erfolg oder Fehler an. */
async function act(fn, okMessage) {
  try {
    const result = await fn();
    if (okMessage) toast(typeof okMessage === 'function' ? okMessage(result) : okMessage);
    return result;
  } catch (e) {
    toast(e.message, true);
    return undefined;
  }
}

/** Kopieren mit Rückmeldung – und Ersatzweg, falls die Seite ohne HTTPS läuft. */
async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('Kopiert: ' + text);
  } catch {
    const ta = el('textarea', { readonly: true });
    ta.style.position = 'fixed'; // per JS gesetzt, weil die CSP style-Attribute verbietet
    ta.style.opacity = '0';
    ta.value = text;
    document.body.append(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch { /* geht nicht */ }
    ta.remove();
    toast(ok ? 'Kopiert: ' + text : 'Kopieren ging nicht – bitte abschreiben: ' + text, !ok);
  }
}

function tpCommand(dim, x, y, z) {
  const c = `${Math.floor(x)} ${Math.floor(y)} ${Math.floor(z)}`;
  return dim === 'minecraft:overworld' ? `/tp @s ${c}` : `/execute in ${dim} run tp @s ${c}`;
}
/** Nether ↔ Oberwelt: 1 Block im Nether = 8 Blöcke in der Oberwelt. */
function convertText(dim, x, z) {
  if (dim === 'minecraft:overworld') return `↔ Im Nether: X ${Math.floor(x / 8)} · Z ${Math.floor(z / 8)}`;
  if (dim === 'minecraft:the_nether') return `↔ In der Oberwelt: X ${Math.floor(x) * 8} · Z ${Math.floor(z) * 8}`;
  return '';
}

function askText(title, label, value = '', maxLength = 24) {
  return new Promise(resolve => {
    const dialog = $('textDialog');
    const input = $('textDialogInput');
    $('textDialogTitle').textContent = title;
    $('textDialogLabel').textContent = label;
    $('textDialogError').textContent = '';
    input.maxLength = maxLength;
    input.value = value;
    dialog.returnValue = '';
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok' ? input.value.trim() : null), { once: true });
    dialog.showModal();
    input.focus();
  });
}

// Wird die Seite unter /planer statt /planer/ geöffnet, würden relative Adressen falsch auflösen.
(function fixTrailingSlash() {
  const p = location.pathname;
  if (location.protocol.startsWith('http') && p !== '/' && !p.endsWith('/') && !p.split('/').pop().includes('.')) {
    location.replace(p + '/' + location.search + location.hash);
  }
})();

// ------------------------------------------------------------------ Zustand & Server
const state = {
  token: storageGet(STORE.token),
  apiBase: storageGet(STORE.apiBase) || '',
  me: null, world: null, players: [], lands: [], markers: [], knownPlayers: [],
  dim: 'minecraft:overworld',
  discovered: new Map(), discoveredCount: 0,
  landRender: [], chunkOwner: new Map(),
  selection: new Set(), selecting: false,
  tab: 'claim', framesLoaded: {},
  layers: { lands: true, markers: true, players: true, grid: true },
  view: { x: 0, z: 0, zoom: 0.5 }, viewRestored: false,
  hover: null,
};

function apiUrl(path) {
  let base = state.apiBase;
  if (base) { if (!base.endsWith('/')) base += '/'; }
  else if (location.protocol.startsWith('http')) base = location.href;
  else throw new Error('Keine Server-Adresse eingestellt (siehe „Erweitert“).');
  return new URL(path, base).href;
}

async function api(path, body) {
  const opts = { method: body === undefined ? 'GET' : 'POST', headers: { Accept: 'application/json' }, cache: 'no-store' };
  const usedToken = state.token;
  if (usedToken) opts.headers.Authorization = 'Bearer ' + usedToken;
  if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  let res;
  try {
    res = await fetch(apiUrl(path), opts);
  } catch (e) {
    throw new Error(e.message.includes('Server-Adresse') ? e.message : 'Server nicht erreichbar. Läuft der Minecraft-Server?');
  }
  let data = {};
  try { data = await res.json(); } catch { /* keine JSON-Antwort */ }
  // Nur abmelden, wenn das 401 zum aktuellen Login gehört – eine alte Anfrage soll keinen neuen Login beenden.
  if (res.status === 401 && path !== 'api/login' && usedToken && usedToken === state.token) loggedOut(data.error);
  if (!res.ok) throw new Error(data.error || 'Fehler ' + res.status);
  return data;
}

// ------------------------------------------------------------------ Login
function showLogin(message = '') {
  $('appView').classList.add('hidden');
  $('loginView').classList.remove('hidden');
  fill($('accountBox'), );
  $('loginError').textContent = message;
  $('apiBaseInput').value = state.apiBase;
  if (!location.protocol.startsWith('http')) $('advancedBox').open = true;
}

function loggedOut(message) {
  state.token = null;
  state.me = null;
  storageSet(STORE.token, null);
  stopPolling();
  showLogin(message || 'Bitte neu einloggen.');
}

async function login(code) {
  $('loginError').textContent = '';
  try {
    const r = await api('api/login', { code });
    state.token = r.token;
    storageSet(STORE.token, r.token);
    $('loginCode').value = '';
    await startApp();
    toast('Willkommen, ' + r.name + '!');
  } catch (e) {
    $('loginError').textContent = e.message;
  }
}

async function logout() {
  try { await api('api/logout', {}); } catch { /* egal */ }
  loggedOut('Du bist abgemeldet.');
}

function renderAccount() {
  const me = state.me;
  fill($('accountBox'), 
    el('span', { class: 'muted small' }, 'Eingeloggt als '),
    el('strong', { text: me.name }),
    me.admin ? el('span', { class: 'pill admin', text: 'Admin' }) : null,
    el('button', { class: 'small', onclick: logout }, 'Abmelden'),
  );
}

// ------------------------------------------------------------------ Laden
async function startApp() {
  state.me = await api('api/me');
  $('loginView').classList.add('hidden');
  $('appView').classList.remove('hidden');
  renderAccount();
  restoreView();
  await Promise.all([loadWorld(), loadLands(), loadMarkers(), loadPlayers(), loadKnownPlayers()]);
  await loadDiscovered();
  if (!state.viewRestored) centerOnMe(false);
  resizeCanvas();
  startPolling();
}

async function loadMe() {
  state.me = await api('api/me');
  renderAccount();
  renderMyLands();
}

async function loadWorld() {
  const w = state.world = await api('api/world');
  $('seedValue').textContent = w.seed ?? 'versteckt';
  $('versionValue').textContent = w.minecraftVersion ?? '—';
  $('serverLine').textContent = (w.worldName || 'Minecraft-Welt') + ' · ' + (w.minecraftVersion || '');
  const dims = w.dimensions && w.dimensions.length ? w.dimensions : Object.keys(DIM_NAMES);
  if (!dims.includes(state.dim)) state.dim = dims[0];
  for (const select of [$('dimSelect'), $('markerDim')]) {
    const old = select.value;
    fill(select, ...dims.map(d => el('option', { value: d, text: dimName(d) })));
    select.value = dims.includes(old) ? old : state.dim;
  }
  $('dimSelect').value = state.dim;
}

async function loadDiscovered() {
  const dim = state.dim;
  const r = await api('api/discovered?dimension=' + encodeURIComponent(dim));
  if (dim !== state.dim) return; // inzwischen umgeschaltet
  const regions = new Map();
  const flat = r.chunks || [];
  for (let i = 0; i + 1 < flat.length; i += 2) {
    const x = flat[i], z = flat[i + 1];
    const key = (x >> 5) + ',' + (z >> 5);
    let reg = regions.get(key);
    if (!reg) { reg = { rx: x >> 5, rz: z >> 5, bits: new Uint8Array(1024), canvas: null }; regions.set(key, reg); }
    reg.bits[(z & 31) * 32 + (x & 31)] = 1;
  }
  state.discovered = regions;
  state.discoveredCount = flat.length / 2;
  $('discoveredValue').textContent = state.discoveredCount.toLocaleString('de-DE');
  requestDraw();
}

async function loadLands() {
  const r = await api('api/lands');
  state.lands = r.lands || [];
  rebuildLandRender();
  renderLands();
  updateSelectionBar();
  requestDraw();
}

async function loadMarkers() {
  const r = await api('api/markers');
  state.markers = r.markers || [];
  renderMarkers();
  requestDraw();
}

async function loadPlayers() {
  const r = await api('api/players');
  state.players = r.players || [];
  $('onlineValue').textContent = r.hidden ? 'versteckt' : state.players.length;
  renderPlayers();
  requestDraw();
}

async function loadKnownPlayers() {
  const r = await api('api/known-players');
  state.knownPlayers = r.players || [];
  fill($('knownPlayers'), ...state.knownPlayers.map(n => el('option', { value: n })));
}

async function refreshAfterLandChange() {
  await Promise.all([loadLands(), loadMe()]);
}

let pollTimers = [];
function startPolling() {
  stopPolling();
  const every = (ms, fn) => pollTimers.push(setInterval(() => {
    if (!document.hidden && state.me) fn().catch(() => { /* nächster Versuch */ });
  }, ms));
  every(5000, loadPlayers);
  every(15000, () => Promise.all([loadLands(), loadMarkers()]));
  every(60000, () => Promise.all([loadDiscovered(), loadMe(), loadKnownPlayers()]));
}
function stopPolling() {
  pollTimers.forEach(clearInterval);
  pollTimers = [];
}

// ------------------------------------------------------------------ Karte: Daten
function rebuildLandRender() {
  state.chunkOwner = new Map();
  state.landRender = [];
  for (const land of state.lands) {
    if (land.dimension !== state.dim) continue;
    const keys = new Set();
    const chunks = [];
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity, sumX = 0, sumZ = 0;
    for (let i = 0; i + 1 < land.chunks.length; i += 2) {
      const x = land.chunks[i], z = land.chunks[i + 1];
      keys.add(x + ',' + z);
      chunks.push([x, z]);
      state.chunkOwner.set(x + ',' + z, land);
      minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
      sumX += x; sumZ += z;
    }
    if (!chunks.length) continue;
    state.landRender.push({ land, keys, chunks, minX, maxX, minZ, maxZ, cx: sumX / chunks.length, cz: sumZ / chunks.length });
  }
}

function isDiscovered(x, z) {
  const reg = state.discovered.get((x >> 5) + ',' + (z >> 5));
  return !!reg && reg.bits[(z & 31) * 32 + (x & 31)] === 1;
}

function regionCanvas(reg) {
  if (reg.canvas) return reg.canvas;
  const c = document.createElement('canvas');
  c.width = 32; c.height = 32;
  const g = c.getContext('2d');
  const img = g.createImageData(32, 32);
  for (let i = 0; i < 1024; i++) {
    if (!reg.bits[i]) continue;
    img.data[i * 4] = 0x2c; img.data[i * 4 + 1] = 0x36; img.data[i * 4 + 2] = 0x44; img.data[i * 4 + 3] = 255;
  }
  g.putImageData(img, 0, 0);
  reg.canvas = c;
  return c;
}

// ------------------------------------------------------------------ Karte: Zeichnen
const canvas = $('mapCanvas');
const ctx = canvas.getContext('2d');
let drawQueued = false;
function requestDraw() {
  if (!drawQueued) { drawQueued = true; requestAnimationFrame(draw); }
}
function resizeCanvas() {
  const r = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.round(r.width * dpr));
  canvas.height = Math.max(1, Math.round(r.height * dpr));
  requestDraw();
}
new ResizeObserver(resizeCanvas).observe(canvas);

function screenSize() {
  const dpr = window.devicePixelRatio || 1;
  return { w: canvas.width / dpr, h: canvas.height / dpr };
}
function toScreen(bx, bz) {
  const { w, h } = screenSize();
  return [(bx - state.view.x) * state.view.zoom + w / 2, (bz - state.view.z) * state.view.zoom + h / 2];
}
function toBlock(sx, sy) {
  const { w, h } = screenSize();
  return [(sx - w / 2) / state.view.zoom + state.view.x, (sy - h / 2) / state.view.zoom + state.view.z];
}
function chunkAt(sx, sy) {
  const [bx, bz] = toBlock(sx, sy);
  return { x: Math.floor(bx / 16), z: Math.floor(bz / 16) };
}

function draw() {
  drawQueued = false;
  const dpr = window.devicePixelRatio || 1;
  const { w, h } = screenSize();
  const zoom = state.view.zoom;
  const cpx = 16 * zoom; // Pixel pro Chunk
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = COLORS.fog;
  ctx.fillRect(0, 0, w, h);

  const [minBX, minBZ] = toBlock(0, 0);
  const [maxBX, maxBZ] = toBlock(w, h);
  const minCX = Math.floor(minBX / 16), maxCX = Math.floor(maxBX / 16);
  const minCZ = Math.floor(minBZ / 16), maxCZ = Math.floor(maxBZ / 16);

  // 1) Entdeckte Chunks (je Region ein kleines Bild, damit es auch bei vielen Chunks flüssig bleibt)
  ctx.imageSmoothingEnabled = false;
  for (let rx = minCX >> 5; rx <= maxCX >> 5; rx++) {
    for (let rz = minCZ >> 5; rz <= maxCZ >> 5; rz++) {
      const reg = state.discovered.get(rx + ',' + rz);
      if (!reg) continue;
      const [x0, z0] = toScreen(rx * 512, rz * 512);
      const [x1, z1] = toScreen((rx + 1) * 512, (rz + 1) * 512);
      ctx.drawImage(regionCanvas(reg), Math.floor(x0), Math.floor(z0), Math.ceil(x1) - Math.floor(x0), Math.ceil(z1) - Math.floor(z0));
    }
  }

  // 2) Chunk-Gitter
  if (state.layers.grid && cpx >= 10) {
    ctx.strokeStyle = COLORS.grid;
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let cx = minCX; cx <= maxCX + 1; cx++) { const [sx] = toScreen(cx * 16, 0); ctx.moveTo(Math.round(sx) + 0.5, 0); ctx.lineTo(Math.round(sx) + 0.5, h); }
    for (let cz = minCZ; cz <= maxCZ + 1; cz++) { const [, sz] = toScreen(0, cz * 16); ctx.moveTo(0, Math.round(sz) + 0.5); ctx.lineTo(w, Math.round(sz) + 0.5); }
    ctx.stroke();
  }
  // Ursprung 0/0 markieren
  const [ox, oz] = toScreen(0, 0);
  ctx.strokeStyle = 'rgba(255,255,255,0.18)';
  ctx.beginPath(); ctx.moveTo(ox - 8, oz); ctx.lineTo(ox + 8, oz); ctx.moveTo(ox, oz - 8); ctx.lineTo(ox, oz + 8); ctx.stroke();

  // 3) Länder
  if (state.layers.lands) {
    for (const r of state.landRender) {
      if (r.maxX < minCX || r.minX > maxCX || r.maxZ < minCZ || r.minZ > maxCZ) continue;
      const mine = r.land.mine;
      ctx.fillStyle = hexA(r.land.color, mine ? 0.45 : 0.3);
      for (const [x, z] of r.chunks) {
        const [sx, sz] = toScreen(x * 16, z * 16);
        ctx.fillRect(sx, sz, cpx + 0.5, cpx + 0.5);
      }
      ctx.strokeStyle = hexA(r.land.color, 1);
      ctx.lineWidth = mine ? 3 : 2;
      ctx.beginPath();
      for (const [x, z] of r.chunks) {
        const [sx, sz] = toScreen(x * 16, z * 16);
        if (!r.keys.has(x + ',' + (z - 1))) { ctx.moveTo(sx, sz); ctx.lineTo(sx + cpx, sz); }
        if (!r.keys.has((x + 1) + ',' + z)) { ctx.moveTo(sx + cpx, sz); ctx.lineTo(sx + cpx, sz + cpx); }
        if (!r.keys.has(x + ',' + (z + 1))) { ctx.moveTo(sx, sz + cpx); ctx.lineTo(sx + cpx, sz + cpx); }
        if (!r.keys.has((x - 1) + ',' + z)) { ctx.moveTo(sx, sz); ctx.lineTo(sx, sz + cpx); }
      }
      ctx.stroke();
    }
    if (cpx >= 3) {
      for (const r of state.landRender) {
        const [sx, sz] = toScreen(r.cx * 16 + 8, r.cz * 16 + 8);
        if (sx < -100 || sx > w + 100 || sz < -20 || sz > h + 20) continue;
        label(r.land.name, sx, sz, '#fff', 'bold 12px system-ui, sans-serif');
      }
    }
  }

  // 4) Auswahl
  if (state.selection.size) {
    ctx.fillStyle = 'rgba(255,255,255,0.35)';
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 1.5;
    ctx.setLineDash([4, 3]);
    for (const key of state.selection) {
      const [x, z] = key.split(',').map(Number);
      const [sx, sz] = toScreen(x * 16, z * 16);
      ctx.fillRect(sx, sz, cpx, cpx);
      ctx.strokeRect(sx + 0.75, sz + 0.75, cpx - 1.5, cpx - 1.5);
    }
    ctx.setLineDash([]);
  }

  // 5) Marker
  if (state.layers.markers) {
    for (const m of state.markers) {
      if (m.dimension !== state.dim) continue;
      const [sx, sz] = toScreen(m.x + 0.5, m.z + 0.5);
      if (sx < -20 || sx > w + 20 || sz < -20 || sz > h + 20) continue;
      ctx.fillStyle = m.public ? '#ffd88a' : '#c5a3ff';
      ctx.strokeStyle = '#000a';
      ctx.lineWidth = 1.5;
      ctx.beginPath(); ctx.moveTo(sx, sz - 7); ctx.lineTo(sx + 6, sz); ctx.lineTo(sx, sz + 7); ctx.lineTo(sx - 6, sz); ctx.closePath();
      ctx.fill(); ctx.stroke();
      if (zoom >= 0.25) label(m.name, sx, sz - 13, '#ffe7b3', '11px system-ui, sans-serif');
    }
  }

  // 6) Spieler
  if (state.layers.players) {
    for (const p of state.players) {
      if (p.dimension !== state.dim) continue;
      const [sx, sz] = toScreen(p.x, p.z);
      const isMe = state.me && p.uuid === state.me.uuid;
      ctx.fillStyle = isMe ? '#8bcf9b' : '#6fb7ff';
      ctx.strokeStyle = '#000';
      ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc(sx, sz, 5, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      label(p.name, sx, sz - 12, '#fff', '11px system-ui, sans-serif');
    }
  }

  // 7) Chunk unter der Maus
  if (state.hover && cpx >= 4) {
    const [sx, sz] = toScreen(state.hover.x * 16, state.hover.z * 16);
    ctx.strokeStyle = 'rgba(255,255,255,0.8)';
    ctx.lineWidth = 1;
    ctx.strokeRect(sx + 0.5, sz + 0.5, cpx - 1, cpx - 1);
  }
}

function label(text, x, y, color, font) {
  ctx.font = font;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.lineWidth = 3;
  ctx.lineJoin = 'round'; // sonst gibt es spitze Zacken an den Buchstaben
  ctx.strokeStyle = 'rgba(0,0,0,0.75)';
  ctx.strokeText(text, x, y);
  ctx.fillStyle = color;
  ctx.fillText(text, x, y);
}

// ------------------------------------------------------------------ Karte: Bedienung
const pointers = new Map();
let drag = null;

function zoomAt(sx, sy, factor) {
  const [bx, bz] = toBlock(sx, sy);
  state.view.zoom = Math.min(4, Math.max(0.02, state.view.zoom * factor));
  const [nx, nz] = toBlock(sx, sy);
  state.view.x += bx - nx;
  state.view.z += bz - nz;
  saveViewSoon();
  requestDraw();
}

function centerOn(x, z, zoom) {
  state.view.x = x;
  state.view.z = z;
  if (zoom) state.view.zoom = zoom;
  saveViewSoon();
  requestDraw();
}

canvas.addEventListener('pointerdown', e => {
  canvas.setPointerCapture(e.pointerId);
  pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
  hideChunkCard();
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    drag = { mode: 'pinch', dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, moved: true };
    return;
  }
  if (state.selecting && e.button === 0) {
    const c = chunkAt(e.offsetX, e.offsetY);
    const key = c.x + ',' + c.z;
    drag = { mode: 'paint', add: !state.selection.has(key), last: key, moved: false };
    paintChunk(c, drag.add, true);
  } else {
    drag = { mode: 'pan', startX: e.offsetX, startY: e.offsetY, lastX: e.offsetX, lastY: e.offsetY, moved: false };
    canvas.classList.add('dragging');
  }
});

canvas.addEventListener('pointermove', e => {
  if (pointers.has(e.pointerId)) pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
  updateHover(e.offsetX, e.offsetY);
  if (!drag) return;
  if (drag.mode === 'pinch' && pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    const dist = Math.hypot(a.x - b.x, a.y - b.y) || 1;
    zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, dist / drag.dist);
    drag.dist = dist;
  } else if (drag.mode === 'pan') {
    state.view.x -= (e.offsetX - drag.lastX) / state.view.zoom;
    state.view.z -= (e.offsetY - drag.lastY) / state.view.zoom;
    drag.lastX = e.offsetX;
    drag.lastY = e.offsetY;
    if (Math.hypot(e.offsetX - drag.startX, e.offsetY - drag.startY) > 4) drag.moved = true;
    saveViewSoon();
    requestDraw();
  } else if (drag.mode === 'paint') {
    const c = chunkAt(e.offsetX, e.offsetY);
    const key = c.x + ',' + c.z;
    if (key !== drag.last) { drag.last = key; paintChunk(c, drag.add, false); }
  }
});

function endPointer(e) {
  pointers.delete(e.pointerId);
  if (drag && drag.mode === 'pan' && !drag.moved && e.type === 'pointerup') showChunkCard(chunkAt(e.offsetX, e.offsetY));
  if (pointers.size === 0) drag = null;
  canvas.classList.remove('dragging');
}
canvas.addEventListener('pointerup', endPointer);
canvas.addEventListener('pointercancel', endPointer);
canvas.addEventListener('pointerleave', () => { state.hover = null; $('hoverInfo').classList.add('hidden'); requestDraw(); });
canvas.addEventListener('wheel', e => { e.preventDefault(); zoomAt(e.offsetX, e.offsetY, Math.exp(-e.deltaY * 0.0015)); }, { passive: false });

function updateHover(sx, sy) {
  const c = chunkAt(sx, sy);
  const [bx, bz] = toBlock(sx, sy);
  if (!state.hover || state.hover.x !== c.x || state.hover.z !== c.z) { state.hover = c; requestDraw(); }
  const land = state.chunkOwner.get(c.x + ',' + c.z);
  const status = land ? '»' + land.name + '«' : isDiscovered(c.x, c.z) ? 'frei' : 'unentdeckt';
  const info = $('hoverInfo');
  info.textContent = `X ${Math.floor(bx)} · Z ${Math.floor(bz)} · Chunk ${c.x}/${c.z} · ${status}`;
  info.classList.remove('hidden');
}

function chunkStatus(c) {
  const land = state.chunkOwner.get(c.x + ',' + c.z);
  if (land) return { land, text: 'Gehört zu »' + land.name + '« – ' + land.owners.map(o => o.name).join(', ') };
  if (!isDiscovered(c.x, c.z)) return { text: 'Noch nicht entdeckt' };
  return { free: true, text: 'Frei' };
}

function showChunkCard(c) {
  const st = chunkStatus(c);
  const card = $('chunkCard');
  const bx = c.x * 16, bz = c.z * 16;
  const canClaim = st.free || (!st.land && !state.me.claimOnlyDiscovered);
  fill(card, 
    el('div', { class: 'item-top' }, el('span', { text: `Chunk ${c.x} / ${c.z}` }),
      el('button', { class: 'small', 'aria-label': 'Schließen', onclick: hideChunkCard }, '×')),
    el('small', { class: 'muted', text: `Blöcke X ${bx} bis ${bx + 15} · Z ${bz} bis ${bz + 15}` }),
    el('div', { text: st.text }),
    st.land && st.land.description ? el('small', { class: 'muted', text: st.land.description }) : null,
    el('div', { class: 'row' },
      canClaim ? el('button', { class: 'primary small', onclick: () => { setSelecting(true); paintChunk(c, true, true); hideChunkCard(); } }, 'Claimen') : null,
      el('button', { class: 'small', onclick: () => { prefillMarker(bx + 8, 64, bz + 8, state.dim); hideChunkCard(); } }, 'Marker hier'),
      el('button', { class: 'small', onclick: () => showIn3D(bx + 8, 64, bz + 8, 'live') }, 'In 3D zeigen'),
    ),
  );
  card.classList.remove('hidden');
}
function hideChunkCard() { $('chunkCard').classList.add('hidden'); }

// ------------------------------------------------------------------ Auswählen & Claimen
function setSelecting(on) {
  state.selecting = on;
  $('selectModeBtn').classList.toggle('active', on);
  $('selectModeBtn').textContent = on ? '✓ Auswahl fertig' : '✚ Auswählen';
  canvas.classList.toggle('selecting', on);
  updateSelectionBar();
}

function paintChunk(c, add, explain) {
  const key = c.x + ',' + c.z;
  if (!add) { state.selection.delete(key); updateSelectionBar(); requestDraw(); return; }
  const st = chunkStatus(c);
  if (st.land && !(st.land.mine || state.me.admin)) { if (explain) toast(st.text, true); return; }
  if (!st.land && !st.free && state.me.claimOnlyDiscovered) { if (explain) toast('Dieser Chunk ist noch nicht entdeckt.', true); return; }
  if (state.selection.size >= 512) { if (explain) toast('Höchstens 512 Chunks auf einmal.', true); return; }
  state.selection.add(key);
  updateSelectionBar();
  requestDraw();
}

function selectionInfo() {
  const flat = [];
  let free = 0;
  const lands = new Set();
  for (const key of state.selection) {
    const [x, z] = key.split(',').map(Number);
    flat.push(x, z);
    const land = state.chunkOwner.get(key);
    if (land) lands.add(land); else free++;
  }
  return { flat, free, lands, total: state.selection.size };
}

function updateSelectionBar() {
  const bar = $('selectionBar');
  const info = selectionInfo();
  bar.classList.toggle('hidden', info.total === 0 && !state.selecting);
  $('selectionCount').textContent = info.total ? info.total + (info.total === 1 ? ' Chunk' : ' Chunks') : 'Klicke Chunks an';
  const allFree = info.total > 0 && info.free === info.total;
  const myLandsHere = state.lands.filter(l => l.mine && l.dimension === state.dim);
  const select = $('addToLandSelect');
  const old = select.value;
  fill(select, ...myLandsHere.map(l => el('option', { value: l.id, text: l.name })));
  if (myLandsHere.some(l => l.id === old)) select.value = old;
  $('newLandBtn').disabled = !allFree;
  $('addToLandBtn').disabled = !allFree || !myLandsHere.length;
  select.disabled = !myLandsHere.length;
  const single = info.lands.size === 1 && info.free === 0 ? [...info.lands][0] : null;
  $('removeFromLandBtn').disabled = !(single && (single.mine || state.me.admin));
}

function clearSelection() {
  state.selection.clear();
  updateSelectionBar();
  requestDraw();
}

async function createLandFromSelection() {
  const info = selectionInfo();
  const name = await askText('Neues Land', 'Name des Landes (2–24 Zeichen)');
  if (!name) return;
  const r = await act(() => api('api/lands', { name, dimension: state.dim, chunks: info.flat }), `Land »${name}« erstellt!`);
  if (r) { clearSelection(); setSelecting(false); await refreshAfterLandChange(); }
}

async function addSelectionToLand() {
  const id = $('addToLandSelect').value;
  const land = state.lands.find(l => l.id === id);
  const info = selectionInfo();
  const r = await act(() => api(`api/lands/${encodeURIComponent(id)}/chunks`, { add: info.flat }),
    `${info.total} Chunks zu »${land ? land.name : 'Land'}« hinzugefügt.`);
  if (r) { clearSelection(); await refreshAfterLandChange(); }
}

async function removeSelectionFromLand() {
  const info = selectionInfo();
  const land = [...info.lands][0];
  if (!land || !confirm(`${info.total} Chunks aus »${land.name}« entfernen?`)) return;
  const r = await act(() => api(`api/lands/${encodeURIComponent(land.id)}/chunks`, { remove: info.flat }),
    res => res.deleted ? `»${land.name}« hatte keine Chunks mehr und wurde aufgelöst.` : 'Chunks entfernt.');
  if (r) { clearSelection(); await refreshAfterLandChange(); }
}

// ------------------------------------------------------------------ Tabs & 3D-Karten
const TAB_NOTES = {
  claim: 'Gezeigt werden nur Chunks, in deren Nähe schon ein Spieler war. Länder sind eine Anzeige – im Spiel wird nichts geschützt.',
  live: 'Die echte Welt, gerendert von BlueMap. Länder und öffentliche Marker erscheinen dort als eigene Ebenen.',
  seed: 'Die Seed-Vorschau kommt von einem extra Server mit gleichem Seed. Sie zeigt die ganze Welt – auch Unentdecktes und ohne Gebautes.',
};

function setTab(tab) {
  state.tab = tab;
  document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  $('claimView').classList.toggle('hidden', tab !== 'claim');
  $('liveView').classList.toggle('hidden', tab !== 'live');
  $('seedView').classList.toggle('hidden', tab !== 'seed');
  $('tabNote').textContent = TAB_NOTES[tab];
  if (tab === 'claim') resizeCanvas(); else ensureFrame(tab);
}

function mapUrl(kind) {
  const maps = state.me && state.me.maps;
  if (!maps) return '';
  if (kind === 'seed') return maps.seedUrl;
  if (maps.liveUrl) return maps.liveUrl;
  // Nichts eingetragen, aber BlueMap läuft: BlueMaps Standard-Port 8100 auf demselben Rechner probieren.
  if (maps.blueMapDetected && location.protocol === 'http:') return `http://${location.hostname}:8100/`;
  return '';
}

function ensureFrame(kind, hash) {
  const url = safeUrl(mapUrl(kind) || '');
  const frame = $(kind + 'Frame'), empty = $(kind + 'Empty');
  if (!url) { frame.classList.add('hidden'); empty.classList.remove('hidden'); return false; }
  if (location.protocol === 'https:' && url.startsWith('http:')) {
    fill(empty, el('h2', { text: 'Karte blockiert' }),
      el('p', { text: 'Diese Seite läuft über https://, die Karte über http://. Browser blockieren das. Der Admin muss BlueMap auch über https:// erreichbar machen.' }));
    frame.classList.add('hidden'); empty.classList.remove('hidden');
    return false;
  }
  frame.classList.remove('hidden');
  empty.classList.add('hidden');
  if (hash || !state.framesLoaded[kind]) {
    frame.src = hash ? url.split('#')[0] + '#' + hash : url;
    state.framesLoaded[kind] = true;
  }
  return true;
}

/** Öffnet BlueMap genau an dieser Stelle (BlueMap-Adressformat: #karte:x:y:z:abstand:drehung:winkel:neigung:ortho:modus). */
function showIn3D(x, y, z, kind = 'live') {
  const ids = (state.me && state.me.maps && state.me.maps.mapIds) || {};
  const mapId = ids[state.dim];
  if (!mapUrl(kind)) { toast('Der Admin hat noch keine 3D-Karte eingerichtet.', true); return; }
  if (!mapId) { toast('Für ' + dimName(state.dim) + ' ist keine BlueMap-Karte eingestellt.', true); return; }
  setTab(kind);
  ensureFrame(kind, `${mapId}:${Math.round(x)}:${Math.round(y)}:${Math.round(z)}:150:0:0:0:0:perspective`);
}

async function setDimension(dim) {
  if (dim === state.dim) return;
  state.dim = dim;
  $('dimSelect').value = dim;
  state.discovered = new Map();
  clearSelection();
  rebuildLandRender();
  updateSelectionBar();
  saveViewSoon();
  requestDraw();
  await act(loadDiscovered);
}

// ------------------------------------------------------------------ Seitenleiste: Länder
function ownersText(land) {
  return land.owners.map(o => o.name).join(', ');
}

function zoomToLand(land) {
  const go = () => {
    const r = state.landRender.find(x => x.land.id === land.id);
    if (!r) return;
    const { w, h } = screenSize();
    const spanX = (r.maxX - r.minX + 3) * 16, spanZ = (r.maxZ - r.minZ + 3) * 16;
    const zoom = Math.min(4, Math.max(0.05, Math.min(w / spanX, h / spanZ)));
    centerOn(((r.minX + r.maxX + 1) / 2) * 16, ((r.minZ + r.maxZ + 1) / 2) * 16, zoom);
  };
  setTab('claim');
  if (land.dimension !== state.dim) setDimension(land.dimension).then(go); else go();
}

function renderLands() {
  renderInvites();
  renderMyLands();
  renderAllLands();
}

function renderInvites() {
  const invites = state.lands.filter(l => l.invitedMe);
  $('invitesPanel').classList.toggle('hidden', !invites.length);
  fill($('invitesList'), ...invites.map(land => el('div', { class: 'item' },
    el('div', { class: 'item-top' }, el('span', { class: 'name' }, swatch(land.color), land.name), el('span', { class: 'pill', text: dimName(land.dimension) })),
    el('small', { text: `${land.chunkCount} Chunks · Besitzer: ${ownersText(land)}` }),
    el('div', { class: 'row' },
      el('button', { class: 'primary small', onclick: () => landAction(land, 'accept', {}, `Du besitzt jetzt »${land.name}« mit!`) }, 'Annehmen'),
      el('button', { class: 'small', onclick: () => landAction(land, 'decline', {}, 'Einladung abgelehnt.') }, 'Ablehnen'),
      el('button', { class: 'small', onclick: () => zoomToLand(land) }, 'Zeigen'),
    ),
  )));
}

async function landAction(land, action, body, okMessage) {
  const r = await act(() => api(`api/lands/${encodeURIComponent(land.id)}/${action}`, body), okMessage);
  if (r) await refreshAfterLandChange();
  return r;
}

function renderMyLands() {
  if (!state.me) return;
  $('usagePill').textContent = `${state.me.usedChunks} / ${state.me.maxChunks} Chunks`;
  const mine = state.lands.filter(l => l.mine);
  const box = $('myLands');
  if (!mine.length) { fill(box, el('div', { class: 'empty', text: 'Du hast noch kein Land.' })); return; }
  fill(box, ...mine.map(land => {
    const inviteInput = el('input', { list: 'knownPlayers', placeholder: 'Spielername', 'aria-label': 'Spieler einladen', class: 'grow' });
    const invite = async () => {
      const player = inviteInput.value.trim();
      if (!player) return;
      const r = await landAction(land, 'invite', { player }, `${player} ist eingeladen und muss noch annehmen.`);
      if (r) inviteInput.value = '';
    };
    inviteInput.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); invite(); } });

    const editBox = el('div', { class: 'hidden' });
    const nameIn = el('input', { value: land.name, maxlength: 24, 'aria-label': 'Name', class: 'grow' });
    const colorIn = el('input', { type: 'color', value: HEX.test(land.color) ? land.color : '#90a4ae', 'aria-label': 'Farbe' });
    const descIn = el('input', { value: land.description || '', maxlength: 200, placeholder: 'Beschreibung (optional)', 'aria-label': 'Beschreibung' });
    descIn.style.width = '100%';
    editBox.append(
      el('div', { class: 'row' }, nameIn, colorIn),
      el('div', { class: 'row' }, descIn),
      el('div', { class: 'row' }, el('button', { class: 'primary small', onclick: () => landAction(land, 'edit',
        { name: nameIn.value, color: colorIn.value, description: descIn.value }, 'Gespeichert.') }, 'Speichern')),
    );

    return el('div', { class: 'item' },
      el('div', { class: 'item-top' }, el('span', { class: 'name' }, swatch(land.color), land.name), el('span', { class: 'pill', text: dimName(land.dimension) })),
      el('small', { text: `${land.chunkCount} Chunks` + (land.description ? ' · ' + land.description : '') }),
      el('div', { class: 'sub' }, 'Besitzer: ', ...land.owners.map(o => el('span', { class: 'chip', text: o.name }))),
      land.invites && land.invites.length ? el('div', { class: 'sub' }, 'Eingeladen: ', ...land.invites.map(p =>
        el('span', { class: 'chip' }, p.name, el('button', { title: 'Einladung zurückziehen', 'aria-label': 'Einladung zurückziehen',
          onclick: () => landAction(land, 'uninvite', { player: p.name }, 'Einladung zurückgezogen.') }, '×')))) : null,
      el('div', { class: 'row nowrap' }, inviteInput, el('button', { class: 'small', onclick: invite }, 'Einladen')),
      el('div', { class: 'row' },
        el('button', { class: 'small', onclick: () => zoomToLand(land) }, 'Zeigen'),
        el('button', { class: 'small', onclick: () => editBox.classList.toggle('hidden') }, 'Bearbeiten'),
        el('button', { class: 'small danger', onclick: () => {
          const last = land.owners.length === 1;
          if (confirm(last ? `Du bist der letzte Besitzer. Wenn du austrittst, wird »${land.name}« frei. Wirklich?` : `Aus »${land.name}« austreten? Die anderen behalten das Land.`)) {
            landAction(land, 'leave', {}, r => r.deleted ? `»${land.name}« ist jetzt frei.` : 'Du bist ausgetreten.');
          }
        } }, 'Austreten'),
      ),
      editBox,
    );
  }));
}

function renderAllLands() {
  const q = $('landSearch').value.trim().toLowerCase();
  const list = state.lands
    .filter(l => !q || l.name.toLowerCase().includes(q) || l.owners.some(o => o.name.toLowerCase().includes(q)))
    .sort((a, b) => a.name.localeCompare(b.name, 'de'));
  $('landCount').textContent = state.lands.length;
  const box = $('allLands');
  if (!list.length) { fill(box, el('div', { class: 'empty', text: q ? 'Nichts gefunden.' : 'Noch keine Länder auf dem Server.' })); return; }
  const admin = state.me && state.me.admin;
  fill(box, ...list.map(land => el('div', { class: 'item' },
    el('div', { class: 'item-top' }, el('span', { class: 'name' }, swatch(land.color), land.name), el('span', { class: 'pill', text: dimName(land.dimension) })),
    el('div', { class: 'sub' }, 'Besitzer: ', ...land.owners.map(o => el('span', { class: 'chip' }, o.name,
      admin ? el('button', { title: 'Als Admin entfernen', 'aria-label': 'Besitzer entfernen', onclick: () => {
        if (confirm(`${o.name} als Admin aus »${land.name}« entfernen?`)) landAction(land, 'remove-owner', { player: o.name }, 'Entfernt.');
      } }, '×') : null))),
    el('small', { text: `${land.chunkCount} Chunks` + (land.description ? ' · ' + land.description : '') }),
    el('div', { class: 'row' },
      el('button', { class: 'small', onclick: () => zoomToLand(land) }, 'Zeigen'),
      admin ? el('button', { class: 'small danger', onclick: () => {
        if (confirm(`»${land.name}« als Admin komplett löschen?`)) landAction(land, 'delete', {}, 'Land gelöscht.');
      } }, 'Löschen (Admin)') : null,
    ),
  )));
}

// ------------------------------------------------------------------ Seitenleiste: Marker
function prefillMarker(x, y, z, dim) {
  resetMarkerForm();
  $('coordX').value = Math.floor(x);
  $('coordY').value = Math.floor(y);
  $('coordZ').value = Math.floor(z);
  $('markerDim').value = dim;
  updateConvertHint();
  $('markerName').focus();
  $('markerForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function resetMarkerForm() {
  $('markerForm').reset();
  $('markerId').value = '';
  $('markerDim').value = state.dim;
  $('markerSubmit').textContent = 'Marker speichern';
  $('markerCancelBtn').classList.add('hidden');
  updateConvertHint();
}

function updateConvertHint() {
  $('convertHint').textContent = convertText($('markerDim').value, Number($('coordX').value), Number($('coordZ').value));
}

function editMarker(m) {
  $('markerId').value = m.id;
  $('markerName').value = m.name;
  $('markerType').value = m.type;
  $('markerDim').value = m.dimension;
  $('coordX').value = m.x; $('coordY').value = m.y; $('coordZ').value = m.z;
  $('markerNotes').value = m.notes;
  $('markerPublic').checked = m.public;
  $('markerSubmit').textContent = 'Änderung speichern';
  $('markerCancelBtn').classList.remove('hidden');
  updateConvertHint();
  $('markerForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function showMarker(m) {
  setTab('claim');
  const go = () => centerOn(m.x, m.z, Math.max(state.view.zoom, 1));
  if (m.dimension !== state.dim) setDimension(m.dimension).then(go); else go();
}

function renderMarkers() {
  const q = $('markerSearch').value.trim().toLowerCase();
  const list = state.markers.filter(m => !q || m.name.toLowerCase().includes(q) || m.notes.toLowerCase().includes(q) || m.ownerName.toLowerCase().includes(q));
  $('markerCount').textContent = state.markers.length + ' Marker';
  const box = $('markerList');
  if (!list.length) { fill(box, el('div', { class: 'empty', text: q ? 'Nichts gefunden.' : 'Noch keine Marker.' })); return; }
  fill(box, ...list.map(m => el('div', { class: 'item' },
    el('div', { class: 'item-top' }, el('span', { class: 'name', text: m.name }), el('span', { class: 'pill', text: TYPE_NAMES[m.type] || m.type })),
    el('small', { text: `${dimName(m.dimension)} · X ${m.x} · Y ${m.y} · Z ${m.z} · von ${m.ownerName}${m.public ? '' : ' · privat'}` }),
    convertText(m.dimension, m.x, m.z) ? el('small', { text: convertText(m.dimension, m.x, m.z) }) : null,
    m.notes ? el('small', { text: m.notes }) : null,
    el('div', { class: 'row' },
      el('button', { class: 'small', onclick: () => showMarker(m) }, 'Zeigen'),
      el('button', { class: 'small', onclick: () => copyText(tpCommand(m.dimension, m.x, m.y, m.z)) }, 'TP-Befehl kopieren'),
      m.canEdit ? el('button', { class: 'small', onclick: () => editMarker(m) }, 'Bearbeiten') : null,
      m.canEdit ? el('button', { class: 'small danger', onclick: async () => {
        if (!confirm(`Marker »${m.name}« löschen?`)) return;
        if (await act(() => api(`api/markers/${encodeURIComponent(m.id)}/delete`, {}), 'Marker gelöscht.')) loadMarkers();
      } }, 'Löschen') : null,
    ),
  )));
}

async function saveMarker(e) {
  e.preventDefault();
  const id = $('markerId').value;
  const data = {
    name: $('markerName').value.trim(), type: $('markerType').value, dimension: $('markerDim').value,
    x: Number($('coordX').value), y: Number($('coordY').value), z: Number($('coordZ').value),
    notes: $('markerNotes').value.trim(), public: $('markerPublic').checked,
  };
  const r = await act(() => api(id ? `api/markers/${encodeURIComponent(id)}/edit` : 'api/markers', data), id ? 'Marker geändert.' : 'Marker gespeichert.');
  if (r) { resetMarkerForm(); await loadMarkers(); }
}

function exportMarkers() {
  const own = state.markers.filter(m => m.mine).map(({ name, type, dimension, x, y, z, notes, public: isPublic }) =>
    ({ name, type, dimension, x, y, z, notes, public: isPublic }));
  const blob = new Blob([JSON.stringify({ schemaVersion: 2, markers: own }, null, 2)], { type: 'application/json' });
  const a = el('a', { href: URL.createObjectURL(blob), download: 'world-planner-marker.json' });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000); // sofortiges Freigeben bricht in manchen Browsern den Download ab
}

async function importMarkers(file) {
  let incoming;
  try {
    const parsed = JSON.parse(await file.text());
    incoming = Array.isArray(parsed) ? parsed : parsed.markers;
    if (!Array.isArray(incoming)) throw new Error();
  } catch {
    toast('Die Datei ist keine gültige Marker-Datei.', true);
    return;
  }
  const valid = incoming.filter(m => m && typeof m.name === 'string' && [m.x, m.y, m.z].every(v => Number.isFinite(Number(v))));
  if (!valid.length) { toast('Keine gültigen Marker in der Datei.', true); return; }
  if (!confirm(`${valid.length} Marker importieren? (Deine bestehenden Marker bleiben erhalten.)`)) return;
  let ok = 0, failed = 0;
  for (const m of valid) {
    const type = String(m.type || 'other').toLowerCase();
    try {
      await api('api/markers', {
        name: m.name.slice(0, 48), type: TYPE_NAMES[type] ? type : 'other',
        dimension: typeof m.dimension === 'string' ? m.dimension : 'minecraft:overworld',
        x: Number(m.x), y: Number(m.y), z: Number(m.z),
        notes: typeof m.notes === 'string' ? m.notes.slice(0, 500) : '', public: m.public !== false,
      });
      ok++;
    } catch { failed++; }
  }
  toast(`${ok} Marker importiert` + (failed ? `, ${failed} fehlgeschlagen.` : '.'), failed > 0);
  await loadMarkers();
}

// ------------------------------------------------------------------ Seitenleiste: Spieler
function myPlayer() {
  return state.me && state.players.find(p => p.uuid === state.me.uuid);
}

function centerOnMe(complain = true) {
  const p = myPlayer();
  if (!p) { if (complain) toast('Du bist gerade nicht auf dem Server.', true); return; }
  setTab('claim');
  const go = () => centerOn(p.x, p.z, Math.max(state.view.zoom, 0.8));
  if (p.dimension !== state.dim) setDimension(p.dimension).then(go); else go();
}

function renderPlayers() {
  const box = $('playerList');
  if (!state.players.length) { fill(box, el('div', { class: 'empty', text: 'Gerade ist niemand online.' })); return; }
  fill(box, ...state.players.map(p => el('div', { class: 'item' },
    el('div', { class: 'item-top' }, el('span', { text: p.name }), el('span', { class: 'pill', text: dimName(p.dimension) })),
    el('small', { text: `X ${Math.floor(p.x)} · Y ${Math.floor(p.y)} · Z ${Math.floor(p.z)}` }),
    el('div', { class: 'row' },
      el('button', { class: 'small', onclick: () => {
        setTab('claim');
        const go = () => centerOn(p.x, p.z, Math.max(state.view.zoom, 0.8));
        if (p.dimension !== state.dim) setDimension(p.dimension).then(go); else go();
      } }, 'Zeigen'),
      el('button', { class: 'small', onclick: () => copyText(`${Math.floor(p.x)} ${Math.floor(p.y)} ${Math.floor(p.z)}`) }, 'Koordinaten kopieren'),
    ),
  )));
}

// ------------------------------------------------------------------ Ansicht merken
let saveViewTimer;
function saveViewSoon() {
  clearTimeout(saveViewTimer);
  saveViewTimer = setTimeout(() => storageSet(STORE.view, JSON.stringify({ ...state.view, dim: state.dim })), 400);
}
function restoreView() {
  try {
    const v = JSON.parse(storageGet(STORE.view) || 'null');
    if (v && [v.x, v.z, v.zoom].every(Number.isFinite)) {
      state.view = { x: v.x, z: v.z, zoom: Math.min(4, Math.max(0.02, v.zoom)) };
      if (typeof v.dim === 'string') state.dim = v.dim;
      state.viewRestored = true;
    }
  } catch { /* egal */ }
}

// ------------------------------------------------------------------ Start
function setupUi() {
  $('loginForm').addEventListener('submit', e => { e.preventDefault(); login($('loginCode').value); });
  $('apiBaseForm').addEventListener('submit', e => {
    e.preventDefault();
    const value = $('apiBaseInput').value.trim();
    if (value && !safeUrl(value)) { $('loginError').textContent = 'Die Adresse muss mit http:// oder https:// beginnen.'; return; }
    state.apiBase = value;
    storageSet(STORE.apiBase, value || null);
    toast(value ? 'Server-Adresse gespeichert.' : 'Server-Adresse zurückgesetzt.');
  });

  document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => setTab(b.dataset.tab)));
  $('dimSelect').addEventListener('change', e => setDimension(e.target.value));
  document.querySelectorAll('[data-layer]').forEach(cb => cb.addEventListener('change', () => {
    state.layers[cb.dataset.layer] = cb.checked;
    requestDraw();
  }));
  $('selectModeBtn').addEventListener('click', () => setSelecting(!state.selecting));
  $('centerMeBtn').addEventListener('click', () => centerOnMe(true));
  $('zoomInBtn').addEventListener('click', () => { const { w, h } = screenSize(); zoomAt(w / 2, h / 2, 1.5); });
  $('zoomOutBtn').addEventListener('click', () => { const { w, h } = screenSize(); zoomAt(w / 2, h / 2, 1 / 1.5); });
  $('newLandBtn').addEventListener('click', createLandFromSelection);
  $('addToLandBtn').addEventListener('click', addSelectionToLand);
  $('removeFromLandBtn').addEventListener('click', removeSelectionFromLand);
  $('clearSelectionBtn').addEventListener('click', clearSelection);
  $('landSearch').addEventListener('input', renderAllLands);

  $('markerForm').addEventListener('submit', saveMarker);
  $('markerCancelBtn').addEventListener('click', resetMarkerForm);
  $('markerHereBtn').addEventListener('click', () => {
    const p = myPlayer();
    if (!p) { toast('Du bist gerade nicht auf dem Server.', true); return; }
    $('coordX').value = Math.floor(p.x); $('coordY').value = Math.floor(p.y); $('coordZ').value = Math.floor(p.z);
    $('markerDim').value = p.dimension;
    updateConvertHint();
  });
  ['coordX', 'coordZ', 'markerDim'].forEach(id => $(id).addEventListener('input', updateConvertHint));
  $('markerSearch').addEventListener('input', renderMarkers);
  $('exportBtn').addEventListener('click', exportMarkers);
  $('importBtn').addEventListener('click', () => $('importFile').click());
  $('importFile').addEventListener('change', async e => {
    const file = e.target.files && e.target.files[0];
    e.target.value = '';
    if (file) await importMarkers(file);
  });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && state.me) loadPlayers().catch(() => {}); });
}

async function init() {
  setupUi();
  updateConvertHint();
  const match = location.hash.match(/login=([A-Za-z0-9-]+)/);
  if (match) history.replaceState(null, '', location.pathname + location.search);
  if (match) { showLogin(); $('loginCode').value = match[1]; await login(match[1]); return; }
  if (state.token) {
    try {
      await startApp();
    } catch (e) {
      if (state.token) showLogin(e.message); // bei 401 hat loggedOut() schon die Login-Seite gezeigt
    }
    return;
  }
  showLogin();
}

init();
