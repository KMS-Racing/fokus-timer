// Rennen: Strecken mit Bestenliste (Rundenzeiten, Abschnitte in F1-Farben), Streckenplan und Verlauf.
// Strecken baut man im Spiel (/rennen neu, /rennen tor) – dort steht man an der Linie und schaut in Fahrtrichtung.
import { api, enc } from '../api.js';
import { showPoint } from '../map.js';
import { fmtDelta, fmtLap, trackPlan } from '../racefmt.js';
import { navigate } from '../router.js';
import { store } from '../store.js';
import { act, confirmDialog, copyText, dimName, el, fill, fmtAgo, fmtDateTime, fmtNum, formDialog, pill } from '../util.js';
import { bind, pageHead } from './common.js';
import { myPosition, navigateButton } from './markers.js';

const NS = 'http://www.w3.org/2000/svg';
const MODE_NAMES = { foot: 'zu Fuß', boat: 'Boot', horse: 'Pferd', elytra: 'Elytra', other: 'Reittier', fly: 'fliegend', creative: 'Kreativ' };

const typeName = t => (store.meta && store.meta.raceTypes && store.meta.raceTypes[t]) || t;
const vehicleName = v => (store.meta && store.meta.raceVehicles && store.meta.raceVehicles[v]) || v;
const startCommand = t => `/rennen start ${/^[A-Za-z0-9_.+-]+$/.test(t.name) ? t.name : `"${t.name.replace(/"/g, '')}"`}`;

/** Name eines Fahrers – als Link zur Statistik, außer er hat sich aus Ranglisten ausgeblendet (dann ohne Namen). */
function driver(l) {
  return l.hidden ? el('span', { class: 'muted', text: 'ausgeblendet' }) : el('a', { href: '#/statistik/spieler/' + enc(l.uuid), text: l.name });
}

function svg(tag, attrs = {}, ...children) {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v != null) node.setAttribute(k, String(v));
  for (const c of children) if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return node;
}

// ------------------------------------------------------------------ Liste

function trackCard(t) {
  const rec = t.record;
  return el('a', { class: 'item link-item race-card', href: '#/rennen/' + enc(t.id), 'data-track': t.name },
    el('div', { class: 'item-top' },
      el('strong', { text: '🏁 ' + t.name }),
      el('div', { class: 'row tight' }, pill(typeName(t.type), 'info'), pill(vehicleName(t.vehicle)), t.building ? pill('wird gebaut', 'warn') : null)),
    el('div', { class: 'meta' },
      el('span', { text: rec ? `Rekord ${fmtLap(rec.ms)}${rec.hidden ? '' : ` von ${rec.name}`}` : t.ready ? 'noch keine Zeit' : 'noch nicht fertig' }),
      t.myBestMs ? el('span', { text: `deine Bestzeit ${fmtLap(t.myBestMs)}` }) : null,
      el('span', { class: 'muted', text: `${t.gateCount} Tore · ${dimName(t.dimension)} · von ${t.ownerName}` })));
}

function listView(d) {
  const tracks = [...d.tracks].sort((a, b) => (b.laps - a.laps) || a.name.localeCompare(b.name));
  return [
    tracks.length ? el('div', { class: 'list', id: 'raceList' }, tracks.map(trackCard))
      : el('div', { class: 'empty' }, el('p', { text: 'Noch keine Strecken. Bau die erste im Spiel!' })),
    el('section', { class: 'card', id: 'raceHowTo' }, el('h2', { text: 'So baust du eine Strecke' }),
      el('ol', { class: 'steps' },
        el('li', {}, el('code', { text: '/rennen neu <Name> runde boot' }), ' – Rundkurs (oder ', el('code', { text: 'sprint' }), ' von A nach B), Fahrzeug: beliebig, zu_fuss, boot, pferd oder elytra'),
        el('li', {}, 'Auf die Start-/Ziellinie stellen, in Fahrtrichtung schauen: ', el('code', { text: '/rennen tor' }), ' (12 Blöcke breit, oder ', el('code', { text: '/rennen tor 20' }), ')'),
        el('li', {}, 'Die Strecke entlanggehen und an jeder Kurve ', el('code', { text: '/rennen tor' }), ' – mindestens 3 Tore beim Rundkurs, 2 beim Sprint'),
        el('li', {}, el('code', { text: '/rennen fertig' }), ' – dann fahren: ', el('code', { text: '/rennen start <Name>' }))),
      el('p', { class: 'muted small', text: 'Gemessen wird ab der Startlinie (fliegender Start). Eine Runde zählt nur, wenn du alle Tore vorwärts durchfährst – ohne Teleport, im erlaubten Fahrzeug, nicht im Kreativmodus. Minecraft kennt Positionen nur alle 0,05 Sekunden; die Hundertstel schätzt der Server dazwischen.' })),
  ];
}

