// Claims (Länder): Liste, Einladungen und Detailseite. Länder sind eine Anzeige für alle – sie schützen im Spiel nichts.
import { api, enc } from '../api.js';
import { showIn3D } from '../bluemap.js';
import { showLand } from '../map.js';
import { navigate } from '../router.js';
import { landById, load, myTeams, store } from '../store.js';
import { act, confirmDialog, dimName, el, fill, fmtAgo, fmtDate, formDialog, pill, plural, swatch } from '../util.js';
import { bind, favButton, pageHead, playerDatalist, searchInput, segmented, selectInput, teamBadgeById } from './common.js';

const state = { filter: 'all', q: '', dim: '', sort: 'name' };

async function refresh() {
  await Promise.all([load.lands(), load.me()]);
}

async function landAction(land, action, body = {}, okMessage) {
  const r = await act(() => api(`api/lands/${enc(land.id)}/${action}`, body), okMessage);
  if (r) await refresh();
  return r;
}

function boundsText(l) {
  const b = l.bounds;
  return `X ${b.minX}…${b.maxX} · Z ${b.minZ}…${b.maxZ}`;
}

function openOnMap(land) {
  navigate('#/karte');
  showLand(land);
}

function in3D(land) {
  const b = land.bounds;
  showLand(land).then(() => showIn3D((b.minX + b.maxX + 1) / 2, (b.minZ + b.maxZ + 1) / 2, navigate));
}

export function landItem(l) {
  return el('article', { class: 'item' + (l.mine ? ' mine' : '') },
    el('div', { class: 'item-top' },
      el('a', { class: 'row tight title-link', href: '#/claims/' + enc(l.id) }, swatch(l.color), el('strong', { text: l.name })),
      el('div', { class: 'row tight' }, favButton('lands', l.id), l.mine ? pill('Meins', 'ok') : null, l.invitedMe ? pill('Eingeladen', 'info') : null)),
    el('div', { class: 'meta' },
      el('span', { text: plural(l.chunkCount, 'Chunk', 'Chunks') }),
      el('span', { text: dimName(l.dimension) }),
      el('span', { text: boundsText(l) }),
      el('span', { text: 'Besitzer: ' + l.owners.map(o => o.name).join(', ') }),
      l.teamId ? teamBadgeById(l.teamId) : null),
    l.description ? el('p', { class: 'notes', text: l.description }) : null,
    el('div', { class: 'row' },
      el('button', { class: 'small', onclick: () => openOnMap(l) }, 'Auf Karte'),
      el('button', { class: 'small', onclick: () => in3D(l) }, 'In 3D'),
      el('a', { class: 'button small', href: '#/claims/' + enc(l.id) }, 'Details')));
}

function invitesBox() {
  const invites = store.lands.filter(l => l.invitedMe);
  if (!invites.length) return null;
  return el('section', { class: 'card highlight', id: 'invitesPanel' },
    el('h2', { text: 'Einladungen für dich' }),
    el('p', { class: 'muted', text: 'Nimmst du an, besitzt du das Land gleichberechtigt mit – und die Chunks zählen zu deinem Limit.' }),
    el('div', { class: 'list', id: 'invitesList' }, invites.map(l => el('div', { class: 'item' },
      el('div', { class: 'item-top' }, el('div', { class: 'row tight' }, swatch(l.color), el('strong', { text: l.name })), el('span', { class: 'muted', text: plural(l.chunkCount, 'Chunk', 'Chunks') })),
      el('small', { text: 'Besitzer: ' + l.owners.map(o => o.name).join(', ') }),
      el('div', { class: 'row' },
        el('button', { class: 'primary small', onclick: () => landAction(l, 'accept', {}, `Du besitzt jetzt »${l.name}« mit.`) }, 'Annehmen'),
        el('button', { class: 'small', onclick: () => landAction(l, 'decline', {}, 'Einladung abgelehnt.') }, 'Ablehnen'),
        el('button', { class: 'small', onclick: () => openOnMap(l) }, 'Ansehen'))))));
}

