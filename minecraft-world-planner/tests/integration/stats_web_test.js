// Browser-Test der neuen Seiten aus 0.5: Statistik (Server, Ranglisten, Spielerseite, Ausblenden) und Rennen.
// Vergleicht, was die Seite zeigt, mit dem, was die API liefert – es wird nichts erfunden. Läuft gegen einen echten Testserver.
// Aufruf: node stats_web_test.js <code-spielerA>
// Für den Rennen-Teil muss es eine fertige Strecke mit Zeiten geben (z. B. nach race_test.py mit RACE_KEEP=1), sonst wird er übersprungen.
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
  await page.evaluate(() => planner.api('api/prefs/game', { key: 'rankings', on: true }));
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

  // 2) Ranglisten
  await page.evaluate(() => planner.navigate('#/statistik/ranglisten'));
  await page.waitForSelector('#rankGrid .rank-card');
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

  // 3) Eigene Spielerseite, ausblenden und wieder sichtbar
  await page.evaluate(uuid => planner.navigate('#/statistik/spieler/' + uuid), me.uuid);
  await page.waitForSelector('#playerTiles');
  const mine = await page.evaluate(uuid => planner.api('api/stats/players/' + uuid), me.uuid);
  const playTile = await page.$eval('#playerTiles .stat strong', s => s.textContent);
  check('Spielerseite: Spielzeit wie in der API', playTile.length > 0 && mine.values.playTime > 0, playTile);
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'statistik-spieler.png'), fullPage: true });
  await page.click('#hideMeBtn');
  await page.waitForSelector('#hiddenNote');
  const prefs = await page.evaluate(() => planner.api('api/prefs'));
  check('„Mich ausblenden“ stellt den Schalter um', prefs.game.rankings === false, JSON.stringify(prefs.game));
  const others = await page.evaluate(() => planner.api('api/stats/rankings?range=all'));
  check('…und Ranglisten zeigen „ausgeblendet“', others.meHidden === true);
  await page.click('#hiddenNote button.primary');
  await page.waitForSelector('#hideMeBtn');
  check('„Wieder sichtbar machen“', (await page.evaluate(() => planner.api('api/prefs'))).game.rankings === true);

  // 4) Einstellungen: derselbe Schalter
  await page.evaluate(() => planner.navigate('#/einstellungen'));
  await page.waitForSelector('#statsSettings input[data-game=rankings]');
  check('Einstellungen: Schalter „In Ranglisten und Statistik sichtbar“ ist an', await page.isChecked('#statsSettings input[data-game=rankings]'));
  const gameSwitches = await page.$$eval('#gameSettings input[data-game]', els => els.map(e => e.dataset.game));
  check('„Im Spiel“ hat weiter genau drei Schalter', JSON.stringify(gameSwitches) === JSON.stringify(['notify', 'landEnter', 'deathMarker']), gameSwitches.join(','));

  // 5) Rennen (nur wenn es eine Strecke mit Zeiten gibt)
  const races = await page.evaluate(() => planner.api('api/races'));
  const track = races.tracks.find(t => t.ready && t.record);
  if (!track) {
    console.log('(Rennen übersprungen: keine fertige Strecke mit Zeiten)');
  } else {
    await page.evaluate(() => planner.navigate('#/rennen'));
    await page.waitForSelector('#raceList');
    const listed = await page.$$eval('#raceList .race-card', els => els.map(e => e.dataset.track));
    check('Rennen: alle Strecken in der Liste', races.tracks.every(t => listed.includes(t.name)), listed.join(','));
    await page.click(`#raceList .race-card[data-track="${track.name}"]`);
    await page.waitForSelector('#raceBoard');
    const detail = await page.evaluate(id => planner.api('api/races/' + id), track.id);
    const names = await page.$$eval('#raceBoard tbody th a', as => as.map(a => a.textContent));
    check('Bestenliste wie die API', JSON.stringify(names) === JSON.stringify(detail.leaderboard.map(l => l.name)), names.join(','));
    const first = await page.$eval('#raceBoard tbody tr td.lap-best', td => td.textContent);
    const fmt = ms => {
      const cs = Math.round(ms / 10), m = Math.floor(cs / 6000), s = Math.floor(cs / 100) % 60, h = cs % 100;
      return `${m > 0 ? `${m}:${String(s).padStart(2, '0')}` : s},${String(h).padStart(2, '0')}`;
    };
    check('Rekordzeit wie in der API, mit Hundertsteln (z. B. 6,05)', first === fmt(detail.leaderboard[0].ms), `${first} vs ${fmt(detail.leaderboard[0].ms)}`);
    const purple = await page.$$eval('#raceBoard td.sector-best', els => els.length);
    check('Lila Abschnitte nur, wo die API den schnellsten Abschnitt hat', purple === detail.leaderboard.reduce((n, l) =>
      n + (l.sectors || []).filter((s, i) => s === detail.bestSectors[i]).length, 0), purple);
    const gates = await page.$$eval('#racePlanCard line.race-gate', els => els.length);
    check('Streckenplan: ein Strich pro Tor', gates === detail.gates.length, gates);
    if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'rennen-strecke.png'), fullPage: true });
  }

  // 6) Handy: nichts ragt seitlich heraus
  await page.setViewportSize({ width: 390, height: 844 });
  for (const hash of ['#/statistik', '#/statistik/ranglisten', '#/statistik/spieler/' + me.uuid].concat(track ? ['#/rennen/' + track.id] : [])) {
    await page.evaluate(h => planner.navigate(h), hash);
    await page.waitForTimeout(1200);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check(`Handy ${hash.split('/').slice(0, 3).join('/')}: keine seitliche Scrollleiste`, overflow <= 0, overflow);
  }
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'statistik-handy.png'), fullPage: true });

  check('Keine Fehler in der Browser-Konsole', problems.length === 0, problems.join(' | '));
  await browser.close();
  process.exit(summary('Statistik und Rennen im Browser') ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
