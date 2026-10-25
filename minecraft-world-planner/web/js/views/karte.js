// Ansicht „Karte“: die 2D-Claim-Karte mit Werkzeugleiste, Infokarte, Auswahl und Vorschau vor dem Claimen.
import { api, enc } from '../api.js';
import {
  blockToChunk, blockToRegion, chunkBounds, convertForPortal, direction, distance2d, fmtCoords, fmtDistance, fmtDuration, measure,
  parseCoords, tpCommand,
} from '../coords.js';
import {
  DEFAULT_LAYERS, centerOn, chunkStatus, clearSelection, loadDiscovered, map, mapElement, paintChunk, requestDraw, scaleBar,
  selectionInfo, setDimension, setLayer, setMeasure, setSelecting, showLand, zoomBy,
} from '../map.js';
import { showIn3D } from '../bluemap.js';
import { navigate } from '../router.js';
import { load, store } from '../store.js';
import { act, confirmDialog, copyText, debounce, dimName, el, fill, formDialog, plural, toast } from '../util.js';
import { myPosition, openMarkerForm } from './markers.js';

const LAYER_NAMES = {
  discovered: 'Entdeckte Gebiete', grid: 'Chunk-Gitter', regions: 'Regionen (512 Blöcke)', lands: 'Länder', markers: 'Marker',
  projects: 'Projekte', players: 'Spieler', spawn: 'Spawn', labels: 'Beschriftungen',
};

let pane = null;
const ui = {};

/** Baut die Bedienelemente einmal; die Karte selbst bleibt beim Ansichtswechsel erhalten. */
function buildPane(host) {
  pane = host;
  ui.dim = el('select', { id: 'dimSelect', 'aria-label': 'Dimension', onchange: e => act(() => setDimension(e.target.value)).then(renderMeasure) });
  ui.goto = el('input', { id: 'gotoInput', placeholder: 'Gehe zu: X Z oder X Y Z', 'aria-label': 'Koordinaten', autocomplete: 'off' });
  const gotoForm = el('form', { class: 'goto', onsubmit: e => { e.preventDefault(); gotoCoords(ui.goto.value); } }, ui.goto,
    el('button', { type: 'submit', class: 'small' }, 'Los'));
  ui.selectBtn = el('button', { id: 'selectModeBtn', class: 'small', onclick: () => { setMeasuring(false); setSelecting(!map.selecting); } }, '✚ Auswählen');
  ui.measureBtn = el('button', { id: 'measureBtn', class: 'small', title: 'Entfernung und Richtung zwischen zwei Punkten', onclick: () => setMeasuring(!measuring) }, '📏 Messen');
  ui.layersBox = el('div', { class: 'layer-list' });
  const layers = el('details', { class: 'dropdown' }, el('summary', { class: 'button small' }, 'Ebenen'), ui.layersBox);
  const calc = el('details', { class: 'dropdown', id: 'calcBox' }, el('summary', { class: 'button small' }, 'Rechner'), calculator());
  const toolbar = el('div', { class: 'map-toolbar' },
    ui.dim, gotoForm,
    el('button', { class: 'small', id: 'centerMeBtn', onclick: centerOnMe }, '◎ Zu mir'),
    el('button', { class: 'small', onclick: () => centerOnSpawn(true) }, '★ Spawn'),
    el('button', { class: 'small', title: 'Ansicht zurücksetzen (Spawn, normaler Zoom, Standard-Ebenen)', onclick: resetView }, '↺'),
    el('button', { class: 'small', 'aria-label': 'Herauszoomen', onclick: () => zoomBy(1 / 1.5) }, '−'),
    el('button', { class: 'small', 'aria-label': 'Hineinzoomen', onclick: () => zoomBy(1.5) }, '+'),
    layers, calc, ui.measureBtn, ui.selectBtn);
  ui.hover = el('div', { id: 'hoverInfo', class: 'map-hover hidden' });
  ui.card = el('div', { id: 'chunkCard', class: 'map-card hidden', role: 'dialog', 'aria-label': 'Chunk-Info' });
  ui.selection = el('div', { id: 'selectionBar', class: 'map-selection hidden' });
  ui.measure = el('div', { id: 'measureBox', class: 'map-measure hidden', 'aria-live': 'polite' });
  ui.scale = el('div', { class: 'map-scale', 'aria-hidden': 'true' }, el('div', { class: 'bar' }), el('span'));
  ui.legend = el('details', { class: 'map-legend' }, el('summary', { text: 'Legende' }), legend());
  ui.note = el('div', { class: 'map-note', text: 'Grau = entdeckt, dunkel = noch nie besucht. Länder sind eine Anzeige – im Spiel wird nichts geschützt.' });
  fill(host, toolbar, el('div', { class: 'map-body' }, mapElement(), ui.hover, ui.card, ui.selection, ui.measure, ui.scale, ui.legend, ui.note));

  map.onClick = (chunk, block, problem) => {
    if (problem) { toast(problem, true); return; }
    if (measuring && block) { measureClick(block); return; }
    showChunkCard(chunk, block);
  };
  map.onHover = h => updateHover(h);
  map.onSelection = () => updateSelection();
  map.onViewChange = () => updateScale();
  updateLayers();
}

