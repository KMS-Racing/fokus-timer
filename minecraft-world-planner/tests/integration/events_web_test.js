// Browser-Test der Events (0.6): anlegen über das Formular, Liste, Kalender, Detail, Zusagen, Verschieben, Absagen, Löschen –
// und das Dashboard (Nächste Events, Rückblick wie die API, „Zu erledigen“ mit demselben Text wie /api/todo im Spiel), Handy.
// Aufruf: node events_web_test.js <code-A> <code-B>
'use strict';
const fs = require('fs');
const path = require('path');
const { launch, makeChecker, collectProblems, login, todoText, waitUntil } = require('./lib');

const [codeA, codeB] = process.argv.slice(2);
if (!codeB) { console.error('Aufruf: node events_web_test.js <code-A> <code-B>'); process.exit(2); }
const SHOTS = process.env.SHOT_DIR || null;
const run = Math.random().toString(36).slice(2, 6).toUpperCase();

async function dialogSubmit(page) {
  await page.click('#modal button.primary[type=submit], #modal button.danger[type=submit]');
  await page.waitForSelector('#modal', { state: 'hidden' });
}

const hash = page => page.evaluate(() => location.hash);
const eventApi = (page, id) => page.evaluate(i => planner.api('api/events/' + i), id);
/** Alle Felder des offenen Formulars als { name: Wert }. */
const formValues = page => page.evaluate(() => Object.fromEntries([...document.querySelectorAll('#modal [name]')].map(x => [x.name, x.value])));
async function closeDialog(page) {
  await page.click('#modal button:has-text("Abbrechen")');
  await page.waitForSelector('#modal', { state: 'hidden' });
}

