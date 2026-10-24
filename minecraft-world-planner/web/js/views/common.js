// Gemeinsame Bausteine für die Ansichten.
import { on, store, isFavorite, toggleFavorite, label } from '../store.js';
import { act, el, fill, pill, swatch } from '../util.js';

/**
 * Zeichnet einen Bereich neu, sobald sich die genannten Daten ändern. Tippt man gerade in einem Feld dieses
 * Bereichs, wird bis zum Verlassen des Felds gewartet – sonst wäre die Eingabe plötzlich weg.
 * Gibt eine Aufräum-Funktion zurück.
 */
export function bind(container, keys, render) {
  let pending = false;
  const run = () => {
    const a = document.activeElement;
    // Nur echte Textfelder zählen als „tippt gerade“ – ein angeklicktes Häkchen oder eine Auswahl nicht
    const typing = a && container.contains(a) && (a.tagName === 'TEXTAREA'
      || (a.tagName === 'INPUT' && !['checkbox', 'radio', 'button', 'submit', 'color', 'file'].includes(a.type)));
    if (typing) {
      if (!pending) {
        pending = true;
        container.addEventListener('focusout', () => { pending = false; setTimeout(run, 0); }, { once: true });
      }
      return;
    }
    render();
  };
  const offs = keys.map(k => on(k, run));
  render();
  return () => offs.forEach(off => off());
}

export function favButton(kind, id) {
  const onNow = isFavorite(kind, id);
  return el('button', {
    class: 'icon fav' + (onNow ? ' on' : ''), title: onNow ? 'Aus Favoriten entfernen' : 'Zu Favoriten', 'aria-pressed': onNow ? 'true' : 'false',
    'aria-label': onNow ? 'Aus Favoriten entfernen' : 'Zu Favoriten hinzufügen',
    onclick: e => { e.stopPropagation(); act(() => toggleFavorite(kind, id)); },
  }, onNow ? '★' : '☆');
}

export function teamBadge(team) {
  if (!team) return null;
  const b = el('span', { class: 'team-badge', title: team.name });
  b.append(swatch(team.color), document.createTextNode((team.symbol ? team.symbol + ' ' : '') + '[' + team.tag + ']'));
  return b;
}

export function teamBadgeById(teamId) {
  const t = store.teams.find(x => x.id === teamId);
  return t ? teamBadge(t) : (teamId ? pill('Team', 'muted') : null);
}

export function pageHead(title, subtitle, ...actions) {
  return el('div', { class: 'page-head' },
    el('div', {}, el('h1', { text: title }), subtitle ? el('p', { class: 'muted', text: subtitle }) : null),
    actions.length ? el('div', { class: 'row' }, ...actions) : null);
}

/** Filter-Leiste: Knöpfe, von denen einer aktiv ist. */
export function segmented(options, value, onChange, labelText) {
  const box = el('div', { class: 'segmented', role: 'group', 'aria-label': labelText || 'Filter' });
  for (const [v, text] of options) {
    box.append(el('button', { class: v === value ? 'active' : '', 'aria-pressed': v === value ? 'true' : 'false', onclick: () => onChange(v) }, text));
  }
  return box;
}

export function searchInput(value, onInput, placeholder = 'Suchen …') {
  const i = el('input', { type: 'search', placeholder, 'aria-label': placeholder, value });
  i.addEventListener('input', () => onInput(i.value));
  return i;
}

export function selectInput(options, value, onChange, ariaLabel) {
  const s = el('select', { 'aria-label': ariaLabel }, options.map(([v, t]) => el('option', { value: v, text: t })));
  s.value = value;
  s.addEventListener('change', () => onChange(s.value));
  return s;
}

export function metaLabel(group, key) {
  return label(group, key);
}

/** Kleine Kennzahl-Kachel fürs Dashboard */
export function stat(value, text, href) {
  const inner = [el('strong', { text: String(value) }), el('span', { text })];
  return href ? el('a', { class: 'stat', href }, inner) : el('div', { class: 'stat' }, inner);
}

export function list(items, renderItem, emptyText) {
  if (!items.length) return el('div', { class: 'empty' }, el('p', { text: emptyText }));
  return el('div', { class: 'list' }, items.map(renderItem));
}

export function replace(node, content) {
  return fill(node, content);
}

/** Datalist mit allen bekannten Spielernamen (für Einladen-Felder). */
export function playerDatalist() {
  let dl = document.getElementById('knownPlayersList');
  if (!dl) {
    dl = el('datalist', { id: 'knownPlayersList' });
    document.body.append(dl);
  }
  fill(dl, store.knownPlayers.map(n => el('option', { value: n })));
  return 'knownPlayersList';
}