/** Koordinaten-Rechner: eine Eingabe, alle Umrechnungen. Rechnet nur im Browser (coords.js). */
function calculator() {
  const input = el('input', { id: 'calcInput', placeholder: 'z. B. 100 64 -200', 'aria-label': 'Koordinaten für den Rechner', autocomplete: 'off' });
  const out = el('div', { id: 'calcOut', class: 'calc-out' });
  const render = () => {
    const c = parseCoords(input.value);
    if (!input.value.trim()) { fill(out, el('p', { class: 'muted small', text: 'Koordinaten eingeben (X Z oder X Y Z). Auch F3-Text oder /tp-Befehle gehen.' })); return; }
    if (!c) { fill(out, el('p', { class: 'warn small', text: 'Das sind keine gültigen Koordinaten.' })); return; }
    const cx = blockToChunk(c.x), cz = blockToChunk(c.z);
    const b = chunkBounds(cx, cz);
    const portal = convertForPortal(map.dim, c.x, c.z);
    const me = myPosition();
    const rows = [
      ['Dimension', dimName(map.dim)],
      ['Chunk', `${cx} / ${cz} (Blöcke X ${b.minX}…${b.maxX}, Z ${b.minZ}…${b.maxZ})`],
      ['Region', `r.${blockToRegion(c.x)}.${blockToRegion(c.z)}.mca`],
      portal ? [dimName(portal.dim), `X ${portal.x} · Z ${portal.z}`] : null,
      me && me.dimension === map.dim ? ['Von dir', `${fmtDistance(distance2d(me, c))} Richtung ${direction(me, c)}`] : null,
    ].filter(Boolean);
    fill(out,
      el('dl', { class: 'facts small' }, rows.map(([k, v]) => [el('dt', { text: k }), el('dd', { text: v })])),
      el('div', { class: 'row' },
        el('button', { class: 'small', type: 'button', onclick: () => centerOn(c.x + 0.5, c.z + 0.5, Math.max(map.view.zoom, 1)) }, 'Auf Karte'),
        el('button', { class: 'small', type: 'button', onclick: () => copyText(tpCommand(map.dim, c.x, c.y ?? 64, c.z)) }, 'TP-Befehl'),
        el('button', { class: 'small', type: 'button', onclick: () => openMarkerForm(null, { x: c.x, y: c.y, z: c.z, dimension: map.dim }) }, 'Marker')));
  };
  input.addEventListener('input', render);
  render();
  return el('div', { class: 'layer-list calc' }, el('strong', { text: 'Koordinaten-Rechner' }), input, out);
}

function legend() {
  const row = (cls, text) => el('div', { class: 'legend-row' }, el('span', { class: 'legend-icon ' + cls }), el('span', { text }));
  return el('div', { class: 'legend' },
    row('found', 'entdeckt (hier war schon jemand)'), row('fog', 'unentdeckt'),
    row('land-mine', 'dein Land (dicker Rand)'), row('land', 'Land anderer'), row('land-team', 'Team-Land (gestrichelt)'),
    row('marker', 'Marker'), row('marker-private', 'Marker nur für dich/Team/Land'), row('project', 'Projekt'),
    row('player', 'Spieler'), row('spawn', 'Spawn'), row('selection', 'deine Auswahl'));
}