(async () => {
  const { check, summary } = makeChecker();
  const browser = await launch();
  const ctxA = await browser.newContext({ viewport: { width: 1400, height: 950 } });
  const page = await ctxA.newPage();
  const problems = collectProblems(page);
  await login(page, codeA);
  // Test-Events früherer Läufe aufräumen (nur eigene, Titel beginnt mit „Test-“)
  await page.evaluate(async () => {
    for (const e of (await planner.api('api/events')).events) if (e.mine && e.title.startsWith('Test-')) await planner.api(`api/events/${e.id}/delete`, {});
    await planner.load.events();
  });

  // 1) Reiter und Formular
  await page.click('#sideNav .nav-link[data-route=events]');
  await page.waitForSelector('#newEventBtn');
  check('Reiter „Events“ führt auf #/events', (await hash(page)) === '#/events', await hash(page));
  const at = await page.evaluate(async () => {
    const f = await import('./js/eventfmt.js');
    const tz = planner.store.meta.timeZone;
    const date = f.dayKey(Date.now() + 2 * 86400000, tz);
    const start = f.zonedToEpoch(date, '18:30', tz);
    return { tz, date, start, when: f.fmtWhen(start, tz), later: f.fmtWhen(start + 3600000, tz), month: date.slice(0, 7), nowMonth: f.dayKey(Date.now(), tz).slice(0, 7),
      dayHead: f.fmtDayHead(date) };
  });
  const title = 'Test-Bauwettbewerb ' + run;
  await page.click('#newEventBtn');
  await page.waitForSelector('#modal input[name=title]');
  check('Formular: Uhrzeit in der Zeitzone des Servers', (await page.textContent('#modal')).includes(`Uhrzeit (${at.tz})`));
  await page.fill('#modal input[name=title]', title);
  await page.selectOption('#modal select[name=kind]', 'build');
  await page.fill('#modal input[name=date]', at.date);
  await page.fill('#modal input[name=time]', '18:30');
  await page.fill('#modal input[name=minutes]', '120');
  await page.selectOption('#modal select[name=placeType]', 'coords');
  await page.fill('#modal input[name=x]', '100');
  await page.fill('#modal input[name=y]', '70');
  await page.fill('#modal input[name=z]', '-50');
  await page.fill('#modal textarea[name=description]', 'Thema: Brücken. Material bringt jeder selbst mit.');
  await dialogSubmit(page);
  await waitUntil(async () => (await hash(page)).startsWith('#/events/'));
  const id = (await hash(page)).split('/')[2];
  await page.waitForSelector('#eventAnswer');
  let e = await eventApi(page, id);
  check('Gespeichert: Beginn genau 18:30 Server-Zeit', e.start === at.start, `${e.start} vs ${at.start}`);
  check('…120 Minuten, Bauwettbewerb, Treffpunkt 100 70 -50', e.minutes === 120 && e.kind === 'build' && e.place.x === 100 && e.place.y === 70 && e.place.z === -50, JSON.stringify(e.place));
  check('Detailseite: Titel und Zeit wie im Spiel', (await page.textContent('#viewRoot h1')) === title && (await page.textContent('#viewRoot')).includes(at.when),
    await page.textContent('#viewRoot .page-head'));
  check('Wer einlädt, kommt', e.myAnswer === 'yes' && (await page.textContent('#eventPeople')).includes('Kommen (1)'));

  // 2) Zusagen: umschalten und zurücknehmen
  await page.click('#eventAnswer button[data-answer=maybe]');
  await waitUntil(async () => (await eventApi(page, id)).myAnswer === 'maybe');
  check('„Vielleicht“ angeklickt', true);
  await page.waitForSelector('#eventAnswer button[data-answer=maybe].active');
  await page.click('#eventAnswer button[data-answer=maybe]');
  await waitUntil(async () => !('myAnswer' in (await eventApi(page, id))));
  check('Nochmal angeklickt = Antwort zurückgenommen', true);
  await page.waitForSelector('#eventAnswer button[data-answer=yes]:not(.active)');
  await page.click('#eventAnswer button[data-answer=yes]');
  await waitUntil(async () => (await eventApi(page, id)).myAnswer === 'yes');

  // 3) Liste und Kalender
  await page.evaluate(() => planner.navigate('#/events'));
  await page.waitForSelector(`#eventList .event-item[data-event="${id}"]`);
  const dayHead = await page.locator('.event-day', { has: page.locator(`[data-event="${id}"]`) }).locator('h3').textContent();
  check('Liste: unter dem richtigen Tag', dayHead.endsWith(at.dayHead), `${dayHead} vs ${at.dayHead}`);
  await page.click('.segmented button:has-text("Kalender")');
  await page.waitForSelector('#eventCalendar');
  if (at.month !== at.nowMonth) await page.click('button[aria-label="Monat vor"]');
  const chip = await page.$eval(`#eventCalendar .cal-day[data-day="${at.date}"] .cal-chip`, c => c.textContent).catch(() => null);
  check('Kalender: Eintrag am richtigen Tag mit Uhrzeit', chip === `18:30 ${title}`, chip);
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'events-kalender.png'), fullPage: true });
  await page.click('.segmented button:has-text("Liste")');

  // 3b) Kalender-Datei fürs Handy, „Nochmal planen“, „Event hier planen“ von Land, Marker und Projekt (0.6.1)
  await page.evaluate(i => planner.navigate('#/events/' + i), id);
  await page.waitForSelector('#eventIcs');
  const [dl] = await Promise.all([page.waitForEvent('download'), page.click('#eventIcs')]);
  const ics = fs.readFileSync(await dl.path(), 'utf8');
  const icsLines = ics.replace(/\r\n /g, '').split('\r\n');
  const utc = new Date(at.start).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  check('Kalender-Datei: Dateiname, Beginn in UTC, Titel, Erinnerung 10 Minuten vorher', dl.suggestedFilename() === `${title}.ics`
    && icsLines.includes(`DTSTART:${utc}`) && icsLines.includes(`SUMMARY:${title}`) && icsLines.includes('TRIGGER:-PT10M') && ics.endsWith('\r\n'),
  dl.suggestedFilename() + '\n' + ics.slice(0, 500));
  check('…mit Treffpunkt und Link zurück zur Webseite', icsLines.includes('LOCATION:Minecraft: Oberwelt 100 70 -50')
    && icsLines.some(l => l.startsWith('URL:') && l.endsWith('#/events/' + id)), ics);

  await page.click('#eventAgain');
  await page.waitForSelector('#modal input[name=title]');
  const again = await page.evaluate(async s => {
    const f = await import('./js/eventfmt.js');
    const tz = planner.store.meta.timeZone;
    const n = f.nextWeekSameTime(s, tz, Date.now() + 60000);
    return { date: f.dayKey(n, tz), start: n };
  }, at.start);
  let form = await formValues(page);
  check('Nochmal planen: dasselbe Event eine Woche später zur selben Uhrzeit', form.title === title && form.date === again.date && form.time === '18:30'
    && form.minutes === '120' && form.kind === 'build' && form.placeType === 'coords' && form.x === '100' && form.y === '70' && form.z === '-50', JSON.stringify(form));
  await dialogSubmit(page);
  await waitUntil(async () => { const h = await hash(page); return h.startsWith('#/events/') && h !== '#/events/' + id; });
  const id2 = (await hash(page)).split('/')[2];
  const e2 = await eventApi(page, id2);
  check('…angelegt: neuer Beginn, Zusagen nicht mitkopiert (nur Alice)', e2.start === again.start && e2.counts.yes === 1 && e2.counts.maybe === 0,
    JSON.stringify([e2.start, again.start, e2.counts]));
  await page.evaluate(i => planner.api(`api/events/${i}/delete`, {}), id2);

  const land = await page.evaluate(() => planner.store.lands[0] || null);
  check('Es gibt ein Land zum Ausprobieren', !!land);
  if (land) {
    await page.evaluate(i => planner.navigate('#/claims/' + i), land.id);
    await page.click('#landEvent');
    await page.waitForSelector('#modal select[name=placeType]');
    form = await formValues(page);
    check('Land: „Event hier planen“ – Treffpunkt ist das Land', form.placeType === 'land' && form.landId === land.id, JSON.stringify(form));
    await closeDialog(page);
  }
  const marker = await page.evaluate(async r => {
    const m = await planner.api('api/markers', { name: 'Test-Treffpunkt ' + r, dimension: 'minecraft:overworld', x: 33, y: 64, z: -44, visibility: 'private' });
    await planner.load.markers();
    return m.id;
  }, run);
  await page.evaluate(() => planner.navigate('#/marker'));
  const markerButton = page.locator('.item', { hasText: 'Test-Treffpunkt ' + run }).locator('.marker-event');
  await markerButton.click();
  await page.waitForSelector('#modal select[name=placeType]');
  form = await formValues(page);
  check('Marker: „Event hier“ – Koordinaten und Titel vorausgefüllt', form.placeType === 'coords' && form.x === '33' && form.y === '64' && form.z === '-44'
    && form.title === `Treffen: Test-Treffpunkt ${run}`, JSON.stringify(form));
  await closeDialog(page);
  await page.evaluate(i => planner.api(`api/markers/${i}/delete`, {}), marker);
  const project = await page.evaluate(async r => (await planner.api('api/projects', { name: 'Test-Projekt ' + r, visibility: 'private' })).id, run);
  await page.evaluate(async i => { await planner.load.projects(); planner.navigate('#/projekte/' + i); }, project);
  await page.click('#projectEvent');
  await page.waitForSelector('#modal select[name=kind]');
  form = await formValues(page);
  check('Projekt: „Bauabend planen“ – Gemeinsam bauen, Titel mit dem Projekt', form.kind === 'together' && form.title === `Bauabend: Test-Projekt ${run}`,
    JSON.stringify(form));
  await closeDialog(page);
  await page.evaluate(i => planner.api(`api/projects/${i}/delete`, {}), project);

  // 4) Bob: Einladung auf dem Dashboard – derselbe Text wie im Spiel (/api/todo kommt vom Server)
  const ctxB = await browser.newContext({ viewport: { width: 1400, height: 950 } });
  const pageB = await ctxB.newPage();
  const problemsB = collectProblems(pageB);
  await login(pageB, codeB);
  await pageB.evaluate(() => planner.load.events());
  const expected = `Event: »${title}« am ${at.when} – kommst du?`;
  const serverTodo = await pageB.evaluate(() => planner.api('api/todo'));
  check('Server (/api/todo, wie im Spiel): „Event: … – kommst du?“', serverTodo.items.some(i => i.text === expected), JSON.stringify(serverTodo.items.map(i => i.text).filter(t => t.startsWith('Event'))));
  await waitUntil(async () => (await todoText(pageB)).includes(expected));
  check('Dashboard zeigt genau denselben Text', (await todoText(pageB)).includes(expected));
  const badge = await pageB.$eval('[data-badge=events]', b => (b.classList.contains('hidden') ? 0 : Number(b.textContent)));
  check('Zahl am Reiter „Events“ = offene Einladungen', badge >= 1, badge);
  await pageB.evaluate(i => planner.navigate('#/events/' + i), id);
  await pageB.waitForSelector('#eventAnswer button[data-answer=yes]');
  check('Bob sieht keine Knöpfe zum Bearbeiten', !(await pageB.isVisible('button:has-text("Bearbeiten")')) && !(await pageB.isVisible('button:has-text("Löschen")')));
  await pageB.click('#eventAnswer button[data-answer=yes]');
  await waitUntil(async () => (await eventApi(pageB, id)).counts.yes === 2);
  check('Bob sagt zu: 2 kommen', true);
  await pageB.evaluate(() => planner.navigate('#/dashboard'));
  await waitUntil(async () => !(await todoText(pageB)).includes(expected));
  check('…und die Einladung ist bei „Zu erledigen“ weg', true);

  // 5) Alice verschiebt um eine Stunde – Bob hat geantwortet, also merkt sich der Server die Verschiebung
  await page.evaluate(async i => { await planner.load.events(); planner.navigate('#/events/' + i); }, id);
  await page.waitForSelector('#eventAnswer');
  await page.click('button:has-text("Bearbeiten")');
  await page.waitForSelector('#modal input[name=time]');
  check('Bearbeiten: Formular mit den gespeicherten Werten', (await page.inputValue('#modal input[name=time]')) === '18:30' && (await page.inputValue('#modal input[name=title]')) === title);
  await page.fill('#modal input[name=time]', '19:30');
  await dialogSubmit(page);
  await waitUntil(async () => (await eventApi(page, id)).start === at.start + 3600000);
  e = await eventApi(page, id);
  check('Verschoben: neue Zeit, alte Zeit gemerkt', e.previousStart === at.start && e.movedAt > 0, JSON.stringify([e.previousStart, e.movedAt]));
  await page.waitForFunction(t => document.querySelector('#viewRoot').textContent.includes(t), at.later);
  check('Seite zeigt die neue Zeit', true);

  // 6) Dashboard von Alice: Nächste Events und Rückblick (Zahlen wie die API)
  await page.evaluate(() => planner.navigate('#/dashboard'));
  await page.waitForSelector('#recapCard .recap-facts');
  await waitUntil(async () => (await page.textContent('#nextEvents')).includes(title));
  check('„Nächste Events“ zeigt es', true);
  const recap = await page.evaluate(() => planner.api('api/recap'));
  const facts = await page.$$eval('#recapCard .recap-fact', els => els.map(x => [x.querySelector('span').textContent, x.querySelector('strong').textContent]));
  const fact = label => (facts.find(f => f[0].startsWith(label)) || [])[1];
  const fmt = await page.evaluate(async ms => (await import('./js/statfmt.js')).fmtDuration(ms), recap.stats ? recap.stats.playTime : 0);
  check('Rückblick: Spielzeit wie die API', !recap.stats || fact('gespielt') === fmt, `${fact('gespielt')} vs ${fmt}`);
  check('Rückblick: neue Länder wie die API', fact('neue') === String(recap.lands.count) || fact('neues') === String(recap.lands.count), JSON.stringify(facts));
  check('Rückblick: Events in den nächsten 7 Tagen wie die API', !recap.events.upcoming
    || (await page.textContent('#recapCard')).includes(`${recap.events.upcoming} ${recap.events.upcoming === 1 ? 'Event' : 'Events'} in den nächsten 7 Tagen`));
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'dashboard-rueckblick.png'), fullPage: true });

  // 7) Absagen und Löschen
  await page.evaluate(i => planner.navigate('#/events/' + i), id);
  await page.waitForSelector('#eventAnswer');
  await page.click('button:has-text("Absagen")');
  await page.waitForSelector('#modal input[name=reason]');
  await page.fill('#modal input[name=reason]', 'Server-Wartung');
  await dialogSubmit(page);
  await page.waitForSelector('#eventCancelled');
  check('Abgesagt: Hinweis mit Grund', (await page.textContent('#eventCancelled')).includes('Server-Wartung'));
  check('…keine Zusage-Knöpfe mehr', (await page.$$('#eventAnswer button[data-answer]')).length === 0);
  await pageB.evaluate(() => Promise.all([planner.load.events()]));
  await waitUntil(async () => (await todoText(pageB)).includes(`Abgesagt: »${title}«`));
  check('Bob (hatte zugesagt) sieht die Absage bei „Zu erledigen“', true);
  await page.click('button:has-text("Löschen")');
  await dialogSubmit(page);
  await waitUntil(async () => (await hash(page)) === '#/events');
  // über die Liste prüfen – eine absichtliche 404 würde als Fehler in der Konsole stehen
  check('Gelöscht', !(await page.evaluate(i => planner.api('api/events').then(r => r.events.some(x => x.id === i)), id)));

  // 8) Handy: nichts ragt seitlich heraus
  await page.setViewportSize({ width: 390, height: 844 });
  for (const [h, sel, cal] of [['#/events', '#newEventBtn', false], ['#/events', '#newEventBtn', true], ['#/dashboard', '#recapCard', false]]) {
    await page.evaluate(x => planner.navigate(x), h);
    await page.waitForSelector(sel);
    if (cal) { await page.click('.segmented button:has-text("Kalender")'); await page.waitForSelector('#eventCalendar'); }
    await page.waitForTimeout(500);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check(`Handy ${h}${cal ? ' (Kalender)' : ''}: keine seitliche Scrollleiste`, overflow <= 0, overflow);
  }

  check('Keine Fehler in der Konsole', problems.length === 0 && problemsB.length === 0, [...problems, ...problemsB].join(' | '));
  await browser.close();
  process.exit(summary('Events im Browser') ? 1 : 0);
})().catch(e => { console.error('ABBRUCH:', e.stack || e.message); process.exit(1); });
