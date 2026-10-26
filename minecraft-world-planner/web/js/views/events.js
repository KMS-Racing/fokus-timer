// Events mit Kalender: Bauwettbewerb, Treffen, Party … – zusagen mit einem Klick, Erinnerung im Spiel kommt vom Server.
// Alle Zeiten in der Zeitzone des Servers (wie im Spiel-Chat), damit alle dieselbe Uhrzeit sehen.
import { api, enc } from '../api.js';
import { dayKey, fmtDayHead, fmtTime, fmtWhen, monthGrid, monthName, zoned, zonedToEpoch } from '../eventfmt.js';
import { showPoint } from '../map.js';
import { navigate } from '../router.js';
import { load, myTeams, serverNow, store } from '../store.js';
import { act, confirmDialog, dimName, el, empty, fill, fmtLeft, formDialog, pill, plural } from '../util.js';
import { bind, pageHead, segmented } from './common.js';
import { myPosition, navigateButton } from './markers.js';

const state = { view: 'list', filter: 'all', month: null };
const ANSWER_BUTTONS = [['yes', '✔ Komme'], ['maybe', '? Vielleicht'], ['no', '✖ Komme nicht']];
const STATE_PILLS = { running: ['läuft gerade', 'ok'], cancelled: ['abgesagt', 'danger'], past: ['vorbei', 'muted'] };

const tz = () => (store.meta && store.meta.timeZone) || 'Europe/Berlin';
const kindName = k => (store.meta && store.meta.eventKinds && store.meta.eventKinds[k]) || k;
const answerName = a => (store.meta && store.meta.eventAnswers && store.meta.eventAnswers[a]) || a;
const off = () => store.meta && store.meta.gameFeatures && store.meta.gameFeatures.events === false;

function timeRange(e) {
  const sameDay = dayKey(e.start, tz()) === dayKey(e.end - 1, tz());
  return `${fmtWhen(e.start, tz())} – ${sameDay ? fmtTime(e.end, tz()) : fmtWhen(e.end, tz())}`;
}

function counts(e) {
  const c = e.counts || {};
  return [plural(c.yes || 0, 'kommt', 'kommen'), c.maybe ? `${c.maybe} vielleicht` : null].filter(Boolean).join(' · ');
}

async function answer(e, value) {
  const label = value ? answerName(value) : 'Antwort zurückgenommen';
  if (await act(() => api(`api/events/${enc(e.id)}/answer`, { answer: value }), `»${e.title}«: ${label}.`)) await load.events();
}

function answerButtons(e) {
  if (e.state === 'cancelled' || e.state === 'past') return null;
  return el('div', { class: 'row tight answer-row' }, ANSWER_BUTTONS.map(([value, text]) =>
    el('button', { class: 'small' + (e.myAnswer === value ? ' active' : ''), 'aria-pressed': String(e.myAnswer === value), 'data-answer': value,
      onclick: ev => { ev.preventDefault(); answer(e, e.myAnswer === value ? null : value); } }, text)));
}

// ------------------------------------------------------------------ Formular