function updateLayers() {
  fill(ui.layersBox, Object.keys(DEFAULT_LAYERS).map(k => {
    const cb = el('input', { type: 'checkbox', 'data-layer': k });
    cb.checked = !!map.layers[k];
    cb.addEventListener('change', () => setLayer(k, cb.checked));
    return el('label', { class: 'check' }, cb, el('span', { text: LAYER_NAMES[k] }));
  }));
}

function updateDims() {
  const dims = (store.world && store.world.dimensions && store.world.dimensions.length) ? store.world.dimensions : ['minecraft:overworld'];
  if (!dims.includes(map.dim)) setDimension(dims[0]); // gemerkte Dimension gibt es auf diesem Server nicht
  fill(ui.dim, dims.map(d => el('option', { value: d, text: dimName(d) })));
  ui.dim.value = map.dim;
}

function updateScale() {
  const s = scaleBar();
  ui.scale.firstChild.style.width = Math.round(s.px) + 'px';
  ui.scale.lastChild.textContent = s.blocks >= 1000 ? `${s.blocks / 1000} km` : `${s.blocks} Blöcke`;
}

function updateHover(h) {
  if (!h) { ui.hover.classList.add('hidden'); return; }
  const st = h.status.land ? '»' + h.status.land.name + '«' : h.status.free ? 'frei' : 'unentdeckt';
  ui.hover.textContent = `X ${h.x} · Z ${h.z} · Chunk ${h.chunk.x}/${h.chunk.z} · ${st}`;
  ui.hover.classList.remove('hidden');
}

export function gotoCoords(text) {
  const c = parseCoords(text);
  if (!c) { toast('Das sind keine Koordinaten. Beispiele: „100 -200“ oder „100 64 -200“.', true); return false; }
  centerOn(c.x + 0.5, c.z + 0.5, Math.max(map.view.zoom, 1));
  toast('Karte auf ' + fmtCoords(c));
  return true;
}

function centerOnMe() {
  const p = myPosition();
  if (!p) { toast(store.playersHidden ? 'Der Server zeigt keine Spielerpositionen.' : 'Du bist gerade nicht auf dem Server.', true); return; }
  const go = () => centerOn(p.x, p.z, Math.max(map.view.zoom, 0.8));
  if (p.dimension !== map.dim) setDimension(p.dimension).then(go); else go();
}

export async function centerOnSpawn(announce = false) {
  const sp = store.world && store.world.spawn;
  if (!sp) { if (announce) toast('Der Spawn ist nicht bekannt.', true); return; }
  if (sp.dimension !== map.dim) await setDimension(sp.dimension);
  centerOn(sp.x, sp.z, 0.8);
}

async function resetView() {
  for (const [k, v] of Object.entries(DEFAULT_LAYERS)) map.layers[k] = v;
  setLayer('discovered', true);
  updateLayers();
  clearSelection();
  setSelecting(false);
  await centerOnSpawn(true);
}

// ------------------------------------------------------------------ Messen

let measuring = false;

function setMeasuring(on) {
  measuring = on;
  if (on) { setSelecting(false); hideChunkCard(); } else setMeasure(null);
  if (!ui.measureBtn) return;
  ui.measureBtn.classList.toggle('active', on);
  ui.measureBtn.textContent = on ? '✓ Messen beenden' : '📏 Messen';
  renderMeasure();
}

/** Erster Klick = Punkt A, zweiter = Punkt B, dritter = neue Messung ab dort. */
function measureClick(block) {
  const m = map.measure;
  if (!m || m.b || m.dim !== map.dim) setMeasure({ dim: map.dim, a: block, b: null });
  else setMeasure({ ...m, b: block });
  renderMeasure();
}

const signed = n => (n > 0 ? '+' : '') + n;

