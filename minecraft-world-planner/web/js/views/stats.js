// Statistik wie bei CraftAttack: der Server in Zahlen, Ranglisten mit Spaßnamen und eine Seite pro Spieler.
// Alle Zahlen kommen vom Server (Minecrafts eigene Statistik plus das, was der Planer seit der Installation misst).
import { api, enc } from '../api.js';
import { setGameSwitch, store } from '../store.js';
import { act, debounce, el, fill, fmtAgo, fmtDate, fmtDateTime, fmtNum, pill } from '../util.js';
import { barList, chartCard, columns, dataTable, heatLegend, heatmap, hideTip } from '../charts.js';
import { dayLabel, fmtDuration, fmtValue, lastDays, oneDecimal, placeText, serverToday, shortDay, WEEKDAYS } from '../statfmt.js';
import { bind, pageHead, searchInput, segmented } from './common.js';

const RANGES = [['all', 'Gesamt'], ['week', '7 Tage'], ['yesterday', 'Gestern'], ['today', 'Heute']];
const state = { range: 'all', q: '' };
const TILE_KEYS = ['playTime', 'activeTime', 'afkTime', 'deaths', 'mined', 'placed', 'diamonds', 'mobKills', 'distance', 'playerKills'];
const HISTORY = [['playTime', 'Spielzeit'], ['mined', 'Blöcke abgebaut'], ['placed', 'Blöcke platziert'], ['mobKills', 'Mobs besiegt']];
const TOPS = [['mined', 'Meist abgebaut'], ['placed', 'Meist platziert'], ['killed', 'Besiegte Mobs'], ['killedBy', 'Getötet von']];

const unitOf = key => (store.meta && store.meta.statUnits && store.meta.statUnits[key]) || 'count';
const labelOf = key => (store.meta && store.meta.statLabels && store.meta.statLabels[key]) || key;
const fmtKey = (key, v) => fmtValue(unitOf(key), v);
const hours = ms => `${fmtNum(Math.round(ms / 3600000))} h`;

// ------------------------------------------------------------------ Laden

const cache = {};

async function fetchJson(key, path) {
  cache[key] = await api(path);
  return cache[key];
}

function tabs(active) {
  return el('nav', { class: 'tabs', 'aria-label': 'Statistik' },
    [['server', 'Server', '#/statistik'], ['ranglisten', 'Ranglisten', '#/statistik/ranglisten'], ['spieler', 'Spieler', '#/statistik/spieler']]
      .map(([k, t, href]) => el('a', { class: 'tab' + (k === active ? ' active' : ''), href, 'aria-current': k === active ? 'page' : null, text: t })));
}

function sinceText(since) {
  return since ? `Der Planer misst seit ${fmtDate(since)} zusätzlich Zuwachs, Aktiv/AFK, Sitzungen und den Verlauf.` : '';
}

function playerHref(uuid) {
  return '#/statistik/spieler/' + enc(uuid);
}

function eventList(events, withNames) {
  if (!events.length) return el('p', { class: 'muted small', text: 'Noch nichts passiert – Tode und Meilensteine erscheinen hier.' });
  return el('ul', { class: 'mini-list events' }, events.map(e => el('li', {},
    el('span', { class: 'event-icon', 'aria-hidden': 'true', text: e.kind === 'death' ? '☠' : e.kind === 'milestone' ? '★' : 'ℹ' }),
    el('span', {}, withNames ? [el('a', { href: playerHref(e.uuid), text: e.name }), ': '] : null, e.text,
      el('br'), el('small', { class: 'muted', text: fmtDateTime(e.at) })))));
}

// ------------------------------------------------------------------ Server

