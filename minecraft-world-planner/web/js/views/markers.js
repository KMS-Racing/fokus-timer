// Marker: Orte und Bau-Marker mit Sichtbarkeit (nur ich / Team / Land-Besitzer / alle).
import { api, enc } from '../api.js';
import { convertForPortal, direction, distance2d, fmtDistance, tpCommand } from '../coords.js';
import { showPoint } from '../map.js';
import { showIn3D } from '../bluemap.js';
import { navigate } from '../router.js';
import { load, myTeams, options, store, label } from '../store.js';
import { act, confirmDialog, copyText, dimName, el, fill, formDialog, pill, toast } from '../util.js';
import { bind, favButton, pageHead, searchInput, segmented, selectInput, teamBadgeById } from './common.js';

const state = { filter: 'all', type: '', dim: '', q: '', sort: 'name' };

export function myPosition() {
  return store.me && store.players.find(p => p.uuid === store.me.uuid);
}

/** Setzt im Spiel den Wegweiser (Pfeil über der Hotbar) – nur sichtbar, wenn du gerade online bist. */
export function navigateButton(path) {
  return el('button', { class: 'small', title: 'Zeigt im Spiel einen Pfeil zum Ziel über der Hotbar (kein Teleport)',
    onclick: () => act(() => api(path, {}), 'Wegweiser gestartet – schau ins Spiel, der Pfeil steht über der Hotbar.') }, '🧭 Im Spiel hinführen');
}

/** Formular zum Anlegen/Bearbeiten. prefill: {x, y, z, dimension} von der Karte. */
export async function openMarkerForm(marker = null, prefill = {}) {
  const src = marker || prefill;
  const dims = (store.world && store.world.dimensions) || ['minecraft:overworld'];
  const teams = myTeams();
  const myLands = store.lands.filter(l => l.mine);
  const values = await formDialog({
    title: marker ? 'Marker bearbeiten' : 'Neuer Marker',
    intro: 'Marker sind Notizen auf der Karte. Sie bauen oder schützen nichts im Spiel.',
    fields: [
      { name: 'name', label: 'Name', value: src.name || '', required: true, maxLength: 48, autofocus: true },
      { name: 'type', label: 'Art', type: 'select', value: src.type || 'other', options: options('markerTypes') },
      { name: 'dimension', label: 'Dimension', type: 'select', value: src.dimension || dims[0], options: dims.map(d => [d, dimName(d)]) },
      { name: 'x', label: 'X', type: 'number', value: src.x ?? '', required: true, step: 1 },
      { name: 'y', label: 'Y (Höhe, optional)', type: 'number', value: src.y ?? '', step: 1, help: 'Leer lassen, wenn egal.' },
      { name: 'z', label: 'Z', type: 'number', value: src.z ?? '', required: true, step: 1 },
      { name: 'visibility', label: 'Wer sieht ihn?', type: 'select', value: src.visibility || 'public', options: options('markerVisibility') },
      { name: 'teamId', label: 'Team', type: 'select', value: src.teamId || (teams[0] && teams[0].id) || '',
        options: teams.length ? teams.map(t => [t.id, `[${t.tag}] ${t.name}`]) : [['', 'Du bist in keinem Team']], showIf: v => v.visibility === 'team' },
      { name: 'landId', label: 'Land (Verknüpfung)', type: 'select', value: src.landId || '',
        options: [['', '— keins —'], ...myLands.map(l => [l.id, l.name])], help: 'Bei „Besitzer des Landes“ sehen ihn nur die Besitzer.' },
      { name: 'symbol', label: 'Symbol', type: 'select', value: src.symbol || '', options: [['', '—'], ...((store.meta && store.meta.markerSymbols) || []).map(s => [s, s])] },
      { name: 'color', label: 'Farbe', type: 'color', value: src.color || '#ffd88a' },
      { name: 'useColor', label: 'Eigene Farbe benutzen', type: 'checkbox', value: !!src.color },
      { name: 'notes', label: 'Notizen', type: 'textarea', value: src.notes || '', maxLength: 500 },
    ],
    tools: [{
      label: '📍 Meine Position',
      onClick: inputs => {
        const p = myPosition();
        if (!p) { toast('Du bist gerade nicht auf dem Server.', true); return; }
        inputs.x.value = Math.floor(p.x); inputs.y.value = Math.floor(p.y); inputs.z.value = Math.floor(p.z);
        inputs.dimension.value = p.dimension;
      },
    }],
    hint: v => {
      if (!Number.isFinite(v.x) || !Number.isFinite(v.z)) return '';
      const c = convertForPortal(v.dimension, v.x, v.z);
      return c ? `↔ ${dimName(c.dim)}: X ${c.x} · Z ${c.z} (für Portale)` : '';
    },
    validate: v => {
      if (!v.name) return 'Bitte einen Namen eingeben.';
      if (!Number.isFinite(v.x) || !Number.isFinite(v.z)) return 'Bitte X und Z eingeben.';
      if (v.visibility === 'team' && !v.teamId) return 'Für „Mein Team“ musst du in einem Team sein.';
      if (v.visibility === 'land' && !v.landId) return 'Für „Besitzer des Landes“ bitte ein Land auswählen.';
      return null;
    },
    submitLabel: marker ? 'Speichern' : 'Marker anlegen',
  });
  if (!values) return null;
  const body = {
    name: values.name, type: values.type, dimension: values.dimension, x: values.x, z: values.z,
    y: values.y == null ? null : values.y, notes: values.notes, visibility: values.visibility,
    teamId: values.visibility === 'team' ? values.teamId : '', landId: values.landId,
    color: values.useColor ? values.color : '', symbol: values.symbol,
  };
  const r = await act(() => marker ? api(`api/markers/${enc(marker.id)}/edit`, body) : api('api/markers', body),
    marker ? 'Marker gespeichert.' : `Marker »${values.name}« angelegt.`);
  if (r) await load.markers();
  return r;
}

