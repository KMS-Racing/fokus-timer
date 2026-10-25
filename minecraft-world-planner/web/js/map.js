// Die 2D-Claim-Karte (Canvas). Eine einzige Karte für die ganze Seite: Beim Wechsel der Ansicht bleibt sie
// erhalten (Position, Zoom, Auswahl), damit man nicht jedes Mal neu suchen muss.
//
// Koordinaten: Blöcke (x, z). Ein Chunk = 16 Blöcke, eine Region = 32 Chunks = 512 Blöcke. Norden ist oben (−Z).
import { api, enc } from './api.js';
import { store, on } from './store.js';
import { hexA, storageJson, storageSet } from './util.js';

const VIEW_KEY = 'mcwp-view-v3';
const LAYER_KEY = 'mcwp-layers-v1';
export const MIN_ZOOM = 0.02;
export const MAX_ZOOM = 4;
const COLORS = { fog: '#0b0e13', found: [0x2c, 0x36, 0x44], grid: 'rgba(255,255,255,0.06)', region: 'rgba(130,170,255,0.28)' };
const TYPE_COLORS = {
  base: '#8bcf9b', farm: '#c5e17a', village: '#e0b47a', portal: '#b388ff', spawner: '#ff8a80', resource: '#80deea',
  project: '#ffcc80', meeting: '#90caf9', danger: '#ff5252', city: '#ffd88a', road: '#bcaaa4', other: '#e0e0e0',
};

export const DEFAULT_LAYERS = { discovered: true, grid: true, regions: false, lands: true, markers: true, players: true, projects: true, spawn: true, labels: true };

export const map = {
  dim: 'minecraft:overworld',
  view: { x: 0, z: 0, zoom: 0.8 },
  viewRestored: false,
  layers: { ...DEFAULT_LAYERS, ...storageJson(LAYER_KEY, {}) },
  discovered: new Map(), discoveredCount: 0, discoveredVersion: {},
  landRender: [], chunkOwner: new Map(),
  selection: new Set(), selecting: false,
  hover: null, highlight: null,
  onClick: null, onHover: null, onSelection: null, onViewChange: null, onDimension: null,
};

let canvas = null, ctx = null, wrap = null;
let drawQueued = false;
/** Letzte echte Höhe der Karte – auch wenn sie gerade versteckt ist (für den Abgleich mit BlueMap). */
let lastHeight = 0;
export const lastMapHeight = () => lastHeight;

// ------------------------------------------------------------------ Aufbau

/** Erzeugt die Karte einmal. Gibt den Container zurück, den eine Ansicht bei sich einhängen kann. */
export function mapElement() {
  if (wrap) return wrap;
  wrap = document.createElement('div');
  wrap.className = 'map-canvas-wrap';
  canvas = document.createElement('canvas');
  canvas.id = 'mapCanvas';
  canvas.setAttribute('aria-label', 'Claim-Karte. Ziehen zum Verschieben, Mausrad oder zwei Finger zum Zoomen.');
  canvas.tabIndex = 0;
  wrap.append(canvas);
  ctx = canvas.getContext('2d');
  new ResizeObserver(resize).observe(canvas);
  setupPointer();
  restoreView();
  on('lands', () => { rebuildLands(); requestDraw(); });
  for (const k of ['markers', 'players', 'projects', 'world', 'me', 'teams']) on(k, requestDraw);
  rebuildLands(); // die Länder können schon geladen sein, bevor es die Karte gibt
  return wrap;
}

function resize() {
  if (!canvas) return;
  const r = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.round(r.width * dpr));
  canvas.height = Math.max(1, Math.round(r.height * dpr));
  if (r.height > 50) lastHeight = r.height;
  requestDraw();
}

export function requestDraw() {
  if (!canvas || drawQueued) return;
  drawQueued = true;
  requestAnimationFrame(draw);
}

export function screenSize() {
  const dpr = window.devicePixelRatio || 1;
  return { w: canvas ? canvas.width / dpr : 800, h: canvas ? canvas.height / dpr : 600 };
}