function eventFields(e = {}) {
  const now = serverNow() + 3600000;
  const startDay = e.start ? dayKey(e.start, tz()) : dayKey(now, tz());
  const startTime = e.start ? fmtTime(e.start, tz()) : `${String(zoned(now, tz()).h).padStart(2, '0')}:00`;
  const teams = myTeams();
  const lands = [...store.lands].sort((a, b) => a.name.localeCompare(b.name));
  const dims = (store.world && store.world.dimensions) || ['minecraft:overworld'];
  const placeType = e.landId ? 'land' : e.place ? 'coords' : 'none';
  const kinds = Object.entries((store.meta && store.meta.eventKinds) || { meet: 'Treffen' });
  return [
    { name: 'title', label: 'Titel', value: e.title || '', required: true, maxLength: 60, autofocus: true, placeholder: 'z. B. Bauwettbewerb: Brücken' },
    { name: 'kind', label: 'Art', type: 'select', value: e.kind || 'meet', options: kinds },
    { name: 'date', label: 'Tag', type: 'date', value: startDay, required: true },
    { name: 'time', label: `Uhrzeit (${tz()})`, type: 'time', value: startTime, required: true },
    { name: 'minutes', label: 'Dauer (Minuten)', type: 'number', value: e.minutes || 60, min: 15, max: 1440, step: 15 },
    { name: 'placeType', label: 'Treffpunkt', type: 'select', value: placeType,
      options: [['none', '— keiner —'], ['land', 'ein Land'], ['coords', 'Koordinaten']] },
    { name: 'landId', label: 'Land', type: 'select', value: e.landId || (lands[0] ? lands[0].id : ''), options: lands.map(l => [l.id, l.name]),
      showIf: v => v.placeType === 'land' },
    { name: 'dimension', label: 'Dimension', type: 'select', value: (e.place && e.place.dimension) || dims[0], options: dims.map(d => [d, dimName(d)]),
      showIf: v => v.placeType === 'coords' },
    { name: 'x', label: 'X', type: 'number', value: e.place ? e.place.x : '', step: 1, showIf: v => v.placeType === 'coords' },
    { name: 'y', label: 'Y (optional)', type: 'number', value: e.place && e.place.y != null ? e.place.y : '', step: 1, showIf: v => v.placeType === 'coords' },
    { name: 'z', label: 'Z', type: 'number', value: e.place ? e.place.z : '', step: 1, showIf: v => v.placeType === 'coords' },
    { name: 'visibility', label: 'Wer sieht es?', type: 'select', value: e.visibility || 'all',
      options: Object.entries((store.meta && store.meta.eventVisibility) || { all: 'Alle Spieler' }),
      help: teams.length ? '„Nur Team“: nur dein Team sieht und bekommt Erinnerungen.' : 'Du bist in keinem Team – „Nur Team“ geht dann nicht.' },
    { name: 'teamId', label: 'Team', type: 'select', value: e.teamId || (teams[0] ? teams[0].id : ''), options: teams.map(t => [t.id, `[${t.tag}] ${t.name}`]),
      showIf: v => v.visibility === 'team' },
    { name: 'description', label: 'Beschreibung', type: 'textarea', value: e.description || '', maxLength: 1000, rows: 4,
      placeholder: 'Was ist geplant? Was soll man mitbringen?' },
  ];
}

/** e = das Event beim Bearbeiten: Bleibt der Beginn gleich, darf er auch schon vorbei sein (Event läuft gerade). */
function validateEvent(v, e = null) {
  if (v.title.length < 2) return 'Der Titel braucht mindestens 2 Zeichen.';
  if (!v.date || !v.time) return 'Bitte Tag und Uhrzeit angeben.';
  const start = zonedToEpoch(v.date, v.time, tz());
  if ((!e || start !== e.start) && start < serverNow() + 60000) return 'Der Beginn muss in der Zukunft liegen.';
  if (!Number.isFinite(v.minutes) || v.minutes < 15 || v.minutes > 1440) return 'Dauer: 15 bis 1440 Minuten.';
  if (v.placeType === 'land' && !v.landId) return 'Bitte ein Land auswählen.';
  if (v.placeType === 'coords' && (!Number.isFinite(v.x) || !Number.isFinite(v.z))) return 'Für den Treffpunkt bitte X und Z angeben.';
  if (v.visibility === 'team' && !v.teamId) return '„Nur Team“ geht nur mit einem Team.';
  return null;
}

function toBody(v) {
  return {
    title: v.title, kind: v.kind, start: zonedToEpoch(v.date, v.time, tz()), minutes: v.minutes, description: v.description,
    visibility: v.visibility, teamId: v.visibility === 'team' ? v.teamId : '',
    landId: v.placeType === 'land' ? v.landId : '',
    place: v.placeType === 'coords' ? { dimension: v.dimension, x: v.x, y: Number.isFinite(v.y) ? v.y : null, z: v.z } : null,
  };
}

const positionTool = { label: '📍 Meine Position', onClick: inputs => {
  const p = myPosition();
  if (!p) return;
  inputs.placeType.value = 'coords';
  inputs.dimension.value = p.dimension;
  inputs.x.value = Math.floor(p.x);
  inputs.y.value = Math.floor(p.y);
  inputs.z.value = Math.floor(p.z);
} };

export async function createEvent() {
  const v = await formDialog({ title: 'Neues Event', fields: eventFields(), validate: validateEvent, submitLabel: 'Einladen',
    intro: 'Alle, die es sehen dürfen, bekommen im Spiel eine Nachricht mit [Komme] – und wer zusagt, eine Erinnerung 10 Minuten vorher.',
    tools: myPosition() ? [positionTool] : [] });
  if (!v) return;
  const r = await act(() => api('api/events', toBody(v)), `»${v.title}« angelegt.`);
  if (r) { await load.events(); navigate('#/events/' + enc(r.id)); }
}