export async function deleteMarker(m) {
  if (!await confirmDialog({ title: 'Marker löschen?', text: `»${m.name}« wird gelöscht.`, confirmLabel: 'Löschen', danger: true })) return;
  if (await act(() => api(`api/markers/${enc(m.id)}/delete`, {}), 'Marker gelöscht.')) await load.markers();
}

function visibilityPill(m) {
  const kinds = { public: 'ok', private: 'muted', team: 'team', land: 'info' };
  return pill(label('markerVisibility', m.visibility), kinds[m.visibility] || '');
}

export function markerItem(m, compact = false) {
  const me = myPosition();
  const dist = me && me.dimension === m.dimension
    ? `${fmtDistance(distance2d(me, m))} ${direction(me, m)}` : null;
  const diamond = el('span', { class: 'diamond', 'aria-hidden': 'true' });
  if (m.color) diamond.style.background = m.color;
  return el('article', { class: 'item' },
    el('div', { class: 'item-top' },
      el('div', { class: 'row tight' }, diamond, m.symbol ? el('span', { text: m.symbol }) : null, el('strong', { text: m.name })),
      el('div', { class: 'row tight' }, favButton('markers', m.id), visibilityPill(m))),
    el('div', { class: 'meta' },
      el('span', { text: label('markerTypes', m.type) }),
      el('span', { text: `${dimName(m.dimension)} · X ${m.x}${m.y != null ? ' · Y ' + m.y : ''} · Z ${m.z}` }),
      dist ? el('span', { text: '📍 ' + dist }) : null,
      el('span', { text: 'von ' + m.ownerName }),
      m.teamId ? teamBadgeById(m.teamId) : null),
    !compact && m.notes ? el('p', { class: 'notes', text: m.notes }) : null,
    el('div', { class: 'row' },
      el('button', { class: 'small', onclick: () => { navigate('#/karte'); showPoint(m.dimension, m.x + 0.5, m.z + 0.5, 1.5); } }, 'Auf Karte'),
      el('button', { class: 'small', onclick: () => { showPoint(m.dimension, m.x + 0.5, m.z + 0.5).then(() => showIn3D(m.x + 0.5, m.z + 0.5, navigate)); } }, 'In 3D'),
      el('button', { class: 'small', onclick: () => copyText(tpCommand(m.dimension, m.x, m.y ?? 64, m.z)) }, 'TP-Befehl'),
      el('button', { class: 'small', onclick: () => copyText(`${m.x} ${m.y ?? '~'} ${m.z}`) }, 'Koordinaten'),
      myPosition() ? navigateButton(`api/markers/${enc(m.id)}/navigate`) : null,
      m.canEdit ? el('button', { class: 'small', onclick: () => openMarkerForm(m) }, 'Bearbeiten') : null,
      m.canEdit ? el('button', { class: 'small danger-text', onclick: () => deleteMarker(m) }, 'Löschen') : null));
}

