// Spieler: wer ist online, wer war wann da, in welchen Teams, wie viele Länder. Nur echte Server-Daten.
import { showPoint } from '../map.js';
import { navigate } from '../router.js';
import { load, store } from '../store.js';
import { copyText, dimName, el, fill, fmtAgo, pill } from '../util.js';
import { bind, pageHead, searchInput, segmented } from './common.js';

const state = { filter: 'all', q: '' };

function onlinePosition(uuid) {
  return store.players.find(p => p.uuid === uuid);
}

function personItem(p) {
  const pos = onlinePosition(p.uuid);
  const me = store.me && store.me.uuid === p.uuid;
  return el('article', { class: 'item person' },
    el('div', { class: 'item-top' },
      el('div', { class: 'row tight' }, el('span', { class: 'dot ' + (p.online ? 'online' : 'offline'), 'aria-hidden': 'true' }),
        el('strong', { text: p.name + (me ? ' (du)' : '') }), p.role === 'admin' ? pill('Admin', 'gold') : null),
      el('span', { class: 'muted small', text: p.online ? 'online' : store.onlineHidden ? 'Status versteckt' : 'zuletzt ' + fmtAgo(p.lastSeen) })),
    el('div', { class: 'meta' },
      el('span', { text: `${p.lands} ${p.lands === 1 ? 'Land' : 'Länder'} · ${p.chunks} Chunks` }),
      ...p.teams.map(t => el('a', { class: 'team-badge', href: '#/teams/' + encodeURIComponent(t.id), text: `[${t.tag}] ${t.name}` })),
      pos ? el('span', { text: `${dimName(pos.dimension)} · X ${Math.floor(pos.x)} · Y ${Math.floor(pos.y)} · Z ${Math.floor(pos.z)}` }) : null),
    pos ? el('div', { class: 'row' },
      el('button', { class: 'small', onclick: () => { navigate('#/karte'); showPoint(pos.dimension, pos.x, pos.z, 1); } }, 'Auf Karte'),
      el('button', { class: 'small', onclick: () => copyText(`${Math.floor(pos.x)} ${Math.floor(pos.y)} ${Math.floor(pos.z)}`) }, 'Koordinaten kopieren')) : null);
}

export default {
  title: 'Spieler',
  render(root) {
    load.people().catch(() => {});
    const controls = el('div', { class: 'toolbar' }), listBox = el('div'), head = el('div');
    const draw = () => {
      const online = store.people.filter(p => p.online).length;
      fill(head, pageHead('Spieler', store.onlineHidden ? 'Der Server zeigt nicht, wer online ist.' : `${online} von ${store.people.length} gerade online.`));
      fill(controls,
        segmented([['all', 'Alle'], ['online', 'Online'], ['admins', 'Admins']], state.filter, v => { state.filter = v; draw(); }),
        searchInput(state.q, v => { state.q = v; drawList(); }, 'Spieler suchen …'));
      drawList();
    };
    const drawList = () => {
      const q = state.q.trim().toLowerCase();
      const items = store.people.filter(p => (state.filter !== 'online' || p.online) && (state.filter !== 'admins' || p.role === 'admin')
        && (!q || p.name.toLowerCase().includes(q)))
        .sort((a, b) => (b.online - a.online) || (b.lastSeen || 0) - (a.lastSeen || 0) || a.name.localeCompare(b.name));
      fill(listBox, items.length ? el('div', { class: 'list', id: 'peopleList' }, items.map(personItem)) : el('div', { class: 'empty' }, el('p', { text: 'Niemand gefunden.' })));
    };
    fill(root, head, controls, listBox,
      el('p', { class: 'muted small', text: '„Zuletzt da“ wird höchstens alle 10 Minuten gespeichert und ist deshalb ungefähr.' }));
    return bind(root, ['people', 'players', 'me'], draw);
  },
};
