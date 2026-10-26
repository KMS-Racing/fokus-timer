// Dashboard: was gibt es zu tun, was ist los auf dem Server? Alles aus echten Server-Daten berechnet.
import { dayKey, eventTodos, fmtWhen } from '../eventfmt.js';
import { fmtDuration, fmtValue } from '../statfmt.js';
import { map } from '../map.js';
import { load, myTeams, serverNow, store } from '../store.js';
import { dimName, el, fill, fmtAgo, fmtDate, fmtLeft, fmtNum, pill, plural, progressBar } from '../util.js';
import { bind, stat } from './common.js';
import { openMarkerForm } from './markers.js';
import { createEvent } from './events.js';
import { createProject } from './projects.js';

const enc = encodeURIComponent;
const tz = () => (store.meta && store.meta.timeZone) || 'Europe/Berlin';
/** So viele Einträge zeigt „Zu erledigen“ zugeklappt – wie die Zusammenfassung im Spiel beim Einloggen. */
const TODO_SHOWN = 6;
let todosOpen = false;
const TODO_RANK = { danger: 1, invite: 2, warn: 3, info: 4 };
/** Wie Notices.rank(): Event beginnt gleich oder gerade, Streitfall, Einladung, Warnung, Info. */
const todoRank = t => (t.urgent ? 0 : TODO_RANK[t.kind] ?? 4);

/** Rückblick auf die letzten 7 Tage – alles vom Server (/api/recap), nichts geschätzt. */
function recapCard() {
  const r = store.recap;
  const head = el('h2', { text: 'Rückblick: letzte 7 Tage' });
  if (!r) return el('section', { class: 'card', id: 'recapCard' }, head, el('p', { class: 'muted', text: 'Lädt …' }));
  const s = r.stats, p = r.projects || { done: [], tasksDone: 0 }, ev = r.events, lands = r.lands || { count: 0, latest: [] };
  const facts = [];
  if (s) {
    facts.push([fmtDuration(s.playTime), 'gespielt'], [fmtNum(s.activePlayers), 'Spieler aktiv']);
    if (s.peakOnline) facts.push([fmtNum(s.peakOnline), 'höchstens gleichzeitig']);
  }
  facts.push([fmtNum(lands.count), lands.count === 1 ? 'neues Land' : 'neue Länder']);
  const lines = [];
  if (s && s.tops.length) {
    lines.push(el('h3', { text: 'Spitze der Woche' }), el('ul', { class: 'mini-list', id: 'recapTops' }, s.tops.map(t => el('li', {},
      el('strong', { text: t.rank + ': ' }), el('a', { href: '#/statistik/spieler/' + enc(t.leader.uuid), text: t.leader.name }),
      el('small', { class: 'muted', text: ` (${fmtValue(t.unit, t.leader.value)})` })))));
  }
  if (s && s.milestones.length) {
    lines.push(el('h3', { text: 'Meilensteine' }), el('ul', { class: 'mini-list' }, s.milestones.slice(0, 4).map(m => el('li', {},
      el('span', { text: `★ ${m.name}: ${m.text}` }), el('small', { class: 'muted', text: ' · ' + fmtAgo(m.at) })))));
  }
  if (p.done.length || p.tasksDone) {
    lines.push(el('h3', { text: 'Projekte' }), el('p', {},
      p.done.length ? ['Fertig: ', ...p.done.flatMap((x, i) => [i ? ', ' : '', el('a', { href: '#/projekte/' + enc(x.id), text: x.name })]), '. '] : null,
      p.tasksDone ? `${plural(p.tasksDone, 'Aufgabe', 'Aufgaben')} erledigt.` : null));
  }
  if (ev && (ev.past.length || ev.upcoming)) {
    lines.push(el('h3', { text: 'Events' }), el('ul', { class: 'mini-list' },
      ev.past.map(x => el('li', {}, el('a', { href: '#/events/' + enc(x.id), text: x.title }),
        el('small', { class: 'muted', text: ` · ${fmtWhen(x.start, tz())} · ${plural(x.coming, 'hatte', 'hatten')} zugesagt` }))),
      ev.upcoming ? el('li', {}, el('a', { href: '#/events', text: `${plural(ev.upcoming, 'Event', 'Events')} in den nächsten 7 Tagen` })) : null));
  }
  const quiet = !lines.length && (!s || !s.playTime) && !lands.count;
  return el('section', { class: 'card', id: 'recapCard' }, head,
    el('div', { class: 'recap-facts' }, facts.map(([v, t]) => el('div', { class: 'recap-fact' }, el('strong', { text: v }), el('span', { text: t })))),
    quiet ? el('p', { class: 'muted', text: 'Diese Woche war es ruhig.' }) : lines,
    s && s.since > r.from ? el('p', { class: 'muted small', text: `Spielzeit und Spitzen zählen erst seit ${fmtDate(s.since)} – da kam der Planer dazu.` }) : null);
}

