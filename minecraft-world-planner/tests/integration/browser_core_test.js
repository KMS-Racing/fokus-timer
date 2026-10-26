// Browser-Test der Kernfunktionen: Login, Land per Karte claimen (mit Vorschau), einladen, annehmen, Marker,
// Suche, Handy-Ansicht. Läuft gegen einen echten Testserver.
// Aufruf: node browser_core_test.js <code-spielerA> <code-spielerB> <name-spielerB>   (beide keine OPs)
'use strict';
const { launch, makeChecker, collectProblems, login } = require('./lib');

const [codeA, codeB, nameB] = process.argv.slice(2);
if (!codeA || !codeB || !nameB) { console.error('Aufruf: node browser_core_test.js <code-A> <code-B> <name-B>'); process.exit(2); }
const run = Math.random().toString(36).slice(2, 6).toUpperCase();

async function dialogSubmit(page) {
  await page.click('#modal button.primary[type=submit], #modal button.danger[type=submit]');
  await page.waitForSelector('#modal', { state: 'hidden' });
}

(async () => {
  const { check, summary } = makeChecker();
  const browser = await launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 950 } });
  const problems = collectProblems(page);

  // 1) Login über den Link aus dem Spiel
  await login(page, codeA);
  check('Login per Link funktioniert', await page.isVisible('#appView'));
  check('Code wurde aus der Adresszeile entfernt', !page.url().includes('login='), page.url());
  // Testländer früherer Läufe aufräumen: nur eigene, deren Name mit „Testland “ beginnt und die niemand sonst mitbesitzt.
  // Sonst ist nach vielen Läufen die Chunk-Grenze voll. Das Land dieses Laufs bleibt am Ende zum Ansehen stehen.
  await page.evaluate(async () => {
    const me = planner.store.me.uuid;
    for (const l of planner.store.lands) {
      if (l.name.startsWith('Testland ') && l.owners.length === 1 && l.owners[0].uuid === me) await planner.api(`api/lands/${l.id}/delete`, {});
    }
    await Promise.all([planner.load.lands(), planner.load.me()]); // auch den Chunk-Zähler, wie die Seite nach dem Löschen
  });
  const nameA = await page.evaluate(() => planner.store.me.name);
  check('Name oben rechts', (await page.textContent('#accountBox')).includes(nameA));
  check('Kein "null" im Kopfbereich', !(await page.textContent('#accountBox')).includes('null'));
  check('Dashboard ist die Startseite', (await page.textContent('#viewRoot h1')).includes('Hallo'));
  const routes = await page.$$eval('#sideNav .nav-link', as => as.map(a => a.dataset.route));
  check('Hauptnavigation mit allen 13 Bereichen (seit 0.6 mit Events, ohne Rennen)', JSON.stringify(routes) === JSON.stringify(
    ['dashboard', 'karte', 'live', 'claims', 'marker', 'teams', 'handel', 'projekte', 'events', 'spieler', 'statistik', 'ranglisten', 'einstellungen']), routes.join(','));
  await page.click('#sideNav a[data-route=einstellungen]');
  await page.waitForSelector('#seedValue');
  check('Seed angezeigt (Einstellungen)', /^-?\d+$/.test(await page.textContent('#seedValue')), await page.textContent('#seedValue'));

  // 2) Karte: drei freie, entdeckte Chunks nebeneinander suchen
  await page.click('#sideNav a[data-route=karte]');
  await page.waitForFunction(() => planner.map.map.discoveredCount > 0 && planner.store.lands.length >= 0);
  const free = await page.evaluate(() => {
    const m = planner.map.map;
    for (const reg of m.discovered.values()) {
      for (let i = 0; i < 1024; i++) {
        if (!reg.bits[i]) continue;
        const x = reg.rx * 32 + (i % 32), z = reg.rz * 32 + Math.floor(i / 32);
        const row = [0, 1, 2].map(d => [x + d, z]);
        if (row.every(([cx, cz]) => planner.map.isDiscovered(cx, cz) && !m.chunkOwner.has(cx + ',' + cz))) return row;
      }
    }
    return null;
  });
  check('Freie entdeckte Chunks gefunden', !!free);
  await page.evaluate(([x, z]) => planner.map.centerOn(x * 16 + 24, z * 16 + 8, 2), free[0]);
  await page.waitForTimeout(300);
  const at = async ([x, z]) => {
    const box = await page.locator('#mapCanvas').boundingBox();
    const [sx, sz] = await page.evaluate(([x, z]) => planner.map.toScreen(x * 16 + 8, z * 16 + 8), [x, z]);
    return { x: box.x + sx, y: box.y + sz };
  };
  const usedBefore = await page.evaluate(() => planner.store.me.usedChunks);

  // 3) Auswählen, Vorschau, neues Land erstellen
  await page.click('#selectModeBtn');
  for (const c of free) { const p = await at(c); await page.mouse.click(p.x, p.y); }
  check('3 Chunks ausgewählt', (await page.textContent('#selectionCount')).startsWith('3'), await page.textContent('#selectionCount'));
  await page.waitForSelector('#checkLine.ok', { timeout: 8000 });
  check('Vorschau: alles frei und zusammenhängend', (await page.textContent('#checkLine')).includes('Alles frei'), await page.textContent('#checkLine'));
  check('Vorschau zeigt Chunks danach', (await page.textContent('#checkLine')).includes(`danach ${usedBefore + 3} von`), await page.textContent('#checkLine'));
  check('"Neues Land" ist klickbar', await page.isEnabled('#newLandBtn'));
  await page.click('#newLandBtn');
  const landName = 'Testland ' + run;
  await page.fill('#modal input[name=name]', landName);
  await dialogSubmit(page);
  await page.waitForFunction(n => planner.store.lands.some(l => l.name === n), landName, { timeout: 8000 });
  check('Land erstellt', true);
  check('Auswahlmodus ist danach aus', !((await page.getAttribute('#mapCanvas', 'class')) || '').includes('selecting'));
  await page.waitForFunction(u => planner.store.me.usedChunks === u + 3, usedBefore, { timeout: 8000 }).catch(() => {});
  check('Chunk-Zähler +3', (await page.evaluate(() => planner.store.me.usedChunks)) === usedBefore + 3);

  // 4) Klick auf einen Chunk zeigt Infos
  const mid = await at(free[1]);
  await page.mouse.click(mid.x, mid.y);
  await page.waitForSelector('#chunkCard:not(.hidden)');
  const card = await page.textContent('#chunkCard');
  check('Chunk-Infokarte zeigt das Land', card.includes(landName), card);
  check('Chunk-Infokarte ohne "null"/"undefined"', !/null|undefined/.test(card), card);

  // 5) Detailseite: Spieler B einladen
  await page.click('#chunkCard button:has-text("Land öffnen")');
  await page.waitForSelector('#viewRoot h1');
  check('Detailseite des Landes', (await page.textContent('#viewRoot h1')).includes(landName));
  check('Hinweis: Land schützt nichts im Spiel', (await page.textContent('#viewRoot')).includes('verhindert im Spiel kein Abbauen'));
  await page.fill('input[aria-label="Spieler einladen"]', nameB);
  await page.click('button:has-text("Einladen")');
  await page.waitForFunction(n => [...document.querySelectorAll('#viewRoot .card')].some(c => c.textContent.includes('Eingeladen') && c.textContent.includes(n)), nameB, { timeout: 8000 });
  check('Mitspieler steht als eingeladen da', true);
  check('Löschen geht als einziger Besitzer', await page.isVisible('button.danger:has-text("Löschen")'));

  // 6) Marker mit HTML im Namen (muss als Text erscheinen) + Nether-Umrechnung
  await page.click('#sideNav a[data-route=marker]');
  await page.click('button:has-text("Neuer Marker")');
  const markerName = '<b>Portal ' + run + '</b>';
  await page.fill('#modal input[name=name]', markerName);
  await page.selectOption('#modal select[name=type]', 'portal');
  await page.fill('#modal input[name=x]', '-100');
  await page.fill('#modal input[name=z]', '250');
  await page.dispatchEvent('#modal input[name=z]', 'input');
  check('Nether-Umrechnung (-100 / 250 -> -13 / 31)', (await page.textContent('#modal .hint')).includes('X -13 · Z 31'), await page.textContent('#modal .hint'));
  await dialogSubmit(page);
  await page.waitForFunction(n => document.querySelector('#viewRoot').textContent.includes(n), markerName, { timeout: 8000 });
  check('HTML im Markernamen wird als Text gezeigt', (await page.locator('#viewRoot .item b').count()) === 0);
  await page.locator('#viewRoot .item', { hasText: markerName }).locator('button', { hasText: 'TP-Befehl' }).first().click();
  // Gezielt auf die Kopier-Meldung warten: Direkt davor steht noch „Marker angelegt“ – ein beliebiger Toast reicht nicht.
  const copied = await page.waitForFunction(() => {
    const t = document.querySelector('#toast.show');
    return t && t.textContent.includes('/tp @s -100 64 250');
  }, null, { timeout: 5000 }).then(() => true, () => false);
  check('Kopieren gibt Rückmeldung', copied, await page.textContent('#toast'));

  // 6b) Koordinaten-Rechner auf der Karte
  await page.click('#sideNav a[data-route=karte]');
  await page.evaluate(() => planner.map.setDimension('minecraft:overworld'));
  await page.click('#calcBox summary');
  await page.fill('#calcInput', 'XYZ: -100.5 / 64.0 / 250.2');
  const calc = await page.textContent('#calcOut');
  check('Rechner: Chunk -7 / 15', calc.includes('-7 / 15'), calc);
  check('Rechner: Region r.-1.0.mca', calc.includes('r.-1.0.mca'), calc);
  check('Rechner: Nether X -13 · Z 31', calc.includes('X -13 · Z 31'), calc);
  await page.fill('#calcInput', 'Basis');
  check('Rechner: Unsinn wird erklärt', (await page.textContent('#calcOut')).includes('keine gültigen Koordinaten'));
  await page.click('#calcBox summary');

  // 7) Globale Suche: Land und Koordinaten
  await page.fill('#searchInput', landName);
  await page.waitForSelector('#searchResults:not(.hidden) .search-item');
  check('Suche findet das Land', (await page.textContent('#searchResults')).includes(landName));
  await page.keyboard.press('Enter');
  await page.waitForFunction(n => document.querySelector('#viewRoot h1') && document.querySelector('#viewRoot h1').textContent.includes(n), landName);
  check('Enter öffnet das Land', true);
  await page.fill('#searchInput', '1000 64 -2000');
  await page.waitForSelector('#searchResults .search-item');
  check('Suche erkennt Koordinaten', (await page.textContent('#searchResults')).includes('Gehe zu X 1000 · Y 64 · Z -2000'));
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => location.hash === '#/karte');
  const view = await page.evaluate(() => planner.map.map.view);
  check('Karte springt zu den Koordinaten', Math.abs(view.x - 1000.5) < 1 && Math.abs(view.z + 1999.5) < 1, JSON.stringify(view));

  // 8) Handy-Ansicht
  await page.setViewportSize({ width: 390, height: 844 });
  for (const v of ['dashboard', 'karte', 'claims', 'handel', 'teams']) {
    await page.evaluate(n => { location.hash = '#/' + n; }, v);
    await page.waitForTimeout(400);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check(`Handy (${v}): kein seitliches Scrollen`, overflow <= 0, overflow + 'px');
  }
  await page.click('#navToggle');
  check('Handy: Menü öffnet sich', await page.isVisible('#sideNav a[data-route=projekte]'));
  await page.click('#sideNav a[data-route=projekte]');
  check('Handy: Menü schließt nach Auswahl', !(await page.evaluate(() => document.body.classList.contains('nav-open'))));
  await page.setViewportSize({ width: 1400, height: 950 });

  // 9) Abmelden
  await page.click('#accountBox button:has-text("Abmelden")');
  await page.waitForSelector('#loginView:not(.hidden)');
  check('Abmelden führt zum Login', true);

  // 10) Spieler B nimmt die Einladung an
  await page.fill('#loginCode', codeB);
  await page.click('#loginForm button');
  await page.waitForSelector('#appView:not(.hidden)');
  check('B: kein "null" im Kopfbereich', !(await page.textContent('#accountBox')).includes('null'));
  check('B: kein Admin-Abzeichen', !(await page.textContent('#accountBox')).includes('Admin'));
  check('B: Dashboard zeigt die Einladung', (await page.textContent('#todoCard')).includes(landName));
  await page.click('#sideNav a[data-route=claims]');
  await page.waitForSelector('#invitesPanel');
  check('B sieht die Einladung bei Claims', (await page.textContent('#invitesList')).includes(landName));
  await page.locator('#invitesList .item', { hasText: landName }).locator('button', { hasText: 'Annehmen' }).click();
  await page.waitForFunction(n => planner.store.lands.some(l => l.name === n && l.mine), landName, { timeout: 8000 });
  const item = page.locator('#landList .item', { hasText: landName });
  check('B besitzt das Land jetzt mit', (await item.textContent()).includes(nameA) && (await item.textContent()).includes('Meins'));
  await item.locator('a', { hasText: 'Details' }).click();
  // Auf die Detailseite dieses Landes warten – die Liste hat auch eine Überschrift („Claims“)
  await page.waitForSelector(`#viewRoot h1:text-is("${landName}")`);
  check('Mit Mitbesitzer: kein Löschen-Knopf, nur Austreten', !(await page.isVisible('button.danger:has-text("Löschen")')) && await page.isVisible('button:has-text("Austreten")'));
  check('B sieht keine Admin-Knöpfe', (await page.locator('button', { hasText: 'Admin' }).count()) === 0);

  // Aufräumen: B tritt aus (A behält das Land zum Ansehen – der nächste Lauf räumt es weg)
  await page.evaluate(async n => { const l = planner.store.lands.find(x => x.name === n); await planner.api(`api/lands/${l.id}/leave`, {}); }, landName);
  await page.click('#accountBox button:has-text("Abmelden")');
  check('Keine Fehler unserer Seite in der Konsole', problems.length === 0, problems.join(' | '));
  await browser.close();
  console.log('(Hinweis: Land und Marker von Spieler A bleiben zum Ansehen stehen.)');
  process.exit(summary('Browser-Kern') ? 1 : 0);
})().catch(e => { console.error('ABBRUCH:', e.stack || e.message); process.exit(1); });