function filtered() {
  const q = state.q.trim().toLowerCase();
  let items = store.markers.filter(m => {
    if (state.filter === 'mine' && !m.mine) return false;
    if (state.filter === 'public' && m.visibility !== 'public') return false;
    if (state.filter === 'team' && m.visibility !== 'team') return false;
    if (state.filter === 'private' && m.visibility !== 'private') return false;
    if (state.type && m.type !== state.type) return false;
    if (state.dim && m.dimension !== state.dim) return false;
    if (q && !(m.name.toLowerCase().includes(q) || (m.notes || '').toLowerCase().includes(q) || m.ownerName.toLowerCase().includes(q))) return false;
    return true;
  });
  const me = myPosition();
  if (state.sort === 'new') items.sort((a, b) => b.createdAt - a.createdAt);
  else if (state.sort === 'near' && me) items.sort((a, b) => distanceSort(me, a) - distanceSort(me, b));
  else items.sort((a, b) => a.name.localeCompare(b.name, 'de'));
  return items;
}

function distanceSort(me, m) {
  return m.dimension === me.dimension ? distance2d(me, m) : Infinity;
}

export default {
  title: 'Marker',
  render(root) {
    const listBox = el('div');
    const controls = el('div', { class: 'toolbar' });
    const draw = () => {
      const dims = (store.world && store.world.dimensions) || [];
      fill(controls,
        segmented([['all', 'Alle'], ['mine', 'Meine'], ['public', 'Öffentlich'], ['team', 'Team'], ['private', 'Privat']], state.filter, v => { state.filter = v; draw(); }),
        selectInput([['', 'Alle Arten'], ...options('markerTypes')], state.type, v => { state.type = v; draw(); }, 'Art'),
        selectInput([['', 'Alle Dimensionen'], ...dims.map(d => [d, dimName(d)])], state.dim, v => { state.dim = v; draw(); }, 'Dimension'),
        selectInput([['name', 'Name A–Z'], ['new', 'Neueste'], ['near', 'In meiner Nähe']], state.sort, v => { state.sort = v; draw(); }, 'Sortierung'),
        searchInput(state.q, v => { state.q = v; drawList(); }, 'Marker suchen …'));
      drawList();
    };
    const drawList = () => {
      const items = filtered();
      fill(listBox, items.length
        ? el('div', { class: 'list' }, items.map(m => markerItem(m)))
        : el('div', { class: 'empty' }, el('p', { text: store.markers.length ? 'Kein Marker passt zu den Filtern.' : 'Noch keine Marker. Lege den ersten an – zum Beispiel deine Basis oder ein Portal.' })));
    };
    fill(root,
      pageHead('Marker', 'Orte, Portale, Farmen und Treffpunkte. Private Marker sieht nur du.',
        el('button', { class: 'primary', onclick: () => openMarkerForm() }, '+ Neuer Marker')),
      controls, listBox);
    return bind(root, ['markers', 'players', 'teams', 'meta'], draw);
  },
};