export function toScreen(bx, bz) {
  const { w, h } = screenSize();
  return [(bx - map.view.x) * map.view.zoom + w / 2, (bz - map.view.z) * map.view.zoom + h / 2];
}

export function toBlock(sx, sy) {
  const { w, h } = screenSize();
  return [(sx - w / 2) / map.view.zoom + map.view.x, (sy - h / 2) / map.view.zoom + map.view.z];
}

export function chunkAt(sx, sy) {
  const [bx, bz] = toBlock(sx, sy);
  return { x: Math.floor(bx / 16), z: Math.floor(bz / 16) };
}

// ------------------------------------------------------------------ Daten

function rebuildLands() {
  map.chunkOwner = new Map();
  map.landRender = [];
  for (const land of store.lands) {
    if (land.dimension !== map.dim) continue;
    const keys = new Set();
    const chunks = [];
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity, sumX = 0, sumZ = 0;
    for (let i = 0; i + 1 < land.chunks.length; i += 2) {
      const x = land.chunks[i], z = land.chunks[i + 1];
      keys.add(x + ',' + z);
      chunks.push([x, z]);
      map.chunkOwner.set(x + ',' + z, land);
      minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
      sumX += x; sumZ += z;
    }
    if (!chunks.length) continue;
    // Beschriftung auf den Chunk legen, der dem Schwerpunkt am nächsten liegt (bei L-Formen liegt der Schwerpunkt sonst daneben)
    const mx = sumX / chunks.length, mz = sumZ / chunks.length;
    let best = chunks[0], bestD = Infinity;
    for (const c of chunks) { const d = (c[0] - mx) ** 2 + (c[1] - mz) ** 2; if (d < bestD) { bestD = d; best = c; } }
    map.landRender.push({ land, keys, chunks, minX, maxX, minZ, maxZ, labelX: best[0], labelZ: best[1] });
  }
}

export function isDiscovered(x, z) {
  const reg = map.discovered.get((x >> 5) + ',' + (z >> 5));
  return !!reg && reg.bits[(z & 31) * 32 + (x & 31)] === 1;
}

/** Lädt die entdeckten Chunks der aktuellen Dimension (nur, wenn sich auf dem Server etwas geändert hat). */
export async function loadDiscovered(force = false) {
  const dim = map.dim;
  const since = !force && map.discoveredVersion[dim] != null ? '&since=' + map.discoveredVersion[dim] : '';
  const r = await api('api/discovered?dimension=' + enc(dim) + since);
  if (dim !== map.dim || r.unchanged) return;
  const regions = new Map();
  const flat = r.chunks || [];
  for (let i = 0; i + 1 < flat.length; i += 2) {
    const x = flat[i], z = flat[i + 1];
    const key = (x >> 5) + ',' + (z >> 5);
    let reg = regions.get(key);
    if (!reg) { reg = { rx: x >> 5, rz: z >> 5, bits: new Uint8Array(1024), canvas: null }; regions.set(key, reg); }
    reg.bits[(z & 31) * 32 + (x & 31)] = 1;
  }
  map.discovered = regions;
  map.discoveredCount = flat.length / 2;
  map.discoveredVersion[dim] = r.version;
  requestDraw();
  if (map.onDimension) map.onDimension();
}

export async function setDimension(dim) {
  if (dim === map.dim) return;
  map.dim = dim;
  map.discovered = new Map();
  map.discoveredCount = 0;
  delete map.discoveredVersion[dim];
  clearSelection();
  rebuildLands();
  saveViewSoon();
  requestDraw();
  if (map.onDimension) map.onDimension();
  await loadDiscovered(true);
}

function regionCanvas(reg) {
  if (reg.canvas) return reg.canvas;
  const c = document.createElement('canvas');
  c.width = 32; c.height = 32;
  const g = c.getContext('2d');
  const img = g.createImageData(32, 32);
  for (let i = 0; i < 1024; i++) {
    if (!reg.bits[i]) continue;
    img.data[i * 4] = COLORS.found[0]; img.data[i * 4 + 1] = COLORS.found[1]; img.data[i * 4 + 2] = COLORS.found[2]; img.data[i * 4 + 3] = 255;
  }
  g.putImageData(img, 0, 0);
  reg.canvas = c;
  return c;
}

