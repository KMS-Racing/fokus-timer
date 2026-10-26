// Browser-Test der Webseiten-Teile der Spiel-Integration: Schalter „Im Spiel“ in den Einstellungen,
// Dashboard-Liste = Liste im Spiel (/api/todo), Mess-Werkzeug auf der Karte. Läuft gegen einen echten Testserver.
// Aufruf: node game_web_test.js <code-spielerA> <code-spielerB>
'use strict';
const path = require('path');
const { launch, makeChecker, collectProblems, login, waitUntil } = require('./lib');

const [codeA, codeB] = process.argv.slice(2);
if (!codeA || !codeB) { console.error('Aufruf: node game_web_test.js <code-A> <code-B>'); process.exit(2); }
const SHOTS = process.env.SHOT_DIR || null;

/**
 * Texte der Dashboard-Liste „Zu erledigen“ und der Liste, die auch im Spiel steht (/api/todo).
 * Zugeklappt zeigt das Dashboard nur die ersten 6 (before, moreText) – für den Vergleich wird es aufgeklappt (dashboard).
 */
async function bothLists(page) {
  await page.evaluate(() => Promise.all([planner.load.lands(), planner.load.teams(), planner.load.trades(), planner.load.projects(),
    planner.load.events(), planner.load.me()]));
  await page.evaluate(() => planner.navigate('#/dashboard'));
  await page.waitForSelector('#todoCard');
  await page.waitForTimeout(300);
  const before = await page.$$eval('#todoCard li a', as => as.map(a => a.textContent));
  const moreText = await page.$eval('#todoMore', b => b.textContent).catch(() => null);
  if (moreText) {
    await page.click('#todoMore');
    await page.waitForSelector('#todoMore[aria-expanded=true]');
  }
  const dashboard = await page.$$eval('#todoCard li a', as => as.map(a => a.textContent));
  const game = await page.evaluate(async () => (await planner.api('api/todo')).items.map(i => i.text));
  return { dashboard, game, before, moreText };
}

/** Mehr als 6: die ersten 6 und „… und N weitere anzeigen“; sonst alle ohne Knopf. */
function shortened(lists) {
  const n = lists.game.length;
  return n > 6
    ? JSON.stringify(lists.before) === JSON.stringify(lists.game.slice(0, 6)) && lists.moreText === `… und ${n - 6} weitere anzeigen`
    : lists.moreText === null && lists.before.length === n;
}