function renderMeasure() {
  const box = ui.measure;
  if (!box) return;
  box.classList.toggle('hidden', !measuring);
  if (!measuring) return;
  const m = map.measure && map.measure.dim === map.dim ? map.measure : null;
  const close = el('button', { class: 'icon', 'aria-label': 'Messen beenden', onclick: () => setMeasuring(false) }, '×');
  if (!m || !m.b) {
    fill(box, el('div', { class: 'item-top' }, el('strong', { text: '📏 Messen' }), close),
      el('small', { id: 'measureHint', text: m ? `A: X ${m.a.x} · Z ${m.a.z} – jetzt auf das Ziel B klicken.` : 'Klicke auf den Startpunkt A.' }));
    return;
  }
  const r = measure(m.a, m.b, map.dim);
  const summary = `${fmtDistance(r.distance)} Richtung ${r.direction} (von X ${m.a.x} Z ${m.a.z} nach X ${m.b.x} Z ${m.b.z})`;
  fill(box,
    el('div', { class: 'item-top' }, el('strong', { id: 'measureResult', text: `📏 ${fmtDistance(r.distance)} · ${r.direction}` }), close),
    el('small', { class: 'muted', text: `A: X ${m.a.x} · Z ${m.a.z} → B: X ${m.b.x} · Z ${m.b.z} (X ${signed(r.dx)} · Z ${signed(r.dz)})` }),
    r.netherDistance != null ? el('small', { id: 'measureNether', text: `Durch den Nether: ${fmtDistance(r.netherDistance)} (÷ 8)` }) : null,
    r.overworldDistance != null ? el('small', { id: 'measureNether', text: `In der Oberwelt wären das ${fmtDistance(r.overworldDistance)} (× 8)` }) : null,
    el('small', { class: 'muted', text: `Luftlinie ohne Höhe · zu Fuß ≈ ${fmtDuration(r.walkSeconds)}, sprintend ≈ ${fmtDuration(r.sprintSeconds)}` }),
    el('div', { class: 'row' },
      el('button', { class: 'small', onclick: () => { setMeasure(null); renderMeasure(); } }, 'Neu messen'),
      el('button', { class: 'small', onclick: () => copyText(summary) }, 'Kopieren')));
}

// ------------------------------------------------------------------ Infokarte zu einem Chunk

function showChunkCard(c, block) {
  const st = chunkStatus(c);
  const b = chunkBounds(c.x, c.z);
  const px = block ? block.x : b.minX + 8, pz = block ? block.z : b.minZ + 8;
  const portal = convertForPortal(map.dim, px, pz);
  const canClaim = st.free || (!st.land && store.me && !store.me.claimOnlyDiscovered);
  const team = st.land && st.land.teamId ? store.teams.find(t => t.id === st.land.teamId) : null;
  fill(ui.card,
    el('div', { class: 'item-top' }, el('strong', { text: `Chunk ${c.x} / ${c.z}` }),
      el('button', { class: 'icon', 'aria-label': 'Schließen', onclick: hideChunkCard }, '×')),
    el('small', { class: 'muted', text: `Blöcke X ${b.minX}…${b.maxX} · Z ${b.minZ}…${b.maxZ} · Region r.${blockToRegion(px)}.${blockToRegion(pz)}` }),
    block ? el('small', { class: 'muted', text: `Angeklickt: X ${px} · Z ${pz}` + (portal ? ` · ${dimName(portal.dim)}: X ${portal.x} Z ${portal.z}` : '') }) : null,
    el('div', { class: 'card-status ' + (st.free ? 'ok' : st.land ? 'taken' : 'fog'), text: st.text }),
    team ? el('small', { text: `Team: [${team.tag}] ${team.name}` }) : null,
    st.land && st.land.description ? el('small', { class: 'muted', text: st.land.description }) : null,
    el('div', { class: 'row' },
      canClaim ? el('button', { class: 'primary small', onclick: () => { setSelecting(true); paintChunk(c, true); hideChunkCard(); } }, 'Claimen') : null,
      st.land ? el('button', { class: 'small', onclick: () => navigate('#/claims/' + enc(st.land.id)) }, 'Land öffnen') : null,
      el('button', { class: 'small', onclick: () => { hideChunkCard(); openMarkerForm(null, { x: px, z: pz, dimension: map.dim }); } }, 'Marker hier'),
      el('button', { class: 'small', onclick: () => showIn3D(b.minX + 8, b.minZ + 8, navigate) }, 'In 3D zeigen'),
      el('button', { class: 'small', onclick: () => copyText(`${px} ~ ${pz}`) }, 'Koordinaten')));
  ui.card.classList.remove('hidden');
}

