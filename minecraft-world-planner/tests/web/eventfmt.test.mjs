// Events: Zeiten in der Zeitzone des Servers und "Zu erledigen" mit denselben Texten wie im Spiel (EventsTest.java).
import assert from 'node:assert/strict';
import { test } from 'node:test';
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
    [{ kind: 'warn', text: 'In 10 Minuten: »Bauabend« – Treffpunkt: 100 ~ 200', href: '#/events/e1' }]);
  assert.deepEqual(eventTodos([ev({ myAnswer: 'maybe', ...place })], NOW + HOUR + 2 * MIN, BERLIN).map(t => t.text),
    ['Jetzt: »Bauabend« beginnt – Treffpunkt: 100 ~ 200']);
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