function serverView(d) {
  const t = d.totals || {};
  const tiles = [
    [hours(t.playTime || 0), 'Spielstunden'], [fmtNum(t.deaths), 'Tode'], [fmtNum(t.mined), 'Blöcke abgebaut'],
    [fmtNum(t.placed), 'Blöcke platziert'], [fmtNum(t.diamonds), 'Diamanten abgebaut'], [fmtNum(t.mobKills), 'Mobs besiegt'],
    [fmtValue('distance', t.distance || 0), 'unterwegs'], [fmtNum(d.players), d.players === 1 ? 'Spieler' : 'Spieler insgesamt'],
    d.online != null ? [fmtNum(d.online), 'gerade online'] : null, [fmtNum(d.today.players), 'heute da'],
  ].filter(Boolean);

  const heat = d.heat || [];
  const heatMax = Math.max(0, ...heat.flat().filter(v => v != null));
  const heatCard = chartCard({
    id: 'statsHeat', title: 'Wann ist am meisten los?',
    subtitle: `Durchschnittlich online, je Wochentag und Stunde (${d.timeZone}). Gezählt wird jede Minute, in der der Server lief.`,
    legend: heatLegend(heatMax ? oneDecimal(heatMax) + ' Spieler' : '', heat.flat().some(v => v == null)),
    chart: heatmap({ values: heat, format: v => `${oneDecimal(v)} online`, ariaLabel: 'Durchschnittlich online je Wochentag und Stunde' }),
    table: () => dataTable(['Tag', ...Array.from({ length: 24 }, (_, h) => `${h}`)],
      heat.map((row, wd) => [WEEKDAYS[wd], ...row.map(v => (v == null ? '–' : oneDecimal(v)))])),
  });

  const today = serverToday(d.timeZone);
  const byDate = Object.fromEntries((d.days || []).map(x => [x.date, x]));
  const dates = lastDays(today, 30);
  const playersPerDay = dates.map(date => ({ label: dayLabel(date), short: shortDay(date), value: byDate[date] ? byDate[date].players : null }));
  const hoursPerDay = dates.map(date => ({ label: dayLabel(date), short: shortDay(date), value: byDate[date] ? byDate[date].minutes / 60 : null }));
  const dayTable = () => dataTable(['Tag', 'Spieler', 'Höchstens gleichzeitig', 'Spielstunden'],
    dates.filter(x => byDate[x]).map(x => [dayLabel(x), fmtNum(byDate[x].players), fmtNum(byDate[x].peak), oneDecimal(byDate[x].minutes / 60)]));

  const r = d.records || {};
  const records = el('dl', { class: 'facts' },
    el('dt', { text: 'Meiste gleichzeitig online' }), el('dd', { text: r.maxOnline ? `${fmtNum(r.maxOnline.count)} (${fmtDateTime(r.maxOnline.at)})` : '—' }),
    el('dt', { text: 'Längste Sitzung' }), el('dd', {}, r.longestSession
      ? [fmtDuration(r.longestSession.ms), ' – ', r.longestSession.name ? el('a', { href: playerHref(r.longestSession.uuid), text: r.longestSession.name }) : 'ausgeblendet',
        ` (${fmtDate(r.longestSession.at)})`] : '—'),
    el('dt', { text: 'Meiste Spieler an einem Tag' }), el('dd', { text: r.busiestDay ? `${fmtNum(r.busiestDay.players)} (${dayLabel(r.busiestDay.date)})` : '—' }),
    el('dt', { text: 'Meiste Spielstunden an einem Tag' }), el('dd', { text: r.mostPlayedDay ? `${oneDecimal(r.mostPlayedDay.minutes / 60)} h (${dayLabel(r.mostPlayedDay.date)})` : '—' }));

  const causes = (d.causes || []).map(c => ({ name: c.label, value: c.count }));
  return [
    el('div', { class: 'stats', id: 'serverTiles' }, tiles.map(([v, text]) => el('div', { class: 'stat' }, el('strong', { text: v }), el('span', { text })))),
    el('div', { class: 'grid two' }, heatCard,
      el('section', { class: 'card', id: 'statsRecords' }, el('h2', { text: 'Rekorde' }), records,
        el('p', { class: 'muted small', text: 'Die Rekorde zählt der Planer seit der Installation.' }))),
    el('div', { class: 'grid two' },
      chartCard({ id: 'statsPlayersPerDay', title: 'Spieler pro Tag', subtitle: 'Wie viele verschiedene Spieler an einem Tag da waren (letzte 30 Tage).',
        chart: columns({ items: playersPerDay, integer: true, format: v => `${fmtNum(v)} Spieler`, ariaLabel: 'Spieler pro Tag, letzte 30 Tage' }), table: dayTable }),
      chartCard({ id: 'statsHoursPerDay', title: 'Spielstunden pro Tag', subtitle: 'Alle Spieler zusammen (letzte 30 Tage).',
        chart: columns({ items: hoursPerDay, format: v => `${oneDecimal(v)} h`, axis: v => `${fmtNum(v)} h`, ariaLabel: 'Spielstunden pro Tag, letzte 30 Tage' }), table: dayTable })),
    el('div', { class: 'grid two' },
      el('section', { class: 'card', id: 'statsCauses' }, el('h2', { text: 'Häufigste Todesursachen' }),
        barList(causes, v => `${fmtNum(v)}×`, 'Seit der Installation des Planers ist noch niemand gestorben.'),
        causes.length ? el('p', { class: 'muted small', text: 'Gezählt seit der Installation des Planers.' }) : null),
      el('section', { class: 'card', id: 'statsFeed' }, el('h2', { text: 'Verlauf' }), eventList(d.events || [], true))),
    el('p', { class: 'muted small', text: (d.hiddenPlayers ? `${fmtNum(d.hiddenPlayers)} Spieler haben sich ausgeblendet – sie fehlen in Summen, Ranglisten und im Verlauf. ` : '')
      + 'Ausblenden kann sich jeder in den Einstellungen oder im Spiel mit /planer ranglisten aus.' }),
  ];
}