async function editEvent(e) {
  const v = await formDialog({ title: 'Event bearbeiten', fields: eventFields(e), validate: values => validateEvent(values, e),
    intro: e.counts && (e.counts.yes > 1 || e.counts.maybe) ? 'Verschiebst du die Zeit, bekommen alle mit „Komme“ oder „Vielleicht“ im Spiel Bescheid.' : null,
    tools: myPosition() ? [positionTool] : [] });
  if (!v) return;
  if (await act(() => api(`api/events/${enc(e.id)}/edit`, toBody(v)), 'Gespeichert.')) await load.events();
}

async function cancelEvent(e) {
  const v = await formDialog({ title: `»${e.title}« absagen?`, submitLabel: 'Absagen',
    intro: 'Wer zugesagt hat, sieht die Absage im Spiel und auf dem Dashboard. Das Event bleibt sichtbar (als abgesagt).',
    fields: [{ name: 'reason', label: 'Grund (optional)', maxLength: 200, placeholder: 'z. B. Server-Wartung' }] });
  if (!v) return;
  if (await act(() => api(`api/events/${enc(e.id)}/cancel`, { reason: v.reason }), 'Abgesagt.')) await load.events();
}

async function deleteEvent(e) {
  const ok = await confirmDialog({ title: `»${e.title}« löschen?`, danger: true, confirmLabel: 'Löschen',
    text: e.state === 'upcoming' ? 'Tipp: „Absagen“ ist meist besser – dann sehen alle, dass es ausfällt. Löschen entfernt es ganz.' : 'Das Event wird ganz entfernt.' });
  if (!ok) return;
  if (await act(() => api(`api/events/${enc(e.id)}/delete`, {}), 'Gelöscht.')) { await load.events(); navigate('#/events'); }
}

// ------------------------------------------------------------------ Liste

function filtered() {
  return store.events.filter(e => state.filter === 'all' || (state.filter === 'going' && (e.myAnswer === 'yes' || e.myAnswer === 'maybe'))
    || (state.filter === 'mine' && e.mine));
}

function eventItem(e) {
  const sp = STATE_PILLS[e.state];
  return el('a', { class: 'item link-item event-item', href: '#/events/' + enc(e.id), 'data-event': e.id },
    el('div', { class: 'item-top' },
      el('strong', { text: e.title }),
      el('div', { class: 'row tight' }, pill(kindName(e.kind), 'info'), sp ? pill(sp[0], sp[1]) : null,
        e.visibility === 'team' ? pill('Nur Team', 'muted') : null, e.myAnswer ? pill('du: ' + answerName(e.myAnswer), e.myAnswer === 'no' ? 'muted' : 'ok') : null)),
    el('div', { class: 'meta' },
      el('span', { text: timeRange(e) }),
      el('span', { class: 'muted', text: `von ${e.organizer.name} · ${counts(e)}` }),
      e.landName ? el('span', { class: 'muted', text: `Treffpunkt: ${e.landName}` }) : null),
    answerButtons(e));
}

function listView() {
  const now = serverNow();
  const all = filtered();
  const coming = all.filter(e => e.end > now || e.state === 'cancelled' && e.start > now);
  const past = all.filter(e => !coming.includes(e)).sort((a, b) => b.start - a.start).slice(0, 20);
  const groups = new Map();
  for (const e of coming) {
    const key = dayKey(e.start, tz());
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(e);
  }
  const today = dayKey(now, tz()), tomorrow = dayKey(now + 86400000, tz());
  return [
    coming.length ? el('div', { id: 'eventList' }, [...groups].map(([key, list]) => el('section', { class: 'event-day' },
      el('h3', { text: key === today ? `Heute · ${fmtDayHead(key)}` : key === tomorrow ? `Morgen · ${fmtDayHead(key)}` : fmtDayHead(key) }),
      el('div', { class: 'list' }, list.map(eventItem)))))
      : empty(state.filter === 'all' ? 'Noch keine Events geplant. Lade zum ersten ein!' : 'Hier ist gerade nichts.',
        el('button', { class: 'primary', onclick: () => createEvent() }, '+ Neues Event')),
    past.length ? el('details', { class: 'card', id: 'pastEvents' }, el('summary', { text: `Vorbei (${past.length})` }),
      el('div', { class: 'list' }, past.map(eventItem))) : null,
  ];
}