export function hideChunkCard() {
  if (ui.card) ui.card.classList.add('hidden');
}

// ------------------------------------------------------------------ Auswahl und Vorschau

let checkSeq = 0;
const runCheck = debounce(async () => {
  const info = selectionInfo();
  const seq = ++checkSeq;
  if (!info.total || info.free !== info.total) { ui.check = null; renderSelection(); return; }
  try {
    // Geprüft wird wie für ein neues Land; ob es an ein bestehendes angrenzt, sagt der Server beim Hinzufügen.
    const r = await api('api/lands/check', { dimension: map.dim, chunks: info.flat });
    if (seq === checkSeq) { ui.check = r; renderSelection(); }
  } catch (e) {
    if (seq === checkSeq) { ui.check = { error: e.message }; renderSelection(); }
  }
}, 250);

function selectedTargetLand() {
  const sel = ui.selection && ui.selection.querySelector('#addToLandSelect');
  const id = sel && sel.value;
  return id ? store.lands.find(l => l.id === id) : null;
}

function updateSelection() {
  ui.selectBtn.classList.toggle('active', map.selecting);
  ui.selectBtn.textContent = map.selecting ? '✓ Auswahl fertig' : '✚ Auswählen';
  ui.check = null;
  renderSelection();
  runCheck();
}

function renderSelection() {
  const info = selectionInfo();
  const bar = ui.selection;
  bar.classList.toggle('hidden', info.total === 0 && !map.selecting);
  if (bar.classList.contains('hidden')) return;
  const allFree = info.total > 0 && info.free === info.total;
  const myLandsHere = store.lands.filter(l => l.mine && l.dimension === map.dim);
  const oldTarget = bar.querySelector('#addToLandSelect');
  const select = el('select', { id: 'addToLandSelect', 'aria-label': 'Land zum Erweitern', disabled: !myLandsHere.length },
    myLandsHere.map(l => el('option', { value: l.id, text: l.name })));
  if (oldTarget && myLandsHere.some(l => l.id === oldTarget.value)) select.value = oldTarget.value;
  const single = info.lands.size === 1 && info.free === 0 ? [...info.lands][0] : null;
  const c = ui.check;
  let checkLine = null;
  if (allFree && c && !c.error) {
    const problems = [];
    if (c.conflicts.length) problems.push(`${c.conflicts.length} Chunk(s) gehören schon anderen`);
    if (c.undiscovered.length) problems.push(`${c.undiscovered.length} noch unentdeckt`);
    if (!c.connected) problems.push('nicht zusammenhängend');
    if (c.overLimit) problems.push(`zu viele: du hättest ${c.chunksAfter} von ${c.maxChunks}`);
    checkLine = problems.length
      ? el('div', { id: 'checkLine', class: 'check-line warn', text: '⚠ ' + problems.join(' · ') })
      : el('div', { id: 'checkLine', class: 'check-line ok', text: `✓ Alles frei und zusammenhängend · danach ${c.chunksAfter} von ${c.maxChunks} Chunks` });
  } else if (c && c.error) {
    checkLine = el('div', { id: 'checkLine', class: 'check-line warn', text: c.error });
  } else if (info.total && !allFree) {
    checkLine = el('div', { id: 'checkLine', class: 'check-line', text: single ? `Ausgewählte Chunks gehören zu »${single.name}«.` : 'Auswahl enthält schon geclaimte Chunks.' });
  }
  fill(bar,
    el('div', { class: 'row' },
      el('strong', { id: 'selectionCount', text: info.total ? plural(info.total, 'Chunk', 'Chunks') : 'Klicke oder ziehe über Chunks' }),
      el('button', { id: 'newLandBtn', class: 'primary small', disabled: !allFree, onclick: createLand }, 'Neues Land'),
      select,
      el('button', { id: 'addToLandBtn', class: 'small', disabled: !allFree || !myLandsHere.length, onclick: addToLand }, 'Hinzufügen'),
      el('button', { id: 'removeFromLandBtn', class: 'small', disabled: !(single && (single.mine || (store.me && store.me.admin))), onclick: () => removeFromLand(single) }, 'Entfernen'),
      el('button', { class: 'small', onclick: clearSelection }, 'Leeren'),
      el('button', { class: 'small', onclick: () => { clearSelection(); setSelecting(false); } }, 'Fertig')),
    checkLine);
}