(async () => {
  const { check, summary } = makeChecker();
  const browser = await launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 950 } });
  const problems = collectProblems(page);
  await login(page, codeA);
  for (const key of ['notify', 'landEnter', 'deathMarker']) await page.evaluate(k => planner.api('api/prefs/game', { key: k, on: true }), key);

  // 1) Einstellungen „Im Spiel“
  await page.evaluate(() => planner.navigate('#/einstellungen'));
  await page.waitForSelector('#gameSettings input[data-game=landEnter]');
  const boxes = await page.$$eval('#gameSettings input[data-game]', els => els.map(e => [e.dataset.game, e.checked, e.disabled]));
  check('Drei Schalter, alle an', JSON.stringify(boxes) === JSON.stringify([['notify', true, false], ['landEnter', true, false], ['deathMarker', true, false]]), JSON.stringify(boxes));
  const text = await page.textContent('#gameSettings');
  check('Ehrlicher Hinweis: Anzeige schützt nichts', text.includes('sie schützt nichts') && text.includes('Die Items schützt das nicht'), text);
  check('Team-Chat und /planer werden erklärt', text.includes('/tc') && text.includes('/planer einstellungen'));
  await page.click('#gameSettings input[data-game=landEnter]');
  check('Ausschalten wird auf dem Server gespeichert', await waitUntil(async () =>
    (await page.evaluate(() => planner.api('api/prefs'))).game.landEnter === false), 'landEnter bleibt an');
  check('…und bleibt nach dem Neuzeichnen aus', !(await page.isChecked('#gameSettings input[data-game=landEnter]')));
  if (SHOTS) await page.locator('#gameSettings').screenshot({ path: path.join(SHOTS, 'einstellungen-im-spiel.png') });
  await page.click('#gameSettings input[data-game=landEnter]');
  check('Wieder an', await waitUntil(async () => (await page.evaluate(() => planner.api('api/prefs'))).game.landEnter === true));

  // 2) Dashboard zeigt dieselbe Liste wie das Spiel
  const listsA = await bothLists(page);
  check('Dashboard = Liste im Spiel (Spieler A)', JSON.stringify(listsA.dashboard) === JSON.stringify(listsA.game),
    '\n  Dashboard: ' + JSON.stringify(listsA.dashboard) + '\n  Spiel:     ' + JSON.stringify(listsA.game));
  check(`Zugeklappt nur die ersten 6, Rest per Knopf (A hat ${listsA.game.length})`, shortened(listsA), JSON.stringify([listsA.before.length, listsA.moreText]));
  const pageB = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  const problemsB = collectProblems(pageB);
  await login(pageB, codeB);
  const listsB = await bothLists(pageB);
  check('Dashboard = Liste im Spiel (Spieler B)', JSON.stringify(listsB.dashboard) === JSON.stringify(listsB.game),
    '\n  Dashboard: ' + JSON.stringify(listsB.dashboard) + '\n  Spiel:     ' + JSON.stringify(listsB.game));
  check(`Zugeklappt nur die ersten 6, Rest per Knopf (B hat ${listsB.game.length})`, shortened(listsB), JSON.stringify([listsB.before.length, listsB.moreText]));
  check('Die Listen sind nicht einfach leer', listsA.game.length + listsB.game.length > 0, 'beide leer – Test sagt dann wenig');

  // 3) Mess-Werkzeug
  await page.evaluate(() => planner.navigate('#/karte'));
  await page.waitForSelector('#measureBtn');
  await page.evaluate(() => { planner.map.centerOn(0, 0, 1); });
  await page.click('#measureBtn');
  check('Messen: Hinweis auf Punkt A', (await page.textContent('#measureBox')).includes('Startpunkt A'));
  const box = await page.locator('#mapCanvas').boundingBox();
  const [ax, ay] = [box.x + box.width / 2 - 150, box.y + box.height / 2 + 100];
  const [bx, by] = [box.x + box.width / 2 + 150, box.y + box.height / 2 - 100];
  await page.mouse.click(ax, ay);
  check('Nach Punkt A: Hinweis auf Ziel B', (await page.textContent('#measureBox')).includes('jetzt auf das Ziel B klicken'));
  check('…ohne Chunk-Infokarte', await page.isHidden('#chunkCard'));
  await page.mouse.click(bx, by);
  await page.waitForSelector('#measureResult');
  const m = await page.evaluate(() => planner.map.map.measure);
  const expected = await page.evaluate(mm => {
    const r = planner.coords.measure(mm.a, mm.b, mm.dim);
    return { text: `📏 ${planner.coords.fmtDistance(r.distance)} · ${r.direction}`, nether: planner.coords.fmtDistance(r.netherDistance) };
  }, m);
  check('Ergebnis: Entfernung und Richtung', (await page.textContent('#measureResult')) === expected.text, await page.textContent('#measureResult') + ' / erwartet ' + expected.text);
  check('Rechts oben von A aus = Nordosten', expected.text.endsWith('Nordosten'), expected.text);
  check('Strecke durch den Nether', (await page.textContent('#measureNether')).includes(expected.nether));
  check('300 × 200 Pixel bei Zoom 1 ≈ 361 Blöcke', Math.abs(Math.hypot(m.b.x - m.a.x, m.b.z - m.a.z) - Math.hypot(300, 200)) < 3, JSON.stringify(m));
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'karte-messen.png') });
  await page.mouse.click(bx + 40, by + 40);
  check('Dritter Klick: neue Messung ab dort', (await page.textContent('#measureBox')).includes('jetzt auf das Ziel B klicken'));
  await page.click('#measureBox button[aria-label="Messen beenden"]');
  check('Beenden: Linie und Kasten weg', await page.isHidden('#measureBox') && (await page.evaluate(() => planner.map.map.measure)) === null);

  // 4) Handy: der Kasten passt auf den Bildschirm
  await page.setViewportSize({ width: 390, height: 800 });
  await page.click('#measureBtn');
  const mb = await page.locator('#measureBox').boundingBox();
  check('Handy: Mess-Kasten passt in die Breite', mb && mb.x >= 0 && mb.x + mb.width <= 390, JSON.stringify(mb));
  await page.click('#measureBtn');

  check('Keine Fehler in der Browser-Konsole', problems.length + problemsB.length === 0, [...problems, ...problemsB].join(' | '));
  await browser.close();
  process.exit(summary('Spiel-Integration (Webseite)') ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
