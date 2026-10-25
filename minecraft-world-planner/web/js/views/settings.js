// Einstellungen: Konto, Darstellung, Schalter fürs Spiel, Favoriten, Export/Import (mit Vorschau), Server-Infos.
import { api } from '../api.js';
import { load, store, landById, teamById, projectById, tradeById, toggleFavorite, setGameSwitch } from '../store.js';
import { act, confirmDialog, el, fill, fmtDateTime, fmtNum, storageGet, storageSet, toast } from '../util.js';
import { bind, pageHead, segmented } from './common.js';

const THEME_KEY = 'mcwp-theme';

export function applyTheme() {
  const t = storageGet(THEME_KEY) || 'system';
  if (t === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', t);
}

function download(name, data) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const a = el('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

async function exportData() {
  const data = await act(() => api('api/export'));
  if (!data) return;
  download(`world-planner-${store.me.name}-${new Date().toISOString().slice(0, 10)}.json`, data);
  toast('Export heruntergeladen.');
}

function summaryText(r) {
  const part = (name, s) => `${name}: ${s.create} neu` + (s.skipped.length ? `, ${s.skipped.length} übersprungen` : '');
  return [part('Länder', r.lands), part('Marker', r.markers), part('Projekte', r.projects)].join(' · ');
}

function skippedList(r) {
  const rows = [];
  for (const [area, s] of [['Land', r.lands], ['Marker', r.markers], ['Projekt', r.projects]]) {
    for (const x of s.skipped) rows.push(el('li', { text: `${area} »${x.name}«: ${x.reason}` }));
  }
  return rows.length ? el('ul', { class: 'skipped' }, rows) : null;
}

async function importFile(file, resultBox) {
  if (file.size > 4 * 1024 * 1024) { toast('Die Datei ist zu groß (max. 4 MB).', true); return; }
  let data;
  try { data = JSON.parse(await file.text()); } catch { toast('Das ist keine gültige JSON-Datei.', true); return; }
  const preview = await act(() => api('api/import', { data, apply: false }));
  if (!preview) return;
  fill(resultBox, el('h3', { text: 'Vorschau' }), el('p', { text: summaryText(preview) }), skippedList(preview));
  const total = preview.lands.create + preview.markers.create + preview.projects.create;
  if (!total) { resultBox.append(el('p', { class: 'muted', text: 'Es gibt nichts Neues zu importieren.' })); return; }
  if (!await confirmDialog({ title: 'Jetzt importieren?', text: `${summaryText(preview)}. Es wird nichts überschrieben oder gelöscht – nur neu angelegt, und zwar als ${store.me.name}.`, confirmLabel: 'Importieren' })) return;
  const result = await act(() => api('api/import', { data, apply: true }), 'Import fertig.');
  if (!result) return;
  fill(resultBox, el('h3', { text: 'Ergebnis' }), el('p', { text: summaryText(result) }), skippedList(result));
  await Promise.all([load.lands(), load.markers(), load.projects(), load.me()]);
}

const GAME_SWITCHES = [
  ['notify', 'Nachrichten im Chat', 'Kurz nach dem Einloggen eine Zusammenfassung „Zu erledigen“, danach Neues sofort – z. B. Einladungen, überboten, verkauft, neue Bewerbungen.'],
  ['landEnter', 'Land-Anzeige', 'Name des Landes über der Hotbar, wenn du es betrittst, und „Wildnis“, wenn du es verlässt. Nur eine Anzeige – sie schützt nichts.'],
  ['deathMarker', 'Todespunkt-Marker', 'Stirbst du, merkt sich der Planer die Stelle als privaten Marker ☠ (nur du siehst ihn). Die Items schützt das nicht.'],
];

function gameSection() {
  const mine = (store.prefs && store.prefs.game) || {};
  const server = (store.meta && store.meta.gameFeatures) || {};
  return el('section', { class: 'card', id: 'gameSettings' }, el('h2', { text: 'Im Spiel' }),
    el('div', { class: 'stack' }, GAME_SWITCHES.map(([key, title, text]) => {
      const off = server[key] === false;
      const cb = el('input', { type: 'checkbox', 'data-game': key, disabled: off });
      cb.checked = !off && mine[key] !== false;
      cb.addEventListener('change', async () => {
        const on = cb.checked;
        if (!await act(() => setGameSwitch(key, on), `${title}: ${on ? 'an' : 'aus'}`)) cb.checked = !on;
      });
      return el('label', { class: 'check multiline' }, cb,
        el('span', {}, el('strong', { text: title }), el('br'), el('small', { class: 'muted', text: off ? 'Vom Server-Admin für alle ausgeschaltet.' : text })));
    })),
    el('p', { class: 'muted small', text: 'Das geht auch im Spiel mit /planer einstellungen. Alle Befehle: /planer hilfe.' }),
    server.teamChat !== false
      ? el('p', { class: 'muted small', text: 'Team-Chat im Spiel: /tc <Nachricht> – nur Mitglieder deines Teams lesen mit. Wie jeder Chat steht er im Server-Log.' })
      : null);
}

function favoritesSection() {
  const fav = (store.prefs && store.prefs.favorites) || {};
  const rows = [];
  const gone = (kind, id) => [el('span', { class: 'muted', text: 'nicht mehr vorhanden (gelöscht oder nicht mehr sichtbar) ' }),
    el('button', { class: 'small', onclick: () => act(() => toggleFavorite(kind, id), 'Favorit entfernt.') }, 'Entfernen')];
  const add = (kind, lookup, hrefBase, nameOf) => {
    for (const id of fav[kind] || []) {
      const item = lookup(id);
      rows.push(el('li', {}, item ? el('a', { href: hrefBase + encodeURIComponent(id), text: nameOf(item) }) : gone(kind, id)));
    }
  };
  add('lands', landById, '#/claims/', l => '⌂ ' + l.name);
  add('teams', teamById, '#/teams/', t => `[${t.tag}] ${t.name}`);
  add('projects', projectById, '#/projekte/', p => '⚒ ' + p.name);
  add('listings', tradeById, '#/handel/', l => '⚖ ' + l.title);
  for (const id of fav.markers || []) {
    const m = store.markers.find(x => x.id === id);
    rows.push(el('li', {}, m ? `◆ ${m.name} (X ${m.x} · Z ${m.z})` : gone('markers', id)));
  }
  return el('section', { class: 'card' }, el('h2', { text: 'Favoriten' }),
    rows.length ? el('ul', { class: 'favorites' }, rows) : el('p', { class: 'muted', text: 'Noch keine Favoriten. Klicke bei Ländern, Markern, Teams, Projekten oder Angeboten auf ☆.' }),
    el('p', { class: 'muted small', text: 'Favoriten liegen auf dem Server – sie sind auf jedem Gerät gleich.' }));
}

export default {
  title: 'Einstellungen',
  render(root) {
    load.prefs().catch(() => {});
    const resultBox = el('div', { class: 'import-result' });
    const file = el('input', { type: 'file', accept: '.json,application/json', class: 'hidden', id: 'importFile' });
    file.addEventListener('change', () => {
      const f = file.files && file.files[0];
      file.value = '';
      if (f) importFile(f, resultBox);
    });
    const body = el('div');
    const draw = () => {
      const me = store.me || {};
      const w = store.world || {};
      const theme = storageGet(THEME_KEY) || 'system';
      const maps = me.maps || {};
      fill(body,
        el('div', { class: 'grid two' },
          el('section', { class: 'card' }, el('h2', { text: 'Konto' }),
            el('dl', { class: 'facts' },
              el('dt', { text: 'Name' }), el('dd', { text: me.name || '—' }),
              el('dt', { text: 'Rolle' }), el('dd', { text: me.admin ? 'Server-Admin (OP)' : 'Spieler' }),
              el('dt', { text: 'Chunks' }), el('dd', { text: `${me.usedChunks ?? 0} von ${me.maxChunks ?? '?'}` }),
              el('dt', { text: 'Marker' }), el('dd', { text: `${me.ownMarkers ?? 0} von ${me.maxMarkers ?? '?'}` })),
            el('p', { class: 'muted small', text: 'Der Login gilt auf diesem Gerät, bis du dich abmeldest. Einen neuen Code bekommst du im Spiel mit /planer login.' })),
          el('section', { class: 'card' }, el('h2', { text: 'Darstellung' }),
            el('p', { text: 'Farbschema' }),
            segmented([['system', 'Wie das Gerät'], ['dark', 'Dunkel'], ['light', 'Hell']], theme, v => { storageSet(THEME_KEY, v === 'system' ? null : v); applyTheme(); draw(); }),
            el('p', { class: 'muted small', text: 'Ebenen der Karte, die letzte Ansicht und Filter merkt sich dieser Browser.' }))),
        gameSection(),
        favoritesSection(),
        el('section', { class: 'card' }, el('h2', { text: 'Export und Import' }),
          el('p', { text: 'Der Export enthält deine eigenen Länder (inkl. Notizen), Marker, Projekte und Favoriten als JSON-Datei mit Versionsnummer.' }),
          el('p', { class: 'muted small', text: 'Beim Import wird nie etwas überschrieben oder gelöscht: Es wird nur neu angelegt – als du. Vorher siehst du eine Vorschau. Team- und Land-Verknüpfungen werden gelöst, „nur Team“ wird zu „privat“. Auch alte Marker-Dateien der Webseite (v0.2) gehen.' }),
          el('div', { class: 'row' },
            el('button', { class: 'primary', onclick: exportData }, '⇩ Meine Daten exportieren'),
            el('button', { onclick: () => file.click() }, '⇧ Datei importieren …'), file),
          resultBox,
          el('p', { class: 'muted small', text: 'Ein komplettes Server-Backup ist etwas anderes: Das sind die Dateien im Ordner world/world-planner/ (siehe BACKUP.md).' })),
        el('section', { class: 'card' }, el('h2', { text: 'Server' }),
          el('dl', { class: 'facts' },
            el('dt', { text: 'Welt' }), el('dd', { text: w.worldName || '—' }),
            el('dt', { text: 'Minecraft' }), el('dd', { text: w.minecraftVersion || '—' }),
            el('dt', { text: 'Seed' }), el('dd', { id: 'seedValue', text: w.seed ?? 'versteckt' }),
            el('dt', { text: 'Dimensionen' }), el('dd', { text: (w.dimensions || []).length ? String((w.dimensions || []).length) : '—' }),
            el('dt', { text: 'Live-Karte' }), el('dd', { text: maps.liveUrl || (maps.blueMapDetected ? 'BlueMap erkannt (Standard-Port)' : 'nicht eingerichtet') }),
            el('dt', { text: 'Seed-Vorschau' }), el('dd', { text: maps.seedUrl || 'nicht eingerichtet' }),
            el('dt', { text: 'Claims nur in Entdecktem' }), el('dd', { text: me.claimOnlyDiscovered ? 'ja' : 'nein' }),
            el('dt', { text: 'Handels-Einheit' }), el('dd', { text: me.tradeUnit || '—' }),
            el('dt', { text: 'Daten geladen' }), el('dd', { text: fmtDateTime(store.loaded.lands) })),
          el('p', { class: 'muted small', text: `Lädt: ${fmtNum(store.lands.length)} Länder, ${fmtNum(store.markers.length)} Marker, ${fmtNum(store.projects.length)} Projekte, ${fmtNum(store.teams.length)} Teams, ${fmtNum(store.trades.length)} Angebote.` })),
        el('section', { class: 'card' }, el('h2', { text: 'Wichtig zu wissen' }),
          el('ul', {},
            el('li', { text: 'Länder (Claims) sind eine Planung und Anzeige für alle. Sie verhindern im Spiel kein Abbauen und kein Griefing.' }),
            el('li', { text: 'Der Handel überträgt keine Items und keine Bezahlung. Er hält fest, was ihr abmacht; tauschen müsst ihr im Spiel.' }),
            el('li', { text: 'Die Karte zeigt nur Gebiete, in deren Nähe schon jemand war. Was niemand besucht hat, bleibt dunkel.' }))));
    };
    fill(root, pageHead('Einstellungen'), body);
    return bind(root, ['me', 'meta', 'world', 'prefs', 'lands', 'markers', 'projects', 'teams', 'trades'], draw);
  },
};
