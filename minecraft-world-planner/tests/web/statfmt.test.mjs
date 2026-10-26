// Zahlen der Statistik: gleiche Schreibweise wie im Spiel (StatValues.format) und Tage in der Zeitzone des Servers.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { dayLabel, fmtDuration, fmtValue, lastDays, placeText, serverToday, shortDay } from '../../web/js/statfmt.js';

test('Zeiten wie im Spiel', () => {
  assert.equal(fmtDuration(0), '0 min');
  assert.equal(fmtDuration(59 * 60000), '59 min');
  assert.equal(fmtDuration(3600000), '1 h 0 min');
  assert.equal(fmtDuration((47 * 60 + 5) * 60000), '47 h 5 min');
  assert.equal(fmtDuration((3 * 24 + 5) * 3600000), '3 Tage 5 h');
});

test('Strecke, Schaden und Anzahl', () => {
  assert.equal(fmtValue('distance', 350000), '3,5 km');
  assert.equal(fmtValue('distance', 12000), '120 m');
  assert.equal(fmtValue('distance', 123456789), '1.234,6 km');
  assert.equal(fmtValue('damage', 250), '12,5 ♥', 'Minecraft zählt Schaden in Zehnteln: 20 = ein Herz');
  assert.equal(fmtValue('count', 1234), '1.234');
  assert.equal(fmtValue('time', 7200000), '2 h 0 min');
});

test('„Heute“ in der Zeitzone des Servers', () => {
  const lateEvening = new Date('2026-10-25T23:30:00Z'); // in Berlin schon der 26.
  assert.equal(serverToday('Europe/Berlin', lateEvening), '2026-10-26');
  assert.equal(serverToday('UTC', lateEvening), '2026-10-25');
  assert.equal(serverToday('Kein/Ort', lateEvening), '2026-10-25', 'unbekannte Zone: UTC statt Absturz');
});

test('30 Tage rückwärts, über Monats- und Zeitumstellung hinweg', () => {
  const days = lastDays('2026-11-02', 30);
  assert.equal(days.length, 30);
  assert.equal(days[29], '2026-11-02');
  assert.equal(days[0], '2026-10-04');
  assert.ok(days.includes('2026-10-25'), 'Tag der Zeitumstellung nicht doppelt und nicht ausgelassen');
  assert.equal(new Set(days).size, 30);
  assert.equal(dayLabel('2026-10-19'), 'Mo 19.10.');
  assert.equal(dayLabel('2026-10-25'), 'So 25.10.');
  assert.equal(shortDay('2026-01-05'), '05.01.');
});

test('Platz und Veränderung zur Vorwoche', () => {
  assert.equal(placeText({ place: 2, of: 8 }), 'Platz 2 von 8');
  assert.equal(placeText({ place: 2, of: 8, weekAgo: 5 }), 'Platz 2 von 8 · ↑ 3 seit letzter Woche');
  assert.equal(placeText({ place: 4, of: 8, weekAgo: 3 }), 'Platz 4 von 8 · ↓ 1 seit letzter Woche');
  assert.equal(placeText({ place: 1, of: 8, weekAgo: 1 }), 'Platz 1 von 8');
});