// ------------------------------------------------------------------ Zeichnen

function draw() {
  drawQueued = false;
  if (!canvas || !canvas.isConnected) return;
  const dpr = window.devicePixelRatio || 1;
  const { w, h } = screenSize();
  const zoom = map.view.zoom;
  const cpx = 16 * zoom; // Pixel pro Chunk
  const L = map.layers;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = COLORS.fog;
  ctx.fillRect(0, 0, w, h);
  placed = [];

  const [minBX, minBZ] = toBlock(0, 0);
  const [maxBX, maxBZ] = toBlock(w, h);
  const minCX = Math.floor(minBX / 16), maxCX = Math.floor(maxBX / 16);
  const minCZ = Math.floor(minBZ / 16), maxCZ = Math.floor(maxBZ / 16);

  // 1) Entdeckte Chunks: je Region ein kleines Bild – bleibt auch bei vielen Chunks flüssig
  ctx.imageSmoothingEnabled = false;
  if (L.discovered) {
    for (let rx = minCX >> 5; rx <= maxCX >> 5; rx++) {
      for (let rz = minCZ >> 5; rz <= maxCZ >> 5; rz++) {
        const reg = map.discovered.get(rx + ',' + rz);
        if (!reg) continue;
        const [x0, z0] = toScreen(rx * 512, rz * 512);
        const [x1, z1] = toScreen((rx + 1) * 512, (rz + 1) * 512);
        ctx.drawImage(regionCanvas(reg), Math.floor(x0), Math.floor(z0), Math.ceil(x1) - Math.floor(x0), Math.ceil(z1) - Math.floor(z0));
      }
    }
  }

  // 2) Gitter: Chunks (ab genug Zoom) und Regionen (512 Blöcke)
  ctx.lineWidth = 1;
  if (L.grid && cpx >= 10) {
    ctx.strokeStyle = COLORS.grid;
    ctx.beginPath();
    for (let cx = minCX; cx <= maxCX + 1; cx++) { const [sx] = toScreen(cx * 16, 0); ctx.moveTo(Math.round(sx) + 0.5, 0); ctx.lineTo(Math.round(sx) + 0.5, h); }
    for (let cz = minCZ; cz <= maxCZ + 1; cz++) { const [, sz] = toScreen(0, cz * 16); ctx.moveTo(0, Math.round(sz) + 0.5); ctx.lineTo(w, Math.round(sz) + 0.5); }
    ctx.stroke();
  }
  if (L.regions) {
    ctx.strokeStyle = COLORS.region;
    ctx.setLineDash([6, 4]);
    ctx.beginPath();
    for (let rx = Math.floor(minBX / 512); rx <= Math.floor(maxBX / 512) + 1; rx++) { const [sx] = toScreen(rx * 512, 0); ctx.moveTo(Math.round(sx) + 0.5, 0); ctx.lineTo(Math.round(sx) + 0.5, h); }
    for (let rz = Math.floor(minBZ / 512); rz <= Math.floor(maxBZ / 512) + 1; rz++) { const [, sz] = toScreen(0, rz * 512); ctx.moveTo(0, Math.round(sz) + 0.5); ctx.lineTo(w, Math.round(sz) + 0.5); }
    ctx.stroke();
    ctx.setLineDash([]);
    if (zoom >= 0.08) {
      for (let rx = Math.floor(minBX / 512); rx <= Math.floor(maxBX / 512); rx++) {
        for (let rz = Math.floor(minBZ / 512); rz <= Math.floor(maxBZ / 512); rz++) {
          const [sx, sz] = toScreen(rx * 512, rz * 512);
          label(`r.${rx}.${rz}`, sx + 26, sz + 10, 'rgba(160,190,255,0.8)', '10px system-ui, sans-serif');
        }
      }
    }
  }
  // Ursprung 0/0
  const [ox, oz] = toScreen(0, 0);
  ctx.strokeStyle = 'rgba(255,255,255,0.22)';
  ctx.beginPath(); ctx.moveTo(ox - 8, oz); ctx.lineTo(ox + 8, oz); ctx.moveTo(ox, oz - 8); ctx.lineTo(ox, oz + 8); ctx.stroke();

  // 3) Länder
  if (L.lands) {
    for (const r of map.landRender) {
      if (r.maxX < minCX || r.minX > maxCX || r.maxZ < minCZ || r.minZ > maxCZ) continue;
      const mine = r.land.mine;
      const highlighted = map.highlight === r.land.id;
      ctx.fillStyle = hexA(r.land.color, highlighted ? 0.6 : mine ? 0.45 : 0.3);
      for (const [x, z] of r.chunks) {
        const [sx, sz] = toScreen(x * 16, z * 16);
        ctx.fillRect(sx, sz, cpx + 0.5, cpx + 0.5);
      }
      ctx.strokeStyle = highlighted ? '#ffffff' : hexA(r.land.color, 1);
      ctx.lineWidth = highlighted ? 3.5 : mine ? 3 : 2;
      if (r.land.teamId && !mine) ctx.setLineDash([6, 3]); // Team-Land: gestrichelt
      ctx.beginPath();
      for (const [x, z] of r.chunks) {
        const [sx, sz] = toScreen(x * 16, z * 16);
        if (!r.keys.has(x + ',' + (z - 1))) { ctx.moveTo(sx, sz); ctx.lineTo(sx + cpx, sz); }
        if (!r.keys.has((x + 1) + ',' + z)) { ctx.moveTo(sx + cpx, sz); ctx.lineTo(sx + cpx, sz + cpx); }
        if (!r.keys.has(x + ',' + (z + 1))) { ctx.moveTo(sx, sz + cpx); ctx.lineTo(sx + cpx, sz + cpx); }
        if (!r.keys.has((x - 1) + ',' + z)) { ctx.moveTo(sx, sz); ctx.lineTo(sx, sz + cpx); }
      }
      ctx.stroke();
      ctx.setLineDash([]);
    }
    if (L.labels && cpx >= 3) {
      // Eigene Länder zuerst beschriften – bei Platzmangel fallen eher fremde Beschriftungen weg
      const order = map.landRender.slice().sort((a, b) => (b.land.mine - a.land.mine) || (b.chunks.length - a.chunks.length));
      for (const r of order) {
        const [sx, sz] = toScreen(r.labelX * 16 + 8, r.labelZ * 16 + 8);
        if (sx < -100 || sx > w + 100 || sz < -20 || sz > h + 20) continue;
        const tag = r.land.teamId ? teamTag(r.land.teamId) : null;
        label(tag ? `[${tag}] ${r.land.name}` : r.land.name, sx, sz, '#fff', 'bold 12px system-ui, sans-serif', true);
      }
    }
  }

  // 4) Auswahl
  if (map.selection.size) {
    ctx.fillStyle = 'rgba(255,255,255,0.35)';
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 1.5;
    ctx.setLineDash([4, 3]);
    for (const key of map.selection) {
      const [x, z] = key.split(',').map(Number);
      const [sx, sz] = toScreen(x * 16, z * 16);
      ctx.fillRect(sx, sz, cpx, cpx);
      if (cpx >= 4) ctx.strokeRect(sx + 0.75, sz + 0.75, cpx - 1.5, cpx - 1.5);
    }
    ctx.setLineDash([]);
  }

  // 5) Spawn
  const spawn = store.world && store.world.spawn;
  if (L.spawn && spawn && spawn.dimension === map.dim) {
    const [sx, sz] = toScreen(spawn.x + 0.5, spawn.z + 0.5);
    star(sx, sz, 8, '#ffe082');
    if (L.labels) label('Spawn', sx, sz + 16, '#ffe082', 'bold 11px system-ui, sans-serif', true);
  }

  // 6) Projekte mit Ort
  if (L.projects) {
    for (const p of store.projects) {
      const loc = p.location;
      if (!loc || loc.dimension !== map.dim) continue;
      const [sx, sz] = toScreen(loc.x + 0.5, loc.z + 0.5);
      if (sx < -30 || sx > w + 30 || sz < -30 || sz > h + 30) continue;
      flag(sx, sz, p.status === 'done' ? '#8bcf9b' : '#ffb74d');
      if (L.labels && zoom >= 0.25) label(p.name, sx, sz - 24, '#ffd7a0', '11px system-ui, sans-serif', true);
    }
  }

  // 7) Marker
  if (L.markers) {
    for (const m of store.markers) {
      if (m.dimension !== map.dim) continue;
      const [sx, sz] = toScreen(m.x + 0.5, m.z + 0.5);
      if (sx < -20 || sx > w + 20 || sz < -20 || sz > h + 20) continue;
      const color = m.color || TYPE_COLORS[m.type] || TYPE_COLORS.other;
      ctx.fillStyle = color;
      ctx.strokeStyle = m.visibility === 'public' ? 'rgba(0,0,0,0.7)' : '#d1b3ff';
      ctx.lineWidth = m.visibility === 'public' ? 1.5 : 2.5;
      ctx.beginPath(); ctx.moveTo(sx, sz - 7); ctx.lineTo(sx + 6, sz); ctx.lineTo(sx, sz + 7); ctx.lineTo(sx - 6, sz); ctx.closePath();
      ctx.fill(); ctx.stroke();
      if (m.symbol && zoom >= 0.5) label(m.symbol, sx, sz + 16, color, '12px system-ui, sans-serif');
      if (L.labels && zoom >= 0.25) label(m.name, sx, sz - 13, '#ffe7b3', '11px system-ui, sans-serif', true);
    }
  }

  // 8) Spieler
  if (L.players) {
    for (const p of store.players) {
      if (p.dimension !== map.dim) continue;
      const [sx, sz] = toScreen(p.x, p.z);
      const isMe = store.me && p.uuid === store.me.uuid;
      ctx.fillStyle = isMe ? '#8bcf9b' : '#6fb7ff';
      ctx.strokeStyle = '#000';
      ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc(sx, sz, 5, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      label(p.name, sx, sz - 12, '#fff', '11px system-ui, sans-serif');
    }
  }

  // 9) Chunk unter der Maus
  if (map.hover && cpx >= 4) {
    const [sx, sz] = toScreen(map.hover.x * 16, map.hover.z * 16);
    ctx.strokeStyle = 'rgba(255,255,255,0.85)';
    ctx.lineWidth = 1;
    ctx.strokeRect(sx + 0.5, sz + 0.5, cpx - 1, cpx - 1);
  }
}

function teamTag(teamId) {
  const t = store.teams.find(x => x.id === teamId);
  return t ? t.tag : null;
}

/** Schon belegte Flächen für Beschriftungen in diesem Bild (gegen übereinander geschriebene Namen). */
let placed = [];

function label(text, x, y, color, font, avoidOverlap = false) {
  ctx.font = font;
  if (avoidOverlap) {
    const tw = ctx.measureText(text).width + 6, th = 15;
    const box = { x0: x - tw / 2, x1: x + tw / 2, y0: y - th / 2, y1: y + th / 2 };
    if (placed.some(b => box.x0 < b.x1 && box.x1 > b.x0 && box.y0 < b.y1 && box.y1 > b.y0)) return;
    placed.push(box);
  }
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.lineWidth = 3;
  ctx.lineJoin = 'round';
  ctx.strokeStyle = 'rgba(0,0,0,0.75)';
  ctx.strokeText(text, x, y);
  ctx.fillStyle = color;
  ctx.fillText(text, x, y);
}

function star(x, y, r, color) {
  ctx.beginPath();
  for (let i = 0; i < 10; i++) {
    const a = -Math.PI / 2 + i * Math.PI / 5;
    const rr = i % 2 ? r * 0.45 : r;
    ctx.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
  }
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.strokeStyle = 'rgba(0,0,0,0.8)';
  ctx.lineWidth = 1.5;
  ctx.fill();
  ctx.stroke();
}

function flag(x, y, color) {
  ctx.strokeStyle = 'rgba(0,0,0,0.85)';
  ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x, y - 18); ctx.stroke();
  ctx.fillStyle = color;
  ctx.beginPath(); ctx.moveTo(x, y - 18); ctx.lineTo(x + 11, y - 14); ctx.lineTo(x, y - 10); ctx.closePath();
  ctx.fill();
  ctx.lineWidth = 1;
  ctx.stroke();
}