// ------------------------------------------------------------------ Ranglisten

function rankingsView(d) {
  const me = store.me || {};
  const cards = d.categories.map(c => {
    const entries = c.entries.map(e => ({ place: e.place, name: e.name, value: e.value, href: playerHref(e.uuid), mine: e.uuid === me.uuid }));
    const mine = c.me ? `Dein Platz: ${c.me.place} von ${c.count} · ${fmtValue(c.unit, c.me.value)}` : d.meHidden ? 'Du bist ausgeblendet.' : 'Du bist hier (noch) nicht dabei.';
    return el('section', { class: 'card rank-card', 'data-category': c.id },
      el('div', { class: 'rank-head' }, el('h2', { text: c.rank }), el('span', { class: 'muted small', text: c.label })),
      c.available ? barList(entries, v => fmtValue(c.unit, v), 'Noch niemand – sei die oder der Erste!')
        : el('p', { class: 'muted small', text: 'Das ist ein Momentwert – den gibt es nur „Gesamt“.' }),
      c.available && c.entries.length ? el('p', { class: 'muted small rank-mine', text: mine }) : null);
  });
  return [
    el('div', { class: 'toolbar' }, segmented(RANGES, state.range, v => { state.range = v; refresh(); }, 'Zeitraum'),
      el('span', { class: 'toolbar-note', text: state.range === 'all' ? 'Gesamt = Minecrafts Zahlen seit dem ersten Login.'
        : `Zuwachs im Zeitraum – gemessen seit ${fmtDate(d.since)}.` })),
    d.meHidden ? el('div', { class: 'card highlight' }, el('p', { text: 'Du bist ausgeblendet und tauchst in keiner Rangliste auf. Wieder sichtbar: in den Einstellungen oder mit /planer ranglisten an.' })) : null,
    el('div', { class: 'rank-grid', id: 'rankGrid' }, cards),
  ];
}

// ------------------------------------------------------------------ Spieler