function filtered() {
  const q = state.q.trim().toLowerCase();
  const myTeamIds = new Set(myTeams().map(t => t.id));
  const items = store.lands.filter(l => {
    if (state.filter === 'mine' && !l.mine) return false;
    if (state.filter === 'team' && !(l.teamId && myTeamIds.has(l.teamId))) return false;
    if (state.filter === 'fav' && !((store.prefs.favorites || {}).lands || []).includes(l.id)) return false;
    if (state.dim && l.dimension !== state.dim) return false;
    if (q && !(l.name.toLowerCase().includes(q) || l.owners.some(o => o.name.toLowerCase().includes(q)) || (l.description || '').toLowerCase().includes(q))) return false;
    return true;
  });
  const sorts = {
    name: (a, b) => a.name.localeCompare(b.name, 'de'),
    size: (a, b) => b.chunkCount - a.chunkCount,
    new: (a, b) => b.createdAt - a.createdAt,
    changed: (a, b) => (b.updatedAt || 0) - (a.updatedAt || 0),
  };
  return items.sort(sorts[state.sort] || sorts.name);
}

function renderList(root) {
  const controls = el('div', { class: 'toolbar' });
  const listBox = el('div');
  const head = el('div');
  const draw = () => {
    const me = store.me || {};
    fill(head, pageHead('Claims', `Du hast ${me.usedChunks ?? 0} von ${me.maxChunks ?? '?'} Chunks in ${plural(me.ownedLands ?? 0, 'Land', 'Ländern')}. Neue Länder legst du auf der Karte an.`,
      el('a', { class: 'button primary', href: '#/karte' }, '+ Auf der Karte claimen')), invitesBox());
    const dims = (store.world && store.world.dimensions) || [];
    fill(controls,
      segmented([['all', 'Alle'], ['mine', 'Meine'], ['team', 'Team'], ['fav', '★ Favoriten']], state.filter, v => { state.filter = v; draw(); }),
      selectInput([['', 'Alle Dimensionen'], ...dims.map(d => [d, dimName(d)])], state.dim, v => { state.dim = v; draw(); }, 'Dimension'),
      selectInput([['name', 'Name A–Z'], ['size', 'Größte zuerst'], ['new', 'Neueste'], ['changed', 'Zuletzt geändert']], state.sort, v => { state.sort = v; draw(); }, 'Sortierung'),
      searchInput(state.q, v => { state.q = v; drawList(); }, 'Land oder Besitzer suchen …'));
    drawList();
  };
  const drawList = () => {
    const items = filtered();
    fill(listBox, items.length ? el('div', { class: 'list', id: 'landList' }, items.map(landItem))
      : el('div', { class: 'empty' }, el('p', { text: store.lands.length ? 'Kein Land passt zu den Filtern.' : 'Noch hat niemand Land geclaimt. Auf der Karte kannst du entdeckte Chunks auswählen.' })));
  };
  fill(root, head, controls, listBox);
  return bind(root, ['lands', 'me', 'teams', 'prefs', 'world'], draw);
}

// ------------------------------------------------------------------ Detail

async function editLand(l) {
  const v = await formDialog({
    title: 'Land bearbeiten',
    fields: [
      { name: 'name', label: 'Name', value: l.name, required: true, maxLength: 24 },
      { name: 'color', label: 'Farbe', type: 'color', value: l.color },
      { name: 'description', label: 'Beschreibung (für alle sichtbar)', type: 'textarea', value: l.description, maxLength: 200 },
      { name: 'notes', label: 'Notizen (nur für Besitzer)', type: 'textarea', value: l.notes || '', maxLength: 1000, rows: 4 },
    ],
  });
  if (v) await landAction(l, 'edit', v, 'Gespeichert.');
}

function teamSection(l) {
  const teams = myTeams().filter(t => ['member', 'moderator', 'admin', 'owner'].includes(t.myRole));
  const current = l.teamId ? store.teams.find(t => t.id === l.teamId) : null;
  const canLink = l.mine && teams.length;
  const canUnlink = l.teamId && (l.mine || (store.me && store.me.admin) || (current && ['admin', 'owner'].includes(current.myRole)));
  const sel = canLink ? selectInput(teams.map(t => [t.id, `[${t.tag}] ${t.name}`]), l.teamId || teams[0].id, () => {}, 'Team') : null;
  return el('section', { class: 'card' },
    el('h2', { text: 'Team' }),
    current ? el('p', {}, 'Gehört zum Team ', teamBadgeById(l.teamId), ' ', el('a', { href: '#/teams/' + enc(current.id), text: current.name }))
      : el('p', { class: 'muted', text: l.teamId ? 'Gehört zu einem Team, das du nicht sehen kannst.' : 'Keinem Team zugeordnet.' }),
    canLink ? el('div', { class: 'row' }, sel,
      el('button', { class: 'small', onclick: () => landAction(l, 'team', { teamId: sel.value }, 'Team zugeordnet.') }, 'Zuordnen')) : null,
    canUnlink ? el('button', { class: 'small', onclick: () => landAction(l, 'team', { teamId: '' }, 'Team-Zuordnung gelöst.') }, 'Zuordnung lösen') : null,
    el('p', { class: 'muted small', text: 'Die Besitzer bleiben die Besitzer. Das Team sieht das Land als Team-Land; Team-Admins können die Zuordnung lösen.' }));
}

