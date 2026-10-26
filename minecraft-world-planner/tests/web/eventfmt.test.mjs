// Events: Zeiten in der Zeitzone des Servers und "Zu erledigen" mit denselben Texten wie im Spiel (EventsTest.java).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as f from '../../web/js/eventfmt.js';
import { dayKey, eventTodos, fmtDayHead, fmtTime, fmtWhen, inMinutes, monthGrid, zonedToEpoch } from '../../web/js/eventfmt.js';

const BERLIN = 'Europe/Berlin';
// Montag, 26.10.2026, 18:00 in Berlin (Winterzeit, UTC+1) – wie EventsTest.NOW
const NOW = Date.UTC(2026, 9, 26, 17, 0);
const HOUR = 3600000, MIN = 60000;

test('Zeiten wie im Spiel-Chat', () => {
  assert.equal(fmtWhen(NOW, BERLIN), 'Mo 26.10. 18:00');
  assert.equal(fmtWhen(NOW, 'UTC'), 'Mo 26.10. 17:00');
  assert.equal(fmtWhen(Date.UTC(2026, 9, 26, 23, 30), BERLIN), 'Di 27.10. 00:30', 'nach Mitternacht in Berlin: nächster Tag');
  assert.equal(fmtTime(Date.UTC(2026, 6, 1, 10, 5), BERLIN), '12:05', 'Sommerzeit: UTC+2');
  assert.equal(dayKey(Date.UTC(2026, 9, 26, 23, 30), BERLIN), '2026-10-27');
  assert.equal(fmtDayHead('2026-10-28'), 'Mittwoch, 28. Oktober');
  assert.equal(inMinutes(10 * MIN), 'in 10 Minuten');
  assert.equal(inMinutes(1), 'in 1 Minute');
  assert.equal(inMinutes(0), 'gleich');
});

test('Eingabe in Server-Zeit → richtige Millisekunden, auch über die Zeitumstellung', () => {
  assert.equal(zonedToEpoch('2026-10-26', '18:00', BERLIN), NOW);
  assert.equal(zonedToEpoch('2026-07-01', '12:05', BERLIN), Date.UTC(2026, 6, 1, 10, 5));
  assert.equal(zonedToEpoch('2026-10-25', '12:00', BERLIN), Date.UTC(2026, 9, 25, 11, 0), 'Tag der Umstellung, mittags schon Winterzeit');
  assert.equal(zonedToEpoch('2026-10-26', '18:00', 'UTC'), Date.UTC(2026, 9, 26, 18, 0));
});

test('Monatskalender: Montag zuerst, ganze Wochen', () => {
  const weeks = monthGrid(2026, 10); // Oktober 2026 beginnt an einem Donnerstag
  assert.equal(weeks[0][0].key, '2026-09-28');
  assert.equal(weeks[0][3].key, '2026-10-01');
  assert.equal(weeks[0][0].inMonth, false);
  assert.equal(weeks[0][3].inMonth, true);
  assert.equal(weeks.length, 5);
  assert.equal(weeks.at(-1).at(-1).key, '2026-11-01');
  assert.ok(weeks.every(w => w.length === 7));
  assert.equal(monthGrid(2026, 2).length, 5, 'Februar 2026 beginnt an einem Sonntag');
  assert.equal(monthGrid(2027, 2).length, 4, 'Februar 2027 beginnt an einem Montag und hat 28 Tage');
});

const ev = (over = {}) => ({ id: 'e1', title: 'Bauabend', start: NOW + HOUR, end: NOW + 2 * HOUR, state: 'upcoming', mine: false, place: null, ...over });

test('Zu erledigen: dieselben Texte wie im Spiel', () => {
  const place = { place: { dimension: 'minecraft:overworld', x: 100, z: 200 } };
  assert.deepEqual(eventTodos([ev({ myAnswer: 'yes', ...place })], NOW + 50 * MIN, BERLIN),
    [{ kind: 'warn', urgent: true, text: 'In 10 Minuten: »Bauabend« – Treffpunkt: 100 ~ 200', href: '#/events/e1' }], 'urgent = ganz oben, wie im Spiel');
  assert.deepEqual(eventTodos([ev({ myAnswer: 'maybe', ...place })], NOW + HOUR + 2 * MIN, BERLIN).map(t => [t.text, t.urgent]),
    [['Jetzt: »Bauabend« beginnt – Treffpunkt: 100 ~ 200', true]]);
  assert.deepEqual(eventTodos([ev({ myAnswer: 'no', ...place })], NOW + 50 * MIN, BERLIN), [], 'Komme nicht = keine Erinnerung');
  assert.deepEqual(eventTodos([ev({ myAnswer: 'yes', landName: 'Marktplatz' })], NOW + 55 * MIN, BERLIN).map(t => t.text),
    ['In 5 Minuten: »Bauabend« – Treffpunkt: Land »Marktplatz«']);
  assert.deepEqual(eventTodos([ev({ myAnswer: 'yes' })], NOW + HOUR + 11 * MIN, BERLIN), [], 'zehn Minuten nach dem Start ist Schluss');

  const party = ev({ title: 'Party', start: NOW + 2 * 24 * HOUR, end: NOW + 2 * 24 * HOUR + HOUR });
  assert.deepEqual(eventTodos([party], NOW, BERLIN), [{ kind: 'invite', text: 'Event: »Party« am Mi 28.10. 18:00 – kommst du?', href: '#/events/e1' }]);
  assert.deepEqual(eventTodos([{ ...party, mine: true }], NOW, BERLIN), [], 'den Veranstalter nicht fragen');
  assert.deepEqual(eventTodos([{ ...party, start: NOW + 9 * 24 * HOUR, end: NOW + 9 * 24 * HOUR + HOUR }], NOW, BERLIN), [], 'mehr als 7 Tage vorher noch nicht');
  const cancelled = { ...party, state: 'cancelled', cancelReason: 'Regen' };
  assert.deepEqual(eventTodos([{ ...cancelled, myAnswer: 'yes' }], NOW, BERLIN).map(t => t.text), ['Abgesagt: »Party« (Mi 28.10. 18:00) – Regen']);
  assert.deepEqual(eventTodos([{ ...cancelled, myAnswer: 'no' }], NOW, BERLIN), []);
  assert.deepEqual(eventTodos([cancelled], NOW, BERLIN), [], 'abgesagt und nie geantwortet: nicht mehr fragen');
});