function playerListView(d) {
  const me = store.me || {};
  const listBox = el('div');
  const drawList = () => {
    const q = state.q.trim().toLowerCase();
    const items = d.players.filter(p => !q || p.name.toLowerCase().includes(q)).sort((a, b) => b.playTime - a.playTime || a.name.localeCompare(b.name));
    fill(listBox, items.length ? el('div', { class: 'list', id: 'statsPlayerList' }, items.map(p => el('a', { class: 'item link-item', href: playerHref(p.uuid) },
      el('div', { class: 'item-top' },
        el('div', { class: 'row tight' }, el('span', { class: 'dot ' + (p.online ? 'online' : 'offline'), 'aria-hidden': 'true' }),
          el('strong', { text: p.name + (p.uuid === me.uuid ? ' (du)' : '') }), p.hidden ? pill('ausgeblendet', 'muted') : null),
        el('span', { class: 'muted small', text: `${fmtDuration(p.playTime)} gespielt · ${fmtNum(p.deaths)} Tode` })))))
      : el('div', { class: 'empty' }, el('p', { text: 'Niemand gefunden.' })));
  };
  drawList();
  return [
    el('div', { class: 'toolbar' }, searchInput(state.q, v => { state.q = v; drawList(); }, 'Spieler suchen …')),
    listBox,
    el('p', { class: 'muted small', text: 'Spieler, die seit der Installation des Planers noch nie online waren, erscheinen nach ihrem nächsten Login.' }),
  ];
}

async function setVisible(on) {
  if (await act(() => setGameSwitch('rankings', on), on ? 'Du bist wieder in Ranglisten und Statistik sichtbar.' : 'Du bist jetzt ausgeblendet.')) refresh();
}

