// Events: Zeiten und "Zu erledigen" – ohne DOM, damit es auch in Node getestet werden kann.
// Alle Zeiten gelten in der Zeitzone des Servers (wie im Spiel-Chat), damit alle dieselbe Uhrzeit sehen.

const WEEKDAYS = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
const MONTHS = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];
const EN_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
/** Dieselben Grenzen wie EventStore.java */
export const SOON_MS = 10 * 60000, JUST_STARTED_MS = 10 * 60000, ASK_AHEAD_MS = 7 * 24 * 3600000;

const pad = n => String(n).padStart(2, '0');
const formats = new Map();

/** Jahr, Monat, Tag, Stunde, Minute und Wochentag (Mo = 0) in der Zeitzone tz. */
export function zoned(ms, tz) {
  if (!formats.has(tz)) {
    formats.set(tz, new Intl.DateTimeFormat('en-GB', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', weekday: 'short', hourCycle: 'h23' }));
  }
  const o = {};
  for (const p of formats.get(tz).formatToParts(new Date(ms))) o[p.type] = p.value;
  return { y: +o.year, m: +o.month, d: +o.day, h: +o.hour % 24, min: +o.minute, wd: EN_DAYS.indexOf(o.weekday) };
}

/** "Mi 28.10. 18:00" – genau wie im Spiel (EventStore.when). */
export function fmtWhen(ms, tz) {
  const t = zoned(ms, tz);
  return `${WEEKDAYS[t.wd]} ${pad(t.d)}.${pad(t.m)}. ${pad(t.h)}:${pad(t.min)}`;
}

export function fmtTime(ms, tz) {
  const t = zoned(ms, tz);
  return `${pad(t.h)}:${pad(t.min)}`;
}

/** "JJJJ-MM-TT" des Tages, an dem ms in der Zeitzone tz liegt. */
export function dayKey(ms, tz) {
  const t = zoned(ms, tz);
  return `${t.y}-${pad(t.m)}-${pad(t.d)}`;
}

/** "Mittwoch, 28. Oktober" für eine Tages-Überschrift. */
export function fmtDayHead(key) {
  const [y, m, d] = key.split('-').map(Number);
  const wd = (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7;
  const long = ['Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag', 'Sonntag'][wd];
  return `${long}, ${d}. ${MONTHS[m - 1]}`;
}

export function monthName(y, m) {
  return `${MONTHS[m - 1]} ${y}`;
}

/** Datum ("JJJJ-MM-TT") und Uhrzeit ("HH:MM") in der Zeitzone tz → Millisekunden. */
export function zonedToEpoch(date, time, tz) {
  const [y, m, d] = date.split('-').map(Number);
  const [h, min] = time.split(':').map(Number);
  const wanted = Date.UTC(y, m - 1, d, h, min);
  let guess = wanted;
  for (let i = 0; i < 3; i++) {
    const t = zoned(guess, tz);
    guess += wanted - Date.UTC(t.y, t.m - 1, t.d, t.h, t.min);
  }
  return guess;
}

/** Wochen (Montag zuerst) eines Monats als Tages-Schlüssel; inMonth = gehört zum Monat. */
export function monthGrid(y, m) {
  const first = Date.UTC(y, m - 1, 1);
  const lead = (new Date(first).getUTCDay() + 6) % 7;
  const days = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const weeks = [];
  for (let start = -lead; start < days; start += 7) {
    const week = [];
    for (let i = 0; i < 7; i++) {
      const t = new Date(first + (start + i) * 86400000);
      week.push({ key: `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`, day: t.getUTCDate(), inMonth: t.getUTCMonth() === m - 1 });
    }
    weeks.push(week);
  }
  return weeks;
}

/** "in 7 Minuten", "in 1 Minute", "gleich" – wie EventStore.inMinutes. */
export function inMinutes(ms) {
  const min = Math.floor((ms + 59999) / 60000);
  if (min <= 0) return 'gleich';
  return `in ${min} ${min === 1 ? 'Minute' : 'Minuten'}`;
}

function where(e) {
  if (e.landName) return ` – Treffpunkt: Land »${e.landName}«`;
  if (e.place) return ` – Treffpunkt: ${e.place.x} ${e.place.y ?? '~'} ${e.place.z}`;
  return '';
}

/** "Zu erledigen" für Events – dieselben Regeln und Texte wie Notices.java (ohne "verschoben", das ist nur eine Nachricht). */
export function eventTodos(events, now, tz) {
  const out = [];
  for (const e of events) {
    if (e.end + JUST_STARTED_MS < now) continue;
    const going = e.myAnswer === 'yes' || e.myAnswer === 'maybe';
    const until = e.start - now;
    const href = '#/events/' + encodeURIComponent(e.id);
    const title = `»${e.title}«`;
    if (e.state === 'cancelled') {
      if (going && until > 0) out.push({ kind: 'warn', text: `Abgesagt: ${title} (${fmtWhen(e.start, tz)})${e.cancelReason ? ' – ' + e.cancelReason : ''}`, href });
      continue;
    }
    if (going && until > 0 && until <= SOON_MS) {
      const inText = inMinutes(until);
      out.push({ kind: 'warn', urgent: true, text: `${inText[0].toUpperCase()}${inText.slice(1)}: ${title}${where(e)}`, href });
    } else if (going && until <= 0 && -until < JUST_STARTED_MS) {
      out.push({ kind: 'info', urgent: true, text: `Jetzt: ${title} beginnt${where(e)}`, href });
    } else if (!e.myAnswer && !e.mine && until > 0 && until <= ASK_AHEAD_MS) {
      out.push({ kind: 'invite', text: `Event: ${title} am ${fmtWhen(e.start, tz)} – kommst du?`, href });
    }
  }
  return out;
}

/**
 * Dieselbe Uhrzeit (Server-Zeit) eine Woche später – oder so viele Wochen, bis es nach `after` liegt.
 * Nicht einfach + 7 × 24 Stunden: Über die Zeitumstellung würde aus 20:00 sonst 19:00 oder 21:00.
 */
export function nextWeekSameTime(ms, tz, after) {
  const t = zoned(ms, tz);
  for (let w = 1; w < 600; w++) {
    const d = new Date(Date.UTC(t.y, t.m - 1, t.d + 7 * w));
    const s = zonedToEpoch(`${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`, `${pad(t.h)}:${pad(t.min)}`, tz);
    if (s > after) return s;
  }
  return null;
}

/** Vorschlag für einen Titel aus einem Marker- oder Projektnamen: nur Zeichen, die der Server erlaubt (Validate.name), höchstens 60. */
export function eventTitle(text) {
  return String(text).replace(/[^\p{L}\p{N} _\-.!?+()#:]/gu, '').replace(/\s+/g, ' ').trim().slice(0, 60).trim();
}

// ------------------------------------------------------------------ Kalender-Datei (.ics, RFC 5545)

const icsTime = ms => new Date(ms).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const icsText = s => String(s).replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');
const utf8 = new TextEncoder();

/** Zeilen länger als 75 Bytes umbrechen (Folgezeile beginnt mit Leerzeichen) – nie mitten in einem Umlaut oder Emoji. */
function fold(line) {
  const parts = [];
  let cur = '', bytes = 0;
  for (const ch of line) {
    const n = utf8.encode(ch).length;
    if (bytes + n > (parts.length ? 74 : 75)) { parts.push(cur); cur = ''; bytes = 0; }
    cur += ch;
    bytes += n;
  }
  parts.push(cur);
  return parts.join('\r\n ');
}

/** "Land »Neustadt« · Oberwelt 120 ~ -30" – wo im Spiel. dimLabel macht aus "minecraft:overworld" einen Namen. */
export function placeText(e, dimLabel = d => d) {
  const p = e.place;
  const coords = p ? `${dimLabel(p.dimension)} ${p.x} ${p.y ?? '~'} ${p.z}` : '';
  if (e.landName) return `Land »${e.landName}«` + (coords ? ` · ${coords}` : '');
  return coords;
}

/**
 * Ein Event als Kalender-Datei für Handy oder PC – mit Erinnerung 10 Minuten vorher, auch wenn man nicht im Spiel ist.
 * Zeiten in UTC (…Z), damit jeder Kalender sie richtig in seine Zeitzone umrechnet. Nichts wird hochgeladen.
 */
export function eventIcs(e, { now, url = '', kind = '', dimLabel } = {}) {
  const where = placeText(e, dimLabel);
  const about = [e.description, `${kind ? kind + ' · ' : ''}lädt ein: ${e.organizer ? e.organizer.name : '?'}`, url].filter(Boolean).join('\n\n');
  const lines = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//World Planner//Minecraft-Events//DE', 'CALSCALE:GREGORIAN', 'METHOD:PUBLISH',
    'BEGIN:VEVENT',
    `UID:${e.id}@world-planner`,
    `DTSTAMP:${icsTime(now)}`,
    `DTSTART:${icsTime(e.start)}`,
    `DTEND:${icsTime(e.end)}`,
    `SEQUENCE:${Math.max(0, Math.floor(((e.updated || 0) - (e.created || 0)) / 1000))}`,
    `SUMMARY:${icsText(e.title)}`,
    `LOCATION:${icsText(where ? 'Minecraft: ' + where : 'Minecraft')}`,
    `DESCRIPTION:${icsText(about)}`,
    url ? `URL:${url}` : null,
    'BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:${icsText(e.title)}`, 'TRIGGER:-PT10M', 'END:VALARM',
    'END:VEVENT', 'END:VCALENDAR',
  ].filter(Boolean);
  return lines.map(fold).join('\r\n') + '\r\n';
}

/** Dateiname ohne Zeichen, die Windows oder Handys stören. */
export function icsFileName(title) {
  return (String(title).replace(/[^\p{L}\p{N} _-]/gu, '').trim().slice(0, 60) || 'event') + '.ics';
}