// ------------------------------------------------------------------ Kalender

function calendarView() {
  const now = serverNow();
  if (!state.month) { const t = zoned(now, tz()); state.month = { y: t.y, m: t.m }; }
  const { y, m } = state.month;
  const byDay = new Map();
  for (const e of filtered()) {
    const key = dayKey(e.start, tz());
    if (!byDay.has(key)) byDay.set(key, []);
    byDay.get(key).push(e);
  }
  const today = dayKey(now, tz());
  const move = d => { const n = (y * 12 + (m - 1) + d); state.month = { y: Math.floor(n / 12), m: (n % 12) + 1 }; redraw(); };
  return [
    el('div', { class: 'toolbar cal-toolbar' },
      el('button', { class: 'small', 'aria-label': 'Monat zurück', onclick: () => move(-1) }, '‹'),
      el('strong', { class: 'cal-month', text: monthName(y, m) }),
      el('button', { class: 'small', 'aria-label': 'Monat vor', onclick: () => move(1) }, '›'),
      el('button', { class: 'small', onclick: () => { state.month = null; redraw(); } }, 'Heute')),
    el('div', { class: 'cal', id: 'eventCalendar', role: 'grid', 'aria-label': `Kalender ${monthName(y, m)}` },
      ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'].map(d => el('div', { class: 'cal-head', role: 'columnheader', text: d })),
      monthGrid(y, m).flat().map(d => {
        const list = (byDay.get(d.key) || []).sort((a, b) => a.start - b.start);
        return el('div', { class: 'cal-day' + (d.inMonth ? '' : ' cal-out') + (d.key === today ? ' cal-today' : ''), role: 'gridcell', 'data-day': d.key },
          el('span', { class: 'cal-num', text: String(d.day) }),
          list.slice(0, 3).map(e => el('a', { class: 'cal-chip' + (e.state === 'cancelled' ? ' cancelled' : '') + (e.myAnswer === 'yes' ? ' going' : ''),
            href: '#/events/' + enc(e.id), title: `${fmtTime(e.start, tz())} ${e.title}` },
            el('span', { class: 'cal-time', text: fmtTime(e.start, tz()) }), el('span', { class: 'cal-title', text: ' ' + e.title }))),
          list.length > 3 ? el('span', { class: 'cal-more', text: `+${list.length - 3}` }) : null);
      })),
    el('p', { class: 'muted small', text: `Zeiten in der Zeitzone des Servers (${tz()}). Grün umrandet = du kommst.` }),
  ];
}

// ------------------------------------------------------------------ Einzelnes Event