// ------------------------------------------------------------------ Strecke

function planSvg(t) {
  const w = 320, h = 220;
  const plan = trackPlan(t.gates, t.type, w, h);
  const root = svg('svg', { viewBox: `0 0 ${w} ${h}`, width: w, height: h, class: 'race-plan', role: 'img', 'aria-label': `Streckenplan mit ${t.gates.length} Toren, Norden oben` });
  if (plan.path.length > 1) root.append(svg('polyline', { points: plan.path.map(p => p.join(',')).join(' '), class: 'race-line' }));
  plan.gates.forEach((g, i) => {
    const kind = i === 0 ? 'start' : t.type === 'sprint' && i === plan.gates.length - 1 ? 'finish' : 'gate';
    root.append(svg('line', { x1: g.a[0], y1: g.a[1], x2: g.b[0], y2: g.b[1], class: 'race-gate ' + kind }));
    const label = i === 0 ? (t.type === 'sprint' ? 'Start' : 'S/Z') : kind === 'finish' ? 'Ziel' : String(i);
    root.append(svg('text', { x: g.c[0] + 6, y: g.c[1] - 6, class: 'race-label' }, label));
    // kleiner Pfeil: Fahrtrichtung
    const [fx, fz] = g.forward, len = 9;
    root.append(svg('line', { x1: g.c[0], y1: g.c[1], x2: g.c[0] + fx * len, y2: g.c[1] + fz * len, class: 'race-dir' }));
  });
  root.append(svg('text', { x: w - 14, y: 14, class: 'race-label', 'text-anchor': 'middle' }, 'N'));
  return root;
}

function leaderboardTable(t) {
  const best = t.bestSectors || [];
  const leader = t.leaderboard.length ? t.leaderboard[0].ms : 0;
  const sectorCount = Math.max(0, ...t.leaderboard.map(l => (l.sectors || []).length));
  const me = store.me || {};
  const head = ['Platz', 'Fahrer', 'Zeit', 'Abstand', ...Array.from({ length: sectorCount }, (_, i) => `S${i + 1}`)];
  return el('div', { class: 'table-wrap' }, el('table', { class: 'data-table race-table', id: 'raceBoard' },
    el('thead', {}, el('tr', {}, head.map(x => el('th', { scope: 'col', text: x })))),
    el('tbody', {}, t.leaderboard.map(l => el('tr', { class: l.uuid === me.uuid ? 'mine' : null },
      el('td', { text: `${l.place}.` }),
      el('th', { scope: 'row' }, driver(l), l.uuid === me.uuid ? [' ', pill('du', 'ok')] : null),
      el('td', { class: l.place === 1 ? 'lap-best' : null, text: fmtLap(l.ms) }),
      el('td', { text: l.place === 1 ? '—' : fmtDelta(l.ms - leader) }),
      (l.sectors || []).map((s, i) => el('td', { class: best[i] === s ? 'sector-best' : null, title: best[i] === s ? 'schnellster Abschnitt von allen' : null, text: fmtLap(s) })))))));
}