// ------------------------------------------------------------------ Bewegen

export function clampZoom(z) {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, z));
}

export function zoomAt(sx, sy, factor) {
  const [bx, bz] = toBlock(sx, sy);
  map.view.zoom = clampZoom(map.view.zoom * factor);
  const [nx, nz] = toBlock(sx, sy);
  map.view.x += bx - nx;
  map.view.z += bz - nz;
  viewChanged();
}

export function zoomBy(factor) {
  const { w, h } = screenSize();
  zoomAt(w / 2, h / 2, factor);
}

export function centerOn(x, z, zoom) {
  map.view.x = x;
  map.view.z = z;
  if (zoom) map.view.zoom = clampZoom(zoom);
  viewChanged();
}

/** Zeigt ein Rechteck (Blöcke) möglichst groß, mit etwas Rand. */
export function fitBlocks(minX, minZ, maxX, maxZ) {
  const { w, h } = screenSize();
  const spanX = Math.max(48, maxX - minX + 48), spanZ = Math.max(48, maxZ - minZ + 48);
  centerOn((minX + maxX + 1) / 2, (minZ + maxZ + 1) / 2, Math.min(w / spanX, h / spanZ));
}

export async function showLand(land) {
  if (land.dimension !== map.dim) await setDimension(land.dimension);
  const b = land.bounds;
  map.highlight = land.id;
  fitBlocks(b.minX, b.minZ, b.maxX, b.maxZ);
  setTimeout(() => { if (map.highlight === land.id) { map.highlight = null; requestDraw(); } }, 4000);
}