function detailView(e) {
  const sp = STATE_PILLS[e.state];
  const going = (e.answers || []).filter(a => a.answer === 'yes');
  const maybe = (e.answers || []).filter(a => a.answer === 'maybe');
  const no = (e.answers || []).filter(a => a.answer === 'no');
  const names = list => list.length ? el('ul', { class: 'mini-list' }, list.map(a => el('li', {},
    el('a', { href: '#/statistik/spieler/' + enc(a.uuid), text: a.name }), a.uuid === e.organizer.uuid ? [' ', pill('lädt ein', 'muted')] : null)))
    : el('p', { class: 'muted small', text: 'niemand' });
  const p = e.place;
  return [
    el('a', { class: 'back', href: '#/events', text: '← Alle Events' }),
    pageHead(e.title, `${kindName(e.kind)} · ${timeRange(e)} · lädt ein: ${e.organizer.name}`,
      e.canEdit ? el('button', { class: 'small', onclick: () => editEvent(e) }, 'Bearbeiten') : null,
      e.canEdit ? el('button', { class: 'small', onclick: () => cancelEvent(e) }, 'Absagen') : null,
      e.canDelete ? el('button', { class: 'small danger', onclick: () => deleteEvent(e) }, 'Löschen') : null),
    el('div', { class: 'row tight' }, pill(kindName(e.kind), 'info'), sp ? pill(sp[0], sp[1]) : pill('beginnt in ' + fmtLeft(e.start - serverNow()), 'muted'),
      e.visibility === 'team' ? pill('Nur Team', 'muted') : null),
    e.state === 'cancelled' ? el('div', { class: 'card highlight', id: 'eventCancelled' },
      el('p', { text: `Abgesagt${e.cancelReason ? ': ' + e.cancelReason : '.'}` })) : null,
    e.movedAt && e.state === 'upcoming' ? el('p', { class: 'muted small', text: `Verschoben (vorher ${fmtWhen(e.previousStart, tz())}).` }) : null,
    el('div', { class: 'grid two' },
      el('section', { class: 'card', id: 'eventAnswer' }, el('h2', { text: 'Kommst du?' }),
        e.state === 'cancelled' || e.state === 'past' ? el('p', { class: 'muted', text: e.state === 'past' ? 'Das Event ist vorbei.' : 'Das Event ist abgesagt.' })
          : [answerButtons(e), el('p', { class: 'muted small', text: e.myAnswer && e.myAnswer !== 'no'
            ? 'Im Spiel kommt 10 Minuten vorher eine Erinnerung (außer du hast „/planer nachrichten aus“).'
            : 'Wer „Komme“ oder „Vielleicht“ sagt, bekommt im Spiel 10 Minuten vorher eine Erinnerung.' })]),
      el('section', { class: 'card', id: 'eventPlace' }, el('h2', { text: 'Treffpunkt' }),
        p ? [el('p', { text: e.landName ? `Land »${e.landName}«` : `${dimName(p.dimension)} · X ${p.x}${p.y != null ? ' · Y ' + p.y : ''} · Z ${p.z}` }),
          el('div', { class: 'row tight' },
            el('button', { class: 'small', onclick: () => { navigate('#/karte'); showPoint(p.dimension, p.x + 0.5, p.z + 0.5, 2); } }, 'Auf der Karte'),
            myPosition() && e.state !== 'past' && e.state !== 'cancelled' ? navigateButton(`api/events/${enc(e.id)}/navigate`) : null)]
          : el('p', { class: 'muted', text: 'Kein Treffpunkt angegeben.' }))),
    e.description ? el('section', { class: 'card' }, el('h2', { text: 'Worum geht es?' }), el('p', { class: 'pre-wrap', text: e.description })) : null,
    el('div', { class: 'grid three', id: 'eventPeople' },
      el('section', { class: 'card' }, el('h2', { text: `Kommen (${going.length})` }), names(going)),
      el('section', { class: 'card' }, el('h2', { text: `Vielleicht (${maybe.length})` }), names(maybe)),
      el('section', { class: 'card' }, el('h2', { text: `Kommen nicht (${no.length})` }), names(no))),
  ];
}

// ------------------------------------------------------------------ Seite

let redraw = () => {};

export default {
  title: 'Events',
  render(root, route) {
    const id = route.id;
    const head = el('div'), body = el('div', { class: 'events-page' });
    const draw = () => {
      if (off()) { fill(head); fill(body, empty('Events sind auf diesem Server ausgeschaltet.')); return; }
      if (id) {
        const e = store.events.find(x => x.id === id);
        fill(head);
        fill(body, e ? detailView(e) : store.loaded.events ? empty('Dieses Event gibt es nicht (mehr) – oder du darfst es nicht sehen.') : el('p', { class: 'muted', text: 'Lädt …' }));
        return;
      }
      fill(head, pageHead('Events', 'Bauwettbewerb, Treffen, Party … Zusagen mit einem Klick – wer kommt, wird im Spiel 10 Minuten vorher erinnert.',
        el('button', { class: 'primary', id: 'newEventBtn', onclick: () => createEvent() }, '+ Neues Event')),
      el('div', { class: 'toolbar' },
        segmented([['list', 'Liste'], ['calendar', 'Kalender']], state.view, v => { state.view = v; draw(); }, 'Ansicht'),
        segmented([['all', 'Alle'], ['going', 'Ich komme'], ['mine', 'Von mir']], state.filter, v => { state.filter = v; draw(); }, 'Filter')));
      fill(body, state.view === 'calendar' ? calendarView() : listView());
    };
    redraw = draw;
    fill(root, head, body);
    const unbind = bind(root, ['events', 'meta', 'teams', 'lands', 'players'], draw);
    load.events().catch(e => { fill(body, empty('Events konnten nicht geladen werden: ' + e.message)); });
    const timer = setInterval(() => { if (!document.hidden) load.events().catch(() => {}); }, 30000);
    return () => { clearInterval(timer); unbind(); redraw = () => {}; };
  },
};