function ownersSection(l) {
  const me = store.me || {};
  const invite = el('input', { list: playerDatalist(), placeholder: 'Spielername', 'aria-label': 'Spieler einladen', autocomplete: 'off' });
  return el('section', { class: 'card' },
    el('h2', { text: 'Besitzer' }),
    el('p', { class: 'muted small', text: 'Alle Besitzer sind gleichberechtigt. Niemand kann einen anderen rauswerfen – nur Server-Admins.' }),
    el('div', { class: 'list' }, l.owners.map(o => el('div', { class: 'item row between' },
      el('span', { text: o.name + (o.uuid === me.uuid ? ' (du)' : '') }),
      me.admin && o.uuid !== me.uuid ? el('button', { class: 'small danger-text', onclick: async () => {
        if (await confirmDialog({ title: 'Besitzer entfernen?', text: `${o.name} aus »${l.name}« entfernen (Admin)?`, confirmLabel: 'Entfernen', danger: true })) {
          await landAction(l, 'remove-owner', { player: o.name }, `${o.name} entfernt.`);
        }
      } }, 'Entfernen (Admin)') : null))),
    l.mine ? el('form', { class: 'row', onsubmit: async e => {
      e.preventDefault();
      if (invite.value.trim()) await landAction(l, 'invite', { player: invite.value.trim() }, `${invite.value.trim()} ist eingeladen.`);
    } }, invite, el('button', { class: 'small', type: 'submit' }, 'Einladen')) : null,
    l.invites && l.invites.length ? el('div', {}, el('h3', { text: 'Eingeladen' }), el('div', { class: 'list' }, l.invites.map(i => el('div', { class: 'item row between' },
      el('span', { text: i.name }),
      el('button', { class: 'small', onclick: () => landAction(l, 'uninvite', { player: i.name }, 'Einladung zurückgezogen.') }, 'Zurückziehen'))))) : null);
}

function linkedSection(l) {
  const projects = store.projects.filter(p => p.landId === l.id);
  const markers = store.markers.filter(m => m.landId === l.id);
  if (!projects.length && !markers.length) return null;
  return el('section', { class: 'card' },
    el('h2', { text: 'Verknüpft' }),
    projects.length ? el('div', {}, el('h3', { text: 'Projekte' }), el('ul', {}, projects.map(p => el('li', {}, el('a', { href: '#/projekte/' + enc(p.id), text: p.name }))))) : null,
    markers.length ? el('div', {}, el('h3', { text: 'Marker' }), el('ul', {}, markers.map(m => el('li', { text: `${m.name} (X ${m.x} · Z ${m.z})` })))) : null);
}

function historySection(l) {
  const events = store.activity.filter(e => e.refType === 'land' && e.refId === l.id).slice(0, 10);
  if (!events.length) return null;
  return el('section', { class: 'card' }, el('h2', { text: 'Verlauf' }),
    el('ul', { class: 'timeline' }, events.map(e => el('li', {}, el('span', { text: e.text }), el('small', { class: 'muted', text: ' · ' + fmtAgo(e.at) })))));
}