export async function showPoint(dim, x, z, zoom = 1.5) {
  if (dim && dim !== map.dim) await setDimension(dim);
  centerOn(x, z, Math.max(map.view.zoom, zoom));
}

function viewChanged() {
  saveViewSoon();
  requestDraw();
  if (map.onViewChange) map.onViewChange();
}

let saveTimer;
function saveViewSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => storageSet(VIEW_KEY, JSON.stringify({ ...map.view, dim: map.dim })), 400);
}

function restoreView() {
  const v = storageJson(VIEW_KEY, null);
  if (v && [v.x, v.z, v.zoom].every(Number.isFinite)) {
    map.view = { x: v.x, z: v.z, zoom: clampZoom(v.zoom) };
    if (typeof v.dim === 'string') map.dim = v.dim;
    map.viewRestored = true;
  }
}

export function setLayer(name, on) {
  map.layers[name] = on;
  storageSet(LAYER_KEY, JSON.stringify(map.layers));
  requestDraw();
}

// ------------------------------------------------------------------ Auswahl

export function chunkStatus(c) {
  const land = map.chunkOwner.get(c.x + ',' + c.z);
  if (land) return { land, text: 'Gehört zu »' + land.name + '« – ' + land.owners.map(o => o.name).join(', ') };
  if (!isDiscovered(c.x, c.z)) return { text: 'Noch nicht entdeckt' };
  return { free: true, text: 'Frei' };
}