function recentList(t) {
  if (!t.recent.length) return el('p', { class: 'muted small', text: 'Noch keine Runden.' });
  return el('ul', { class: 'mini-list' }, t.recent.slice(0, 20).map(l => el('li', { class: 'race-recent' },
    el('span', {}, el('strong', { text: fmtLap(l.ms) }), ' ', driver(l),
      el('span', { class: 'muted', text: ` · ${MODE_NAMES[l.mode] || l.mode}` }),
      !l.clean ? [' ', pill('ungenau', 'warn')] : null, !l.current ? [' ', pill('alte Version', 'muted')] : null),
    el('small', { class: 'muted', text: fmtAgo(l.at) }))));
}

async function rename(t, refresh) {
  const v = await formDialog({ title: 'Strecke umbenennen', fields: [{ name: 'name', label: 'Name', value: t.name, required: true, maxLength: 32 }] });
  if (v && await act(() => api(`api/races/${enc(t.id)}/rename`, { name: v.name }), 'Umbenannt.')) refresh();
}

async function changeVehicle(t, refresh) {
  const options = Object.entries((store.meta && store.meta.raceVehicles) || {});
  const v = await formDialog({
    title: 'Fahrzeug ändern', intro: 'Mit einem anderen Fahrzeug wären die Zeiten nicht vergleichbar: Die Strecke bekommt eine neue Version, die bisherigen Bestzeiten kommen ins Archiv.',
    fields: [{ name: 'vehicle', label: 'Fahrzeug', type: 'select', value: t.vehicle, options }], submitLabel: 'Ändern',
  });
  if (v && v.vehicle !== t.vehicle && await act(() => api(`api/races/${enc(t.id)}/vehicle`, { vehicle: v.vehicle }), 'Fahrzeug geändert – neue Version.')) refresh();
}

async function remove(t) {
  const typed = await confirmDialog({ title: 'Strecke löschen?', text: `»${t.name}« und alle ${fmtNum(t.laps)} Zeiten werden gelöscht. Das geht nicht rückgängig.`,
    confirmLabel: 'Löschen', danger: true, requireText: t.name });
  if (typed && await act(() => api(`api/races/${enc(t.id)}/delete`, { confirmName: typed }), 'Strecke gelöscht.')) navigate('#/rennen');
}