function playerView(d) {
  const me = store.me || {};
  const self = d.uuid === me.uuid;
  const v = d.values || {};
  const today = (d.growth && d.growth.today) || {};
  const cats = (store.meta && store.meta.statCategories) || [];
  const status = d.online ? (!d.onlineSince ? 'online' : Date.now() - d.onlineSince < 60000 ? 'gerade online gekommen'
    : `online seit ${fmtDuration(Date.now() - d.onlineSince)}`)
    : d.lastSeen ? `zuletzt online ${fmtAgo(d.lastSeen)}` : '';

  const tiles = TILE_KEYS.filter(k => k !== 'playerKills' || v[k] > 0).map(k => el('div', { class: 'stat' },
    el('strong', { text: fmtKey(k, v[k] || 0) }), el('span', { text: labelOf(k) }),
    today[k] > 0 ? el('small', { class: 'delta', text: `heute +${fmtKey(k, today[k])}` }) : null));

  const places = d.places || {};
  const rankRows = cats.filter(c => places[c.id]).sort((a, b) => places[a.id].place - places[b.id].place)
    .map(c => el('li', {}, el('strong', { text: c.rank }), el('span', { class: 'muted', text: ` ${c.label} · ` }), placeText(places[c.id])));

  // Nur Zeilen mit Zuwachs (und immer die Spielzeit) – sonst wäre es eine lange Liste voller Nullen
  const ranges = ['today', 'yesterday', 'week'];
  const grown = cats.filter(c => c.growth && (c.key === 'playTime' || ranges.some(r => ((d.growth[r] || {})[c.key] || 0) > 0)));
  const growthTable = dataTable(['', 'Heute', 'Gestern', '7 Tage'], grown.map(c => [c.label,
    ...ranges.map(r => fmtValue(c.unit, (d.growth[r] || {})[c.key] || 0))]));

  const heat = d.heat || [];
  const heatMax = Math.max(0, ...heat.flat());
  const perHour = Object.entries(d.perHour || {}).filter(([, x]) => x > 0);
  const s = d.sessions || {};

  const history = d.history || [];
  // Zeit als Stunden zeichnen – so bekommt die Achse runde Werte (0 / 1 / 2 h statt krummer Millisekunden)
  const historyCharts = HISTORY.map(([key, title]) => {
    const unit = unitOf(key), time = unit === 'time';
    const items = history.map(h => ({ label: dayLabel(h.date), short: shortDay(h.date), value: h[key] == null ? null : time ? h[key] / 3600000 : h[key] }));
    return chartCard({ title, subtitle: 'pro Tag, letzte 30 Tage',
      chart: columns({ items, height: 120, integer: unit === 'count', format: x => (time ? fmtDuration(x * 3600000) : fmtValue(unit, x)),
        axis: time ? x => `${fmtNum(x)} h` : undefined, ariaLabel: `${title} pro Tag, letzte 30 Tage` }),
      table: () => dataTable(['Tag', title], history.map(h => [dayLabel(h.date), h[key] == null ? 'vor der Messung' : fmtValue(unit, h[key])])) });
  });

  const tops = d.tops || {};
  const topCard = ([kind, title]) => el('section', { class: 'card' }, el('h2', { text: title }),
    barList((tops[kind] || []).slice(0, 10).map(x => ({ name: x.name, value: x.count })), x => fmtNum(x), 'Noch nichts.'));

  return [
    el('a', { class: 'back', href: '#/statistik/spieler', text: '← Alle Spieler' }),
    pageHead(d.name + (self ? ' (du)' : ''), status,
      self && !d.hidden ? el('button', { class: 'small', id: 'hideMeBtn', onclick: () => setVisible(false) }, 'Mich ausblenden') : null),
    d.hidden ? el('div', { class: 'card highlight', id: 'hiddenNote' },
      el('p', { text: 'Du bist ausgeblendet: Nur du siehst diese Seite, und du fehlst in allen Ranglisten. Gezählt wird trotzdem weiter.' }),
      el('button', { class: 'primary', onclick: () => setVisible(true) }, 'Wieder sichtbar machen')) : null,
    el('div', { class: 'stats', id: 'playerTiles' }, tiles),
    el('div', { class: 'grid two' },
      el('section', { class: 'card', id: 'playerRanks' }, el('h2', { text: 'Ränge' }),
        rankRows.length ? el('ul', { class: 'mini-list' }, rankRows) : el('p', { class: 'muted small', text: d.hidden ? 'Ausgeblendet – keine Ränge.' : 'Noch in keiner Rangliste.' })),
      el('section', { class: 'card', id: 'playerGrowth' }, el('h2', { text: 'Zuwachs' }), growthTable,
        el('p', { class: 'muted small', text: `Gemessen seit ${fmtDate(d.firstSeen)}.` }))),
    el('div', { class: 'grid two' },
      chartCard({ id: 'playerHeat', title: 'Wann online?', subtitle: `Minuten online je Wochentag und Stunde (${d.timeZone}), seit ${fmtDate(d.firstSeen)}.`,
        legend: heatLegend(heatMax ? fmtDuration(heatMax * 60000) : '', false),
        chart: heatmap({ values: heat, format: m => fmtDuration(m * 60000), ariaLabel: 'Minuten online je Wochentag und Stunde' }),
        table: () => dataTable(['Tag', ...Array.from({ length: 24 }, (_, h) => `${h}`)], heat.map((row, wd) => [WEEKDAYS[wd], ...row.map(m => fmtNum(m))])) }),
      el('section', { class: 'card', id: 'playerFacts' }, el('h2', { text: 'Pro Online-Stunde' }),
        perHour.length ? el('dl', { class: 'facts' }, perHour.map(([k, x]) => [el('dt', { text: labelOf(k) }),
          el('dd', { text: unitOf(k) === 'count' ? oneDecimal(x) : fmtKey(k, x) })]))
          : el('p', { class: 'muted small', text: (v.playTime || 0) < 3600000 ? 'Gibt es ab einer Stunde Spielzeit.' : 'Noch nichts.' }),
        el('h3', { text: 'Sitzungen' }),
        el('dl', { class: 'facts' },
          el('dt', { text: 'Anzahl' }), el('dd', { text: fmtNum(s.count) }),
          el('dt', { text: 'Längste' }), el('dd', { text: s.longest ? fmtDuration(s.longest) : '—' }),
          el('dt', { text: 'Durchschnitt' }), el('dd', { text: s.average ? fmtDuration(s.average) : '—' })))),
    el('h2', { class: 'section-title', text: 'Verlauf' }),
    el('div', { class: 'grid two small-multiples' }, historyCharts),
    el('div', { class: 'grid two' }, TOPS.slice(0, 2).map(topCard)),
    el('div', { class: 'grid two' }, TOPS.slice(2).map(topCard)),
    el('div', { class: 'grid two' },
      el('section', { class: 'card' }, el('h2', { text: 'Todesursachen' }),
        barList((d.causes || []).map(c => ({ name: c.label, value: c.count })), x => `${fmtNum(x)}×`, 'Seit der Installation nicht gestorben.'),
        el('p', { class: 'muted small', text: '„Getötet von“ oben ist Minecrafts eigene Zählung, die Todesursachen zählt der Planer seit der Installation.' })),
      el('section', { class: 'card', id: 'playerEvents' }, el('h2', { text: 'Tode und Meilensteine' }), eventList(d.events || [], false))),
    el('p', { class: 'muted small', text: `Spielzeit, Tode, Blöcke usw. zählt Minecraft selbst (wie unter Esc → Statistiken). Aktiv/AFK, „Wann online“, Sitzungen und Verlauf misst der Planer seit ${fmtDate(d.since)}. AFK = ${d.afkMinutes} Minuten ohne Bewegung und ohne Umschauen; Bewegung zählt immer, auch im Boot oder vom Wasser geschoben.` }),
  ];
}

