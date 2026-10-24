// Dashboard: was gibt es zu tun, was ist los auf dem Server? Alles aus echten Server-Daten berechnet.
import { map } from '../map.js';
import { load, myTeams, serverNow, store } from '../store.js';
import { dimName, el, fill, fmtAgo, fmtLeft, fmtNum, pill, plural, progressBar } from '../util.js';
import { bind, stat } from './common.js';
import { openMarkerForm } from './markers.js';
import { createProject } from './projects.js';

const enc = encodeURIComponent;

/** Alle offenen Punkte für den Spieler – nur aus Daten, die der Server geliefert hat. */
export function todoItems() {
  const me = store.me || {};
  const items = [];
  for (const l of store.lands.filter(x => x.invitedMe)) {
    items.push({ kind: 'invite', text: `Einladung: Land »${l.name}« mitbesitzen`, href: '#/claims/' + enc(l.id) });
  }
  for (const t of store.teams.filter(x => x.myInvite)) {
    items.push({ kind: 'invite', text: `Team-Einladung von ${t.myInvite.by.name}: [${t.tag}] ${t.name}`, href: '#/teams/' + enc(t.id) });
  }
  for (const t of store.teams.filter(x => x.pendingTransfer && x.pendingTransfer.to.uuid === me.uuid)) {
    items.push({ kind: 'warn', text: `Dir wird das Team [${t.tag}] übergeben – annehmen oder ablehnen`, href: '#/teams/' + enc(t.id) });
  }
  for (const t of myTeams()) {
    const apps = (t.applications || []).filter(a => a.status === 'pending').length;
    if (apps) items.push({ kind: 'info', text: `${plural(apps, 'offene Bewerbung', 'offene Bewerbungen')} bei [${t.tag}]`, href: '#/teams/' + enc(t.id) });
  }
  for (const l of store.trades) {
    if (l.status === 'active' && l.iBid && !l.iAmHighest) items.push({ kind: 'warn', text: `Überboten: »${l.title}« (jetzt ${fmtNum(l.highestBid)})`, href: '#/handel/' + enc(l.id) });
    const h = l.handover;
    if (l.status === 'sold' && h && h.status === 'pending' && ((l.mine && !h.sellerConfirmedAt) || (l.iWon && !h.buyerConfirmedAt))) {
      items.push({ kind: 'warn', text: `Übergabe bestätigen: »${l.title}«`, href: '#/handel/' + enc(l.id) });
    }
    if (me.admin && h && h.status === 'disputed') items.push({ kind: 'danger', text: `Streitfall klären: »${l.title}«`, href: '#/handel/' + enc(l.id) });
  }
  const today = new Date().toISOString().slice(0, 10);
  for (const p of store.projects.filter(x => x.mine && x.status !== 'done' && x.dueDate && x.dueDate < today)) {
    items.push({ kind: 'warn', text: `Projekt überfällig: »${p.name}«`, href: '#/projekte/' + enc(p.id) });
  }
  return items;
}