/** Die nächsten Events (nicht abgesagt, nicht vorbei) – mit deiner Antwort. */
function nextEventsCard() {
  if (store.meta && store.meta.gameFeatures && store.meta.gameFeatures.events === false) return null;
  const now = serverNow();
  const next = store.events.filter(e => e.state !== 'cancelled' && e.end > now).slice(0, 4);
  const today = dayKey(now, tz());
  return el('section', { class: 'card', id: 'nextEvents' },
    el('div', { class: 'row between' }, el('h2', { text: 'Nächste Events' }), el('a', { href: '#/events', text: 'alle' })),
    next.length ? el('ul', { class: 'mini-list' }, next.map(e => el('li', {},
      el('a', { href: '#/events/' + enc(e.id), text: e.title }),
      el('small', { class: 'muted', text: ` · ${e.state === 'running' ? 'läuft gerade' : dayKey(e.start, tz()) === today ? 'heute ' + fmtWhen(e.start, tz()).slice(-5) : fmtWhen(e.start, tz())}`
        + ` · ${e.counts.yes} ${e.counts.yes === 1 ? 'kommt' : 'kommen'}` }),
      e.myAnswer ? [' ', pill('du: ' + ((store.meta.eventAnswers || {})[e.myAnswer] || e.myAnswer), e.myAnswer === 'no' ? 'muted' : 'ok')] : null)))
      : el('p', { class: 'muted', text: 'Nichts geplant.' }, ' ', el('button', { class: 'small', onclick: () => createEvent() }, '+ Event')));
}

/**
 * Alle offenen Punkte für den Spieler – nur aus Daten, die der Server geliefert hat.
 * Dieselben Regeln wie der Server (Notices.java): Im Spiel steht dieselbe Liste.
 */
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
  // Nur wer entscheiden darf (Moderator und höher) – Server-Admins sehen Bewerbungen, entscheiden aber nicht.
  for (const t of myTeams().filter(x => x.can && x.can.decide)) {
    const apps = (t.applications || []).filter(a => a.status === 'pending').length;
    if (apps) items.push({ kind: 'info', text: `${plural(apps, 'offene Bewerbung', 'offene Bewerbungen')} bei [${t.tag}]`, href: '#/teams/' + enc(t.id) });
  }
  for (const l of store.trades) {
    const href = '#/handel/' + enc(l.id);
    const title = `»${l.title}«`;
    const price = l.finalPrice != null ? ` (${fmtNum(l.finalPrice)} ${l.unit})` : '';
    if (l.status === 'active' && l.iBid && !l.iAmHighest) items.push({ kind: 'warn', text: `Überboten: ${title} (jetzt ${fmtNum(l.highestBid)} ${l.unit})`, href });
    const h = l.handover;
    if (l.status === 'sold' && h && h.status === 'pending') {
      if (l.mine && !h.sellerConfirmedAt) {
        items.push({ kind: 'warn', text: `${title} ist an ${l.buyer ? l.buyer.name : '?'} verkauft${price} – Übergabe im Spiel machen und bestätigen`, href });
      } else if (l.iWon && !h.buyerConfirmedAt) {
        const what = l.soldVia === 'bid' ? `Du hast die Auktion ${title} gewonnen${price}`
          : l.soldVia === 'offer' ? `Dein Gegenangebot für ${title} wurde angenommen` : `Du hast ${title} gekauft${price}`;
        items.push({ kind: 'warn', text: what + ' – nach der Übergabe bestätigen', href });
      }
    }
    if (me.admin && h && h.status === 'disputed') items.push({ kind: 'danger', text: `Streitfall klären: ${title}`, href });
  }
  const today = new Date().toISOString().slice(0, 10);
  for (const p of store.projects.filter(x => x.mine && x.status !== 'done' && x.dueDate && x.dueDate < today)) {
    items.push({ kind: 'warn', text: `Projekt überfällig: »${p.name}«`, href: '#/projekte/' + enc(p.id) });
  }
  items.push(...eventTodos(store.events, serverNow(), tz()));
  return items.sort((a, b) => todoRank(a) - todoRank(b)); // stabil: gleicher Rang bleibt in Reihenfolge, wie im Spiel
}

/** Die ersten TODO_SHOWN Einträge, der Rest zum Aufklappen. */
function todoList(todos, redraw) {
  if (!todos.length) return el('p', { class: 'muted', text: 'Nichts offen. 🎉' });
  const rest = todos.length - TODO_SHOWN;
  return [
    el('ul', { class: 'todo', id: 'todoList' }, (todosOpen ? todos : todos.slice(0, TODO_SHOWN)).map(t =>
      el('li', { class: t.kind }, el('a', { href: t.href, text: t.text })))),
    rest > 0 ? el('button', { class: 'small todo-more', id: 'todoMore', 'aria-expanded': String(todosOpen),
      onclick: () => { todosOpen = !todosOpen; redraw(); } }, todosOpen ? 'Weniger anzeigen' : `… und ${rest} weitere anzeigen`) : null,
  ];
}

export default {
  title: 'Dashboard',
  render(root) {
    todosOpen = false;
    Promise.allSettled([load.teams(), load.trades(), load.projects(), load.activity(), load.people(), load.events(), load.recap()]);
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
            el('button', { onclick: () => createEvent() }, '+ Event'),
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
            el('h2', { text: todos.length > TODO_SHOWN ? `Zu erledigen (${todos.length})` : 'Zu erledigen' }),
            todoList(todos, draw)),
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
        el('div', { class: 'grid two' }, nextEventsCard(), recapCard()),
        el('section', { class: 'card' },
          el('div', { class: 'row between' }, el('h2', { text: 'Was ist passiert?' }), pill('nur echte Aktionen', 'muted')),
          store.activity.length ? el('ul', { class: 'timeline', id: 'activityList' }, store.activity.slice(0, 12).map(e => el('li', {},
            el('span', { text: e.text }), el('small', { class: 'muted', text: ' · ' + fmtAgo(e.at) }))))
            : el('p', { class: 'muted', text: 'Noch keine Aktivität.' })));
    };
    return bind(root, ['me', 'meta', 'world', 'lands', 'teams', 'trades', 'projects', 'events', 'recap', 'activity', 'players', 'blueMapStatus', 'discovered'], draw);
  },
};