// ------------------------------------------------------------------ Seite

let redraw = () => {};
let refresh = () => {};

export default {
  title: 'Statistik',
  render(root, route) {
    const tab = route.path[1] === 'ranglisten' ? 'ranglisten' : route.path[1] === 'spieler' ? 'spieler' : 'server';
    const playerId = tab === 'spieler' ? route.path[2] || null : null;
    const head = el('div'), body = el('div', { class: 'stats-page' });
    let data = null, error = null, loading = false;

    const path = () => (tab === 'server' ? 'api/stats/server' : tab === 'ranglisten' ? `api/stats/rankings?range=${state.range}`
      : playerId ? `api/stats/players/${enc(playerId)}` : 'api/stats/players');

    const draw = () => {
      const off = store.meta && store.meta.gameFeatures && store.meta.gameFeatures.statistics === false;
      fill(head, playerId ? null : pageHead('Statistik', 'Wie bei CraftAttack – mit Minecrafts eigenen Zahlen. ' + sinceText(data && data.since)), playerId ? null : tabs(tab));
      if (off) { fill(body, el('div', { class: 'empty' }, el('p', { text: 'Die Statistik ist auf diesem Server ausgeschaltet.' }))); return; }
      if (error) { fill(body, el('div', { class: 'empty' }, el('p', { text: error }))); return; }
      if (!data) { fill(body, el('p', { class: 'muted', text: 'Lädt …' })); return; }
      hideTip();
      fill(body, tab === 'server' ? serverView(data) : tab === 'ranglisten' ? rankingsView(data) : playerId ? playerView(data) : playerListView(data));
    };
    redraw = draw;
    // Beim Nachladen bleibt die alte Ansicht stehen (leicht abgeblendet) – kein Flackern
    refresh = async () => {
      if (loading) return;
      loading = true;
      body.classList.add('refreshing');
      try {
        data = await fetchJson(tab, path());
        error = null;
      } catch (e) {
        error = e.message;
      } finally {
        loading = false;
        body.classList.remove('refreshing');
        const a = document.activeElement;
        // Tippt jemand gerade (Suche), erst nach dem Verlassen des Felds neu zeichnen – sonst wäre die Eingabe weg
        if (a && a.tagName === 'INPUT' && body.contains(a)) a.addEventListener('blur', () => { if (body.isConnected) draw(); }, { once: true });
        else if (body.isConnected) draw();
      }
    };
    fill(root, head, body);
    const unbind = bind(root, ['meta', 'me'], draw);
    refresh();
    const timer = setInterval(() => { if (!document.hidden) refresh(); }, 60000);
    // Die Zeichnungen richten sich nach der Breite – nach dem Drehen des Handys neu zeichnen
    let lastWidth = window.innerWidth;
    const onResize = debounce(() => { if (window.innerWidth !== lastWidth) { lastWidth = window.innerWidth; draw(); } }, 250);
    window.addEventListener('resize', onResize);
    return () => { clearInterval(timer); window.removeEventListener('resize', onResize); unbind(); hideTip(); redraw = () => {}; refresh = () => {}; };
  },
};
