// Browser-Test der Seiten aus 0.5: Statistik (Server, Spielerseite) und Ranglisten (eigener Reiter, für alle sichtbar).
// Vergleicht, was die Seite zeigt, mit dem, was die API liefert – es wird nichts erfunden. Läuft gegen einen echten Testserver.
// Aufruf: node stats_web_test.js <code-spielerA>
'use strict';
const path = require('path');
const { launch, makeChecker, collectProblems, login, waitUntil } = require('./lib');

const [codeA] = process.argv.slice(2);
if (!codeA) { console.error('Aufruf: node stats_web_test.js <code-A>'); process.exit(2); }
const SHOTS = process.env.SHOT_DIR || null;

(async () => {
  const { check, summary } = makeChecker();
  const browser = await launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 950 } });
  const problems = collectProblems(page);
  await login(page, codeA);
  const me = await page.evaluate(() => planner.store.me);

  // 1) Server-Übersicht
  await page.evaluate(() => planner.navigate('#/statistik'));
  await page.waitForSelector('#serverTiles');
  const server = await page.evaluate(() => planner.api('api/stats/server'));
  const tiles = await page.$$eval('#serverTiles .stat', els => els.map(e => [e.querySelector('span').textContent, e.querySelector('strong').textContent]));
  const tile = label => (tiles.find(t => t[0] === label) || [])[1];
  check('Kachel „Tode“ = Summe der API', tile('Tode') === Number(server.totals.deaths).toLocaleString('de-DE'), JSON.stringify([tile('Tode'), server.totals.deaths]));
  check('Kachel „Spielstunden“ = Summe der API', tile('Spielstunden') === `${Math.round(server.totals.playTime / 3600000).toLocaleString('de-DE')} h`, tile('Spielstunden'));
  const cells = await page.$$eval('#statsHeat rect.heat-cell', els => els.length);
  check('Wärmekarte: 7 × 24 Felder', cells === 168, cells);
  const measured = server.heat.flat().filter(v => v !== null).length;
  const empty = await page.$$eval('#statsHeat rect.heat-none', els => els.length);
  check('…ohne Messung = leeres Feld (keine erfundene 0)', empty === 168 - measured, `${empty} vs ${168 - measured}`);
  await page.focus('#statsHeat svg');
  await page.keyboard.press('ArrowRight');
  const tip = await page.$eval('.viz-tip', t => (t.classList.contains('hidden') ? '' : t.textContent)).catch(() => '');
  check('Pfeiltasten zeigen denselben Tooltip wie die Maus', /Mo 0–1 Uhr/.test(tip), tip);
  await page.click('#statsHeat .viz-head button');
  const rows = await page.$$eval('#statsHeat table tbody tr', els => els.length);
  check('Tabellen-Ansicht: 7 Zeilen (Mo–So)', rows === 7, rows);
  const bars = await page.$$eval('#statsPlayersPerDay path.viz-bar', els => els.length);
  check('Spieler pro Tag: so viele Säulen wie Tage mit Spielern', bars === server.days.filter(d => d.players > 0).length, bars);

  // 2) Ranglisten: eigener Reiter in der Navigation
  await page.click('#sideNav .nav-link[data-route=ranglisten]');
  await page.waitForSelector('#rankGrid .rank-card');
  check('Reiter „Ranglisten“ führt auf #/ranglisten und ist markiert', await page.evaluate(() => location.hash) === '#/ranglisten'
    && await page.$eval('#sideNav .nav-link[data-route=ranglisten]', a => a.classList.contains('active')), await page.evaluate(() => location.hash));
  check('Überschrift „Ranglisten“', await page.$eval('#viewRoot h1', h => h.textContent) === 'Ranglisten');
  const cards = await page.$$eval('#rankGrid .rank-card', els => els.map(e => e.dataset.category));
  check('16 Ranglisten', cards.length === 16 && cards.includes('dauerzocker') && cards.includes('flummi'), cards.join(','));
  const api = await page.evaluate(() => planner.api('api/stats/rankings?range=all'));
  const dz = api.categories.find(c => c.id === 'dauerzocker');
  const shown = await page.$$eval('[data-category=dauerzocker] .bar-name a', as => as.map(a => a.textContent));
  check('Dauerzocker: Reihenfolge wie die API', JSON.stringify(shown) === JSON.stringify(dz.entries.map(e => e.name)), JSON.stringify(shown));
  await page.click('.segmented button:has-text("7 Tage")');
  await waitUntil(() => page.$eval('.toolbar-note', n => n.textContent.includes('Zuwachs')));
  const unsterblich = await page.$eval('[data-category=unsterblich]', e => e.textContent);
  check('7 Tage: „Unsterblich“ gibt es nur gesamt', unsterblich.includes('nur „Gesamt“'), unsterblich.slice(0, 120));

  await page.evaluate(() => planner.navigate('#/statistik'));
  await page.waitForSelector('#serverTiles');
  await page.evaluate(() => planner.navigate('#/statistik/ranglisten')); // Link aus 0.5.0 (z. B. im Chat)
  await page.waitForSelector('#rankGrid .rank-card');
  check('Alter Link #/statistik/ranglisten führt auf die neue Seite', await page.evaluate(() => location.hash) === '#/ranglisten', await page.evaluate(() => location.hash));

  // 3) Spielerseiten: für alle sichtbar, ohne „Mich ausblenden“
  const other = api.categories.flatMap(c => c.entries).find(e => e.uuid !== me.uuid);
  if (other) {
    await page.evaluate(uuid => planner.navigate('#/statistik/spieler/' + uuid), other.uuid);
    await page.waitForSelector('#playerTiles');
    check(`Die Seite von ${other.name} ist für alle sichtbar`, (await page.$eval('#viewRoot h1', h => h.textContent)) === other.name);
  }
  await page.evaluate(uuid => planner.navigate('#/statistik/spieler/' + uuid), me.uuid);
  await page.waitForSelector('#playerTiles');
  const mine = await page.evaluate(uuid => planner.api('api/stats/players/' + uuid), me.uuid);
  const playTile = await page.$eval('#playerTiles .stat strong', s => s.textContent);
  check('Spielerseite: Spielzeit wie in der API', playTile.length > 0 && mine.values.playTime > 0, playTile);
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'statistik-spieler.png'), fullPage: true });
  check('Kein „Mich ausblenden“ mehr', (await page.$('#hideMeBtn')) === null && !(await page.textContent('#viewRoot')).includes('ausblenden'));
  const prefs = await page.evaluate(() => planner.api('api/prefs'));
  check('/api/prefs kennt keinen Ranglisten-Schalter mehr', !('rankings' in prefs.game), JSON.stringify(prefs.game));

  // 4) Einstellungen: Hinweis statt Schalter
  await page.evaluate(() => planner.navigate('#/einstellungen'));
  await page.waitForSelector('#statsSettings');
  check('Einstellungen: Hinweis „für alle sichtbar“, kein Schalter', (await page.$$('#statsSettings input')).length === 0
    && (await page.textContent('#statsSettings')).includes('Ausblenden kann man sich nicht.'), await page.textContent('#statsSettings'));
  const gameSwitches = await page.$$eval('#gameSettings input[data-game]', els => els.map(e => e.dataset.game));
  check('„Im Spiel“ hat weiter genau drei Schalter', JSON.stringify(gameSwitches) === JSON.stringify(['notify', 'landEnter', 'deathMarker']), gameSwitches.join(','));

  // 6) Handy: nichts ragt seitlich heraus
  await page.setViewportSize({ width: 390, height: 844 });
  for (const hash of ['#/statistik', '#/ranglisten', '#/statistik/spieler/' + me.uuid]) {
    await page.evaluate(h => planner.navigate(h), hash);
    await page.waitForTimeout(1200);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check(`Handy ${hash.split('/').slice(0, 3).join('/')}: keine seitliche Scrollleiste`, overflow <= 0, overflow);
  }
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'statistik-handy.png'), fullPage: true });

  check('Keine Fehler in der Browser-Konsole', problems.length === 0, problems.join(' | '));
  await browser.close();
  process.exit(summary('Statistik und Ranglisten im Browser') ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