test('Nochmal planen: gleiche Uhrzeit nächste Woche, auch über die Zeitumstellung', () => {
  const sat = f.zonedToEpoch('2026-10-24', '20:00', BERLIN); // Sommerzeit
  assert.equal(f.nextWeekSameTime(sat, BERLIN, sat), Date.UTC(2026, 9, 31, 19, 0), 'Sa 31.10. 20:00 Winterzeit – nicht 19:00');
  assert.equal(f.fmtWhen(f.nextWeekSameTime(sat, BERLIN, sat), BERLIN), 'Sa 31.10. 20:00');
  const spring = f.zonedToEpoch('2027-03-27', '20:00', BERLIN);
  assert.equal(f.nextWeekSameTime(spring, BERLIN, spring), Date.UTC(2027, 3, 3, 18, 0), 'Sa 03.04. 20:00 Sommerzeit');
  assert.equal(f.fmtWhen(f.nextWeekSameTime(sat, BERLIN, sat + 20 * 86400000), BERLIN), 'Sa 14.11. 20:00', 'altes Event (20 Tage her): der nächste Samstag, der noch kommt');
});

test('Kalender-Datei: gültig, Zeiten in UTC, Erinnerung 10 Minuten vorher', () => {
  const start = f.zonedToEpoch('2026-10-28', '18:30', BERLIN);
  const title = 'Bauwettbewerb: Brücken, Türme; Burgen \\ und 🏰 – ein sehr langer Titel, der umbrochen werden muss';
  const ics = f.eventIcs({ id: 'e1', title, start, end: start + 90 * 60000, created: 1000, updated: 61000, description: 'Zeile 1\nZeile 2',
    organizer: { name: 'Alice' }, landName: 'Neustadt', place: { dimension: 'minecraft:overworld', x: 120, z: -30 } },
  { now: start - 3600000, url: 'http://planer/#/events/e1', kind: 'Bauwettbewerb', dimLabel: d => (d === 'minecraft:overworld' ? 'Oberwelt' : d) });
  assert.ok(ics.endsWith('\r\n') && !ics.replace(/\r\n/g, '').includes('\n'), 'Zeilenenden CRLF');
  const raw = ics.split('\r\n').slice(0, -1);
  for (const l of raw) assert.ok(new TextEncoder().encode(l).length <= 75, 'höchstens 75 Bytes: ' + l);
  const lines = ics.replace(/\r\n /g, '').split('\r\n'); // Umbrüche wieder zusammensetzen
  assert.ok(lines.includes('DTSTART:20261028T173000Z'), '18:30 Winterzeit = 17:30 UTC');
  assert.ok(lines.includes('DTEND:20261028T190000Z'));
  assert.ok(lines.includes('TRIGGER:-PT10M') && lines.includes('BEGIN:VALARM'));
  assert.ok(lines.includes('SEQUENCE:60'), 'Sekunden zwischen Anlegen und letzter Änderung – neuere Datei ersetzt die alte');
  assert.ok(lines.includes('SUMMARY:Bauwettbewerb: Brücken\\, Türme\\; Burgen \\\\ und 🏰 – ein sehr langer Titel\\, der umbrochen werden muss'), lines.find(l => l.startsWith('SUMMARY')));
  assert.ok(lines.includes('LOCATION:Minecraft: Land »Neustadt« · Oberwelt 120 ~ -30'), lines.find(l => l.startsWith('LOCATION')));
  assert.ok(lines.includes('DESCRIPTION:Zeile 1\\nZeile 2\\n\\nBauwettbewerb · lädt ein: Alice\\n\\nhttp://planer/#/events/e1'));
  assert.deepEqual(lines.filter(l => l.startsWith('BEGIN:')).map(l => l.slice(6)), ['VCALENDAR', 'VEVENT', 'VALARM']);
  assert.deepEqual(lines.filter(l => l.startsWith('END:')).map(l => l.slice(4)), ['VALARM', 'VEVENT', 'VCALENDAR']);
  assert.equal(f.icsFileName('Bau: Brücken/Türme?'), 'Bau BrückenTürme.ics');
  assert.equal(f.icsFileName('???'), 'event.ics');
});

test('Titel-Vorschlag aus Marker- oder Projektnamen: nur erlaubte Zeichen', () => {
  assert.equal(f.eventTitle("Treffen: Bob's Haus <3 – am See"), 'Treffen: Bobs Haus 3 am See', 'wie Validate.name – sonst lehnt der Server ab');
  assert.equal(f.eventTitle('Bauabend: ' + 'Turm '.repeat(20)).length <= 60, true);
  assert.equal(f.eventTitle('Bauabend: Größe (2)! #1'), 'Bauabend: Größe (2)! #1');
});
