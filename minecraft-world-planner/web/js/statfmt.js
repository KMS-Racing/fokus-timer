// Zahlen und Tage für die Statistik – genauso formatiert wie im Spiel (StatValues.format auf dem Server).
import { fmtNum } from './util.js';

export const WEEKDAYS = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];

export function fmtDuration(ms) {
  const min = Math.floor((ms || 0) / 60000);
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  if (h < 48) return `${h} h ${min % 60} min`;
  return `${fmtNum(Math.floor(h / 24))} Tage ${h % 24} h`;
}

export const oneDecimal = v => v.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** Zeit in ms, Strecke in cm, Schaden in Minecrafts Einheit (Zehntel-Lebenspunkte: 20 = ein Herz). */
export function fmtValue(unit, v) {
  switch (unit) {
    case 'time': return fmtDuration(v);
    case 'distance': return v >= 100000 ? `${oneDecimal(v / 100000)} km` : `${fmtNum(Math.round(v / 100))} m`;
    case 'damage': return `${oneDecimal(v / 20)} ♥`;
    default: return fmtNum(v);
  }
}

/** Heutiges Datum (JJJJ-MM-TT) in der Zeitzone des Servers – „Heute“ soll auf der Webseite dasselbe heißen wie im Spiel. */
export function serverToday(timeZone, now = new Date()) {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
  } catch {
    return now.toISOString().slice(0, 10);
  }
}

/** Die letzten count Tage bis einschließlich today, ältester zuerst. */
export function lastDays(today, count) {
  const out = [];
  const base = new Date(today + 'T12:00:00Z');
  for (let i = count - 1; i >= 0; i--) out.push(new Date(base.getTime() - i * 86400000).toISOString().slice(0, 10));
  return out;
}

/** "2026-10-19" → "Mo 19.10." */
export function dayLabel(date) {
  const d = new Date(date + 'T12:00:00Z');
  return `${WEEKDAYS[(d.getUTCDay() + 6) % 7]} ${date.slice(8, 10)}.${date.slice(5, 7)}.`;
}

export const shortDay = date => `${date.slice(8, 10)}.${date.slice(5, 7)}.`;

/** Platz und Veränderung zur Vorwoche: "Platz 2 von 8 · ↑ 3 seit letzter Woche". */
export function placeText(p) {
  let text = `Platz ${p.place} von ${p.of}`;
  if (p.weekAgo != null && p.weekAgo !== p.place) {
    text += p.weekAgo > p.place ? ` · ↑ ${p.weekAgo - p.place} seit letzter Woche` : ` · ↓ ${p.place - p.weekAgo} seit letzter Woche`;
  }
  return text;
}