export function setSelecting(on) {
  map.selecting = on;
  if (canvas) canvas.classList.toggle('selecting', on);
  if (map.onSelection) map.onSelection();
}

/** Fügt einen Chunk zur Auswahl hinzu oder nimmt ihn heraus. Gibt eine Erklärung zurück, wenn es nicht geht. */
export function paintChunk(c, add) {
  const key = c.x + ',' + c.z;
  if (!add) { map.selection.delete(key); selectionChanged(); return null; }
  const st = chunkStatus(c);
  const me = store.me || {};
  if (st.land && !(st.land.mine || me.admin)) return st.text;
  if (!st.land && !st.free && me.claimOnlyDiscovered) return 'Dieser Chunk ist noch nicht entdeckt.';
  if (map.selection.size >= 512) return 'Höchstens 512 Chunks auf einmal.';
  map.selection.add(key);
  selectionChanged();
  return null;
}

export function clearSelection() {
  map.selection.clear();
  selectionChanged();
}

function selectionChanged() {
  requestDraw();
  if (map.onSelection) map.onSelection();
}

export function selectionInfo() {
  const flat = [];
  let free = 0;
  const lands = new Set();
  for (const key of map.selection) {
    const [x, z] = key.split(',').map(Number);
    flat.push(x, z);
    const land = map.chunkOwner.get(key);
    if (land) lands.add(land); else free++;
  }
  return { flat, free, lands, total: map.selection.size };
}