export default {
  title: 'Dashboard',
  render(root) {
    Promise.allSettled([load.teams(), load.trades(), load.projects(), load.activity(), load.people()]);
    const draw = () => {
      const me = store.me || {};
      const w = store.world || {};
      const todos = todoItems();
      const myLands = store.lands.filter(l => l.mine);
      const myListings = store.trades.filter(l => l.mine && l.status === 'active');
      const myProjects = store.projects.filter(p => (p.mine || (p.teamId && myTeams().some(t => t.id === p.teamId))) && p.status !== 'done');
      const online = store.players;
      const bm = store.blueMapStatus;
      fill(root,
        el('div', { class: 'page-head' },
          el('div', {}, el('h1', { text: `Hallo ${me.name || ''}!` }),
            el('p', { class: 'muted', text: `${w.worldName || 'Minecraft-Welt'} · Minecraft ${w.minecraftVersion || '?'}` })),
          el('div', { class: 'row' },
            el('a', { class: 'button primary', href: '#/karte' }, 'Karte öffnen'),
            el('button', { onclick: () => openMarkerForm() }, '+ Marker'),
            el('button', { onclick: () => createProject() }, '+ Projekt'),
            el('a', { class: 'button', href: '#/handel' }, 'Handel'))),
        el('div', { class: 'stats' },
          stat(`${me.usedChunks ?? 0}/${me.maxChunks ?? '?'}`, 'Chunks', '#/claims'),
          stat(myLands.length, myLands.length === 1 ? 'Land' : 'Länder', '#/claims'),
          stat(me.ownMarkers ?? 0, 'Marker', '#/marker'),
          stat(myTeams().length ? myTeams().map(t => '[' + t.tag + ']').join(' ') : '—', 'Team', '#/teams'),
          stat(myProjects.length, 'offene Projekte', '#/projekte'),
          stat(myListings.length, 'aktive Angebote', '#/handel')),
        el('div', { class: 'grid two' },
          el('section', { class: 'card', id: 'todoCard' },
            el('h2', { text: 'Zu erledigen' }),
            todos.length ? el('ul', { class: 'todo' }, todos.map(t => el('li', { class: t.kind }, el('a', { href: t.href, text: t.text }))))
              : el('p', { class: 'muted', text: 'Nichts offen. 🎉' })),
          el('section', { class: 'card' },
            el('h2', { text: 'Server' }),
            el('dl', { class: 'facts' },
              el('dt', { text: 'Online' }), el('dd', { text: store.playersHidden ? 'versteckt' : online.length ? online.map(p => p.name).join(', ') : 'niemand' }),
              el('dt', { text: 'Entdeckt' }), el('dd', { text: `${fmtNum(map.discoveredCount)} Chunks in ${dimName(map.dim)}` }),
              el('dt', { text: 'Länder' }), el('dd', { text: `${fmtNum(store.lands.length)} (alle Spieler)` }),
              el('dt', { text: 'Teams' }), el('dd', { text: fmtNum(store.teams.length) }),
              el('dt', { text: '3D-Karte' }), el('dd', { text: !bm ? '…' : !bm.available ? 'BlueMap nicht aktiv' : bm.renderQueue > 0 ? `rendert (${bm.renderQueue} Aufgaben)` : bm.paused ? 'pausiert' : 'bereit' }),
              el('dt', { text: 'Seed' }), el('dd', { text: w.seed ?? 'versteckt' })))),
        el('div', { class: 'grid two' },
          el('section', { class: 'card' },
            el('div', { class: 'row between' }, el('h2', { text: 'Projekte' }), el('a', { href: '#/projekte', text: 'alle' })),
            myProjects.length ? el('ul', { class: 'mini-list' }, myProjects.slice(0, 6).map(p => el('li', {},
              el('a', { href: '#/projekte/' + enc(p.id), text: p.name }),
              p.progress.total ? el('div', { class: 'row tight' }, progressBar(p.progress.done, p.progress.total), el('small', { text: `${p.progress.done}/${p.progress.total}` })) : null)))
              : el('p', { class: 'muted', text: 'Keine offenen Projekte von dir oder deinem Team.' })),
          el('section', { class: 'card' },
            el('div', { class: 'row between' }, el('h2', { text: 'Handel' }), el('a', { href: '#/handel', text: 'alle' })),
            (() => {
              const ending = store.trades.filter(l => l.status === 'active').sort((a, b) => a.endsAt - b.endsAt).slice(0, 5);
              return ending.length ? el('ul', { class: 'mini-list' }, ending.map(l => el('li', {},
                el('a', { href: '#/handel/' + enc(l.id), text: l.title }), el('small', { class: 'muted', text: ' · endet in ' + fmtLeft(l.endsAt - serverNow()) }))))
                : el('p', { class: 'muted', text: 'Gerade keine aktiven Angebote.' });
            })())),
        el('section', { class: 'card' },
          el('div', { class: 'row between' }, el('h2', { text: 'Was ist passiert?' }), pill('nur echte Aktionen', 'muted')),
          store.activity.length ? el('ul', { class: 'timeline', id: 'activityList' }, store.activity.slice(0, 12).map(e => el('li', {},
            el('span', { text: e.text }), el('small', { class: 'muted', text: ' · ' + fmtAgo(e.at) }))))
            : el('p', { class: 'muted', text: 'Noch keine Aktivität.' })));
    };
    return bind(root, ['me', 'world', 'lands', 'teams', 'trades', 'projects', 'activity', 'players', 'blueMapStatus', 'discovered'], draw);
  },
};
