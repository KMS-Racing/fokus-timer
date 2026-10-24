// Globale Suche oben in der Leiste: Länder, Marker, Projekte, Teams, Spieler, Angebote – und Koordinaten.
// Durchsucht nur, was der Server diesem Spieler ohnehin zeigt (es gibt keine extra Such-Schnittstelle).
import { fmtCoords, parseCoords } from './coords.js';
import { centerOn, map, showPoint } from './map.js';
import { navigate } from './router.js';
import { store } from './store.js';
import { dimName, el, fill } from './util.js';

const enc = encodeURIComponent;
const MAX_PER_GROUP = 5;

export function searchAll(query) {
  const q = query.trim().toLowerCase();
  if (q.length < 2) return [];
  const groups = [];
  const coords = parseCoords(query);
  if (coords) {
    groups.push({ title: 'Koordinaten', items: [{ text: `Gehe zu ${fmtCoords(coords)}`, sub: dimName(map.dim), run: () => { navigate('#/karte'); centerOn(coords.x + 0.5, coords.z + 0.5, Math.max(map.view.zoom, 1)); } }] });
  }
  const add = (title, list, toItem) => {
    const items = list.slice(0, MAX_PER_GROUP).map(toItem);
    if (items.length) groups.push({ title, items });
  };
  const has = (...texts) => texts.some(t => t && t.toLowerCase().includes(q));
  add('Länder', store.lands.filter(l => has(l.name, l.description, ...l.owners.map(o => o.name))), l => ({
    text: l.name, sub: `${dimName(l.dimension)} · ${l.owners.map(o => o.name).join(', ')}`, run: () => navigate('#/claims/' + enc(l.id)),
  }));
  add('Marker', store.markers.filter(m => has(m.name, m.notes, m.ownerName)), m => ({
    text: m.name, sub: `${dimName(m.dimension)} · X ${m.x} · Z ${m.z}`, run: () => { navigate('#/karte'); showPoint(m.dimension, m.x + 0.5, m.z + 0.5, 1.5); },
  }));
  add('Projekte', store.projects.filter(p => has(p.name, p.description)), p => ({
    text: p.name, sub: `${p.progress.done}/${p.progress.total} Aufgaben`, run: () => navigate('#/projekte/' + enc(p.id)),
  }));
  add('Teams', store.teams.filter(t => has(t.name, t.tag)), t => ({
    text: `[${t.tag}] ${t.name}`, sub: `${t.memberCount} Mitglieder`, run: () => navigate('#/teams/' + enc(t.id)),
  }));
  add('Spieler', store.people.filter(p => has(p.name)), p => ({
    text: p.name, sub: p.online ? 'online' : 'offline', run: () => navigate('#/spieler'),
  }));
  add('Handel', store.trades.filter(l => l.status === 'active' && has(l.title, l.itemName, l.wants)), l => ({
    text: l.title, sub: l.kindLabel, run: () => navigate('#/handel/' + enc(l.id)),
  }));
  return groups;
}

export function setupSearch(input, box) {
  let active = -1;
  let flat = [];
  const close = () => { box.classList.add('hidden'); input.setAttribute('aria-expanded', 'false'); active = -1; };
  const choose = item => { close(); input.value = ''; input.blur(); item.run(); };
  const render = () => {
    const groups = searchAll(input.value);
    flat = groups.flatMap(g => g.items);
    if (!input.value.trim()) { close(); return; }
    if (!flat.length) {
      fill(box, el('div', { class: 'search-empty', text: 'Nichts gefunden. Tipp: Koordinaten wie „100 -200“ gehen auch.' }));
    } else {
      let i = 0;
      fill(box, groups.map(g => el('div', { class: 'search-group' }, el('div', { class: 'search-title', text: g.title }),
        g.items.map(item => {
          const idx = i++;
          return el('button', { type: 'button', class: 'search-item' + (idx === active ? ' active' : ''), role: 'option', 'aria-selected': idx === active ? 'true' : 'false',
            onmousedown: e => e.preventDefault(), onclick: () => choose(item) },
          el('span', { text: item.text }), el('small', { class: 'muted', text: item.sub || '' }));
        }))));
    }
    box.classList.remove('hidden');
    input.setAttribute('aria-expanded', 'true');
  };
  input.addEventListener('input', () => { active = -1; render(); });
  input.addEventListener('focus', render);
  input.addEventListener('blur', () => setTimeout(close, 150));
  input.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown') { e.preventDefault(); active = Math.min(flat.length - 1, active + 1); render(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); active = Math.max(0, active - 1); render(); }
    else if (e.key === 'Enter') { e.preventDefault(); const item = flat[active >= 0 ? active : 0]; if (item) choose(item); }
    else if (e.key === 'Escape') { input.value = ''; close(); input.blur(); }
  });
  // "/" springt in die Suche (wenn man nicht gerade tippt)
  document.addEventListener('keydown', e => {
    if (e.key === '/' && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement && document.activeElement.tagName)) {
      e.preventDefault();
      input.focus();
    }
  });
}