async function createLand() {
  const info = selectionInfo();
  const v = await formDialog({
    title: 'Neues Land',
    intro: `${plural(info.total, 'Chunk', 'Chunks')} in ${dimName(map.dim)}. Ein Land ist eine Anzeige für alle – es schützt im Spiel nichts.`,
    fields: [
      { name: 'name', label: 'Name (2–24 Zeichen)', required: true, maxLength: 24, autofocus: true, placeholder: 'z. B. Nordhafen' },
      { name: 'color', label: 'Farbe', type: 'color', value: '#4caf50' },
      { name: 'description', label: 'Beschreibung (für alle sichtbar)', type: 'textarea', maxLength: 200 },
    ],
    validate: x => (x.name.length < 2 ? 'Der Name braucht mindestens 2 Zeichen.' : null),
    submitLabel: 'Land erstellen',
  });
  if (!v) return;
  const r = await act(() => api('api/lands', { name: v.name, dimension: map.dim, chunks: info.flat, color: v.color, description: v.description }),
    `Land »${v.name}« erstellt!`);
  if (r) { clearSelection(); setSelecting(false); await Promise.all([load.lands(), load.me()]); }
}

async function addToLand() {
  const land = selectedTargetLand();
  if (!land) return;
  const info = selectionInfo();
  const r = await act(() => api(`api/lands/${enc(land.id)}/chunks`, { add: info.flat }), `${plural(info.total, 'Chunk', 'Chunks')} zu »${land.name}« hinzugefügt.`);
  if (r) { clearSelection(); await Promise.all([load.lands(), load.me()]); }
}

async function removeFromLand(land) {
  const info = selectionInfo();
  if (!land || !await confirmDialog({ title: 'Chunks entfernen?', text: `${plural(info.total, 'Chunk', 'Chunks')} aus »${land.name}« entfernen? Sind es alle, wird das Land aufgelöst.`, confirmLabel: 'Entfernen', danger: true })) return;
  const r = await act(() => api(`api/lands/${enc(land.id)}/chunks`, { remove: info.flat }),
    res => res.deleted ? `»${land.name}« hatte keine Chunks mehr und wurde aufgelöst.` : 'Chunks entfernt.');
  if (r) { clearSelection(); await Promise.all([load.lands(), load.me()]); }
}

// ------------------------------------------------------------------ Ansicht

export default {
  title: 'Karte',
  stage: 'claim',
  async render(host, route) {
    if (!pane) buildPane(host);
    updateDims();
    updateScale();
    requestDraw();
    const q = route.query;
    if (q.land) {
      const land = store.lands.find(l => l.id === q.land);
      if (land) await showLand(land);
    } else if (q.x != null && q.z != null) {
      const x = Number(q.x), z = Number(q.z);
      if (Number.isFinite(x) && Number.isFinite(z)) {
        if (q.dim && q.dim !== map.dim) await setDimension(q.dim);
        centerOn(x, z, Number(q.zoom) || Math.max(map.view.zoom, 1));
      }
    }
    loadDiscovered().catch(() => { /* nächster Versuch beim Polling */ });
    renderMeasure();
    return () => { hideChunkCard(); setMeasuring(false); };
  },
};