function trackView(t, refresh) {
  const start = t.gates[0];
  return [
    el('a', { class: 'back', href: '#/rennen', text: '← Alle Strecken' }),
    pageHead('🏁 ' + t.name, `${typeName(t.type)} · Fahrzeug: ${vehicleName(t.vehicle)} · ${t.gates.length} Tore · ${dimName(t.dimension)} · von ${t.ownerName}${t.version > 1 ? ` · Version ${t.version}` : ''}`,
      el('button', { class: 'small', onclick: () => copyText(startCommand(t)) }, 'Start-Befehl kopieren'),
      start && myPosition() ? navigateButton(`api/races/${enc(t.id)}/navigate`) : null,
      start ? el('button', { class: 'small', onclick: () => { navigate('#/karte'); showPoint(t.dimension, start.x, start.z, 2); } }, 'Auf der Karte') : null),
    t.building ? el('div', { class: 'card highlight' }, el('p', { text: 'An dieser Strecke wird gerade gebaut. Bis /rennen fertig gilt die bisherige Version.' })) : null,
    el('div', { class: 'grid two' },
      el('section', { class: 'card', id: 'raceBoardCard' }, el('h2', { text: 'Bestenliste' }),
        t.leaderboard.length ? leaderboardTable(t) : el('p', { class: 'muted', text: t.ready ? `Noch keine Zeit. Fahr die erste: ${startCommand(t)}` : 'Die Strecke ist noch nicht fertig gebaut.' }),
        el('p', { class: 'muted small', text: 'Je Fahrer die beste Runde. Lila = schnellster Abschnitt von allen. Zeiten mit ⚠ „ungenau“ (der Server hat geruckelt) zählen nicht. Wer sich aus den Ranglisten ausgeblendet hat, steht ohne Namen da.' }),
        t.archived ? el('p', { class: 'muted small', text: `${fmtNum(t.archived)} Bestzeit(en) einer früheren Version liegen im Archiv – die Strecke wurde seitdem geändert.` }) : null),
      el('section', { class: 'card', id: 'racePlanCard' }, el('h2', { text: 'Streckenplan' }),
        t.gates.length ? planSvg(t) : el('p', { class: 'muted', text: 'Noch keine Tore.' }),
        el('p', { class: 'muted small', text: 'Dicke Linie = Start' + (t.type === 'sprint' ? ', rot = Ziel' : '/Ziel') + ', gelb = Tore, kurze Striche = Fahrtrichtung, Norden oben. Im Spiel zeigt /rennen zeigen die Tore als Partikel.' }))),
    el('div', { class: 'grid two' },
      el('section', { class: 'card', id: 'raceRecent' }, el('h2', { text: 'Letzte Runden' }), recentList(t)),
      el('section', { class: 'card' }, el('h2', { text: 'Fahren' }),
        el('p', {}, 'Im Spiel: ', el('code', { text: startCommand(t) }), ' – die Uhr läuft, sobald du über die Startlinie fährst. ', el('code', { text: '/rennen stop' }), ' beendet die Zeitmessung.'),
        el('p', { class: 'muted small', text: 'Während der Fahrt: Rundenzeit und Abschnitte über der Hotbar, das nächste Tor leuchtet grün. Lila = schnellster Abschnitt von allen, grün = deine Bestzeit, gelb = langsamer.' }),
        t.canEdit ? el('div', { class: 'row' },
          el('button', { class: 'small', onclick: () => rename(t, refresh) }, 'Umbenennen'),
          el('button', { class: 'small', onclick: () => changeVehicle(t, refresh) }, 'Fahrzeug ändern'),
          el('button', { class: 'small danger-text', onclick: () => remove(t) }, 'Löschen')) : null,
        t.canEdit ? el('p', { class: 'muted small', text: 'Tore ändern geht im Spiel: /rennen bearbeiten <Name>.' }) : null)),
    el('p', { class: 'muted small', text: `Angelegt ${fmtDateTime(t.created)}. ${fmtNum(t.laps)} gezählte Runden von ${fmtNum(t.drivers)} Fahrer(n).` }),
  ];
}

// ------------------------------------------------------------------ Seite

export default {
  title: 'Rennen',
  render(root, route) {
    const id = route.path[1] || null;
    const head = el('div'), body = el('div', { class: 'race-page' });
    let data = null, error = null;
    const draw = () => {
      const off = store.meta && store.meta.gameFeatures && store.meta.gameFeatures.races === false;
      fill(head, id ? null : pageHead('Rennen', 'Rennstrecken mit Zeitmessung – Runde für Runde, mit Abschnitten wie in der Formel 1.'));
      if (off) { fill(body, el('div', { class: 'empty' }, el('p', { text: 'Rennen sind auf diesem Server ausgeschaltet.' }))); return; }
      if (error) { fill(body, el('div', { class: 'empty' }, el('p', { text: error }))); return; }
      if (!data) { fill(body, el('p', { class: 'muted', text: 'Lädt …' })); return; }
      fill(body, id ? trackView(data, refresh) : listView(data));
    };
    const refresh = async () => {
      try {
        data = await api(id ? `api/races/${enc(id)}` : 'api/races');
        error = null;
      } catch (e) {
        error = e.message;
      }
      if (body.isConnected) draw();
    };
    fill(root, head, body);
    const unbind = bind(root, ['meta', 'me', 'players'], draw);
    refresh();
    const timer = setInterval(() => { if (!document.hidden) refresh(); }, 15000);
    return () => { clearInterval(timer); unbind(); };
  },
};