function renderDetail(root, id) {
  const draw = () => {
    const l = landById(id);
    if (!l) {
      fill(root, pageHead('Land nicht gefunden', 'Vielleicht wurde es aufgelöst.'), el('a', { class: 'button', href: '#/claims' }, '← Alle Claims'));
      return;
    }
    const me = store.me || {};
    const canDelete = l.can && l.can.delete;
    fill(root,
      el('a', { class: 'back', href: '#/claims' }, '← Alle Claims'),
      el('div', { class: 'page-head' },
        el('div', { class: 'row tight' }, swatch(l.color, 'big'), el('h1', { text: l.name }), favButton('lands', l.id), l.teamId ? teamBadgeById(l.teamId) : null),
        el('div', { class: 'row' },
          el('button', { onclick: () => openOnMap(l) }, 'Auf Karte'),
          el('button', { onclick: () => in3D(l) }, 'In 3D'),
          l.can && l.can.edit ? el('button', { class: 'primary', onclick: () => editLand(l) }, 'Bearbeiten') : null)),
      l.invitedMe ? el('section', { class: 'card highlight' }, el('p', { text: 'Du bist eingeladen, dieses Land mitzubesitzen.' }),
        el('div', { class: 'row' },
          el('button', { class: 'primary', onclick: () => landAction(l, 'accept', {}, `Du besitzt jetzt »${l.name}« mit.`) }, 'Annehmen'),
          el('button', { onclick: () => landAction(l, 'decline', {}, 'Einladung abgelehnt.') }, 'Ablehnen'))) : null,
      el('div', { class: 'grid two' },
        el('section', { class: 'card' },
          el('h2', { text: 'Übersicht' }),
          el('dl', { class: 'facts' },
            el('dt', { text: 'Dimension' }), el('dd', { text: dimName(l.dimension) }),
            el('dt', { text: 'Größe' }), el('dd', { text: plural(l.chunkCount, 'Chunk', 'Chunks') + ` (${l.chunkCount * 256} Blöcke²)` }),
            el('dt', { text: 'Grenzen' }), el('dd', { text: boundsText(l) }),
            el('dt', { text: 'Angelegt' }), el('dd', { text: `${fmtDate(l.createdAt)} von ${l.createdBy ? l.createdBy.name : '?'}` }),
            el('dt', { text: 'Geändert' }), el('dd', { text: l.updatedAt ? fmtAgo(l.updatedAt) : 'unbekannt' })),
          l.description ? el('p', { class: 'notes', text: l.description }) : el('p', { class: 'muted', text: 'Keine Beschreibung.' }),
          l.notes != null ? el('div', {}, el('h3', { text: 'Notizen (nur Besitzer)' }), el('p', { class: 'notes', text: l.notes || '—' })) : null,
          el('p', { class: 'muted small', text: 'Hinweis: Ein Land ist eine Planung und Anzeige. Es verhindert im Spiel kein Abbauen oder Griefing.' })),
        ownersSection(l)),
      el('div', { class: 'grid two' }, teamSection(l), linkedSection(l) || historySection(l)),
      linkedSection(l) ? historySection(l) : null,
      el('section', { class: 'card danger-zone' },
        el('h2', { text: 'Austreten oder löschen' }),
        l.mine ? el('button', { onclick: async () => {
          if (!await confirmDialog({ title: 'Austreten?', text: l.owners.length === 1 ? `Du bist der letzte Besitzer – »${l.name}« wird dann aufgelöst und die Chunks sind frei.` : `Du gibst »${l.name}« ab. Die anderen Besitzer behalten es.`, confirmLabel: 'Austreten', danger: l.owners.length === 1 })) return;
          const r = await landAction(l, 'leave', {}, res => res.deleted ? 'Land aufgelöst.' : 'Du bist ausgetreten.');
          if (r) navigate('#/claims');
        } }, 'Austreten') : null,
        canDelete ? el('button', { class: 'danger', onclick: async () => {
          if (!await confirmDialog({ title: 'Land löschen?', text: `»${l.name}« wird gelöscht, alle ${l.chunkCount} Chunks werden frei. Verknüpfte Projekte und Marker bleiben erhalten.`, confirmLabel: 'Endgültig löschen', danger: true, requireText: l.name })) return;
          const r = await landAction(l, 'delete', {}, 'Land gelöscht.');
          if (r) navigate('#/claims');
        } }, me.admin && !l.mine ? 'Löschen (Admin)' : 'Löschen') : null,
        !canDelete && l.mine ? el('p', { class: 'muted small', text: 'Löschen geht nur, wenn du der einzige Besitzer bist. Mit Mitbesitzern kannst du austreten.' }) : null,
        !l.mine && !me.admin ? el('p', { class: 'muted small', text: 'Nur Besitzer können austreten oder löschen.' }) : null));
  };
  return bind(root, ['lands', 'me', 'teams', 'projects', 'markers', 'activity', 'prefs'], draw);
}

export default {
  title: 'Claims',
  render(root, route) {
    if (route.id) {
      load.activity().catch(() => {});
      return renderDetail(root, route.id);
    }
    return renderList(root);
  },
};