// ------------------------------------------------------------------ Maus, Finger, Tastatur

function setupPointer() {
  const pointers = new Map();
  let drag = null;
  canvas.addEventListener('pointerdown', e => {
    canvas.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      drag = { mode: 'pinch', dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, moved: true };
      return;
    }
    if (map.selecting && e.button === 0) {
      const c = chunkAt(e.offsetX, e.offsetY);
      const key = c.x + ',' + c.z;
      drag = { mode: 'paint', add: !map.selection.has(key), last: key, moved: false };
      const problem = paintChunk(c, drag.add);
      if (problem && map.onClick) map.onClick(c, null, problem);
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
      map.view.x -= (e.offsetX - drag.lastX) / map.view.zoom;
      map.view.z -= (e.offsetY - drag.lastY) / map.view.zoom;
      drag.lastX = e.offsetX;
      drag.lastY = e.offsetY;
      if (Math.hypot(e.offsetX - drag.startX, e.offsetY - drag.startY) > 4) drag.moved = true;
      viewChanged();
    } else if (drag.mode === 'paint') {
      const c = chunkAt(e.offsetX, e.offsetY);
      const key = c.x + ',' + c.z;
      if (key !== drag.last) { drag.last = key; paintChunk(c, drag.add); }
    }
  });
  const end = e => {
    pointers.delete(e.pointerId);
    if (drag && drag.mode === 'pan' && !drag.moved && e.type === 'pointerup' && map.onClick) {
      const [bx, bz] = toBlock(e.offsetX, e.offsetY);
      map.onClick(chunkAt(e.offsetX, e.offsetY), { x: Math.floor(bx), z: Math.floor(bz) });
    }
    if (pointers.size === 0) drag = null;
    canvas.classList.remove('dragging');
  };
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);
  canvas.addEventListener('pointerleave', () => { map.hover = null; if (map.onHover) map.onHover(null); requestDraw(); });
  canvas.addEventListener('wheel', e => { e.preventDefault(); zoomAt(e.offsetX, e.offsetY, Math.exp(-e.deltaY * 0.0015)); }, { passive: false });
  // Tastatur: Pfeile verschieben, + / − zoomen
  canvas.addEventListener('keydown', e => {
    const step = 120 / map.view.zoom;
    const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
    if (moves[e.key]) { e.preventDefault(); centerOn(map.view.x + moves[e.key][0], map.view.z + moves[e.key][1]); }
    else if (e.key === '+' || e.key === '=') { e.preventDefault(); zoomBy(1.4); }
    else if (e.key === '-') { e.preventDefault(); zoomBy(1 / 1.4); }
  });
}

function updateHover(sx, sy) {
  const c = chunkAt(sx, sy);
  const [bx, bz] = toBlock(sx, sy);
  if (!map.hover || map.hover.x !== c.x || map.hover.z !== c.z) { map.hover = c; requestDraw(); }
  if (map.onHover) map.onHover({ chunk: c, x: Math.floor(bx), z: Math.floor(bz), status: chunkStatus(c) });
}

/** Ein Maßstab mit „runder“ Länge (1, 2, 5 × 10ⁿ Blöcke), ungefähr 80–160 Pixel breit. */
export function scaleBar(targetPx = 110) {
  const blocks = targetPx / map.view.zoom;
  const pow = Math.pow(10, Math.floor(Math.log10(blocks)));
  const nice = [1, 2, 5, 10].map(f => f * pow).find(v => v >= blocks * 0.7) || pow * 10;
  return { blocks: nice, px: nice * map.view.zoom };
}
