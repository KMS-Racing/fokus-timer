// Browser-Test der Kernfunktionen: Login, Land per Karte claimen, einladen, annehmen, Marker, Handy-Ansicht.
// Aufruf: node browser_core_test.js <code-spielerA> <code-spielerB> <name-spielerB>   (beide keine OPs)
'use strict';
const { PLANNER_URL, launch, makeChecker, waitUntil, collectProblems, login } = require('./lib');

const [codeA, codeB, nameB] = process.argv.slice(2);
if (!codeA || !codeB || !nameB) { console.error('Aufruf: node browser_core_test.js <code-A> <code-B> <name-B>'); process.exit(2); }
const run = Math.random().toString(36).slice(2, 6).toUpperCase();

(async () => {
  const { check, summary } = makeChecker();
  const browser = await launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 950 } });
  const problems = collectProblems(page);

  // 1) Login über den Link aus dem Spiel
  await login(page, codeA);
  check('Login per Link funktioniert', await page.isVisible('#appView'));
  check('Code wurde aus der Adresszeile entfernt', !page.url().includes('login='), page.url());
  const nameA = await page.evaluate(() => state.me.name);
  check('Name oben rechts', (await page.textContent('#accountBox')).includes(nameA));
  check('Kein "null" im Kopfbereich', !(await page.textContent('#accountBox')).includes('null'));
  await page.waitForFunction(() => state.world && state.discoveredCount > 0);
  check('Seed angezeigt', /^-?\d+$/.test(await page.textContent('#seedValue')), await page.textContent('#seedValue'));

  // 2) Drei freie, entdeckte Chunks nebeneinander suchen und auf die Karte holen
  const free = await page.evaluate(() => {
    for (const reg of state.discovered.values()) {
      for (let i = 0; i < 1024; i++) {
        if (!reg.bits[i]) continue;
        const x = reg.rx * 32 + (i % 32), z = reg.rz * 32 + Math.floor(i / 32);
        const row = [0, 1, 2].map(d => [x + d, z]);
        if (row.every(([cx, cz]) => isDiscovered(cx, cz) && !state.chunkOwner.has(cx + ',' + cz))) return row;
      }
    }
    return null;
  });
  check('Freie entdeckte Chunks gefunden', !!free);
  await page.evaluate(([x, z]) => { setTab('claim'); centerOn(x * 16 + 24, z * 16 + 8, 2); }, free[0]);
  await page.waitForTimeout(300);
  // Position der Karte vor jedem Klick neu messen – die Seite kann inzwischen gescrollt haben.
  const at = async ([x, z]) => {
    await page.locator('#mapCanvas').scrollIntoViewIfNeeded();
    const box = await page.locator('#mapCanvas').boundingBox();
    const [sx, sz] = await page.evaluate(([x, z]) => toScreen(x * 16 + 8, z * 16 + 8), [x, z]);
    return { x: box.x + sx, y: box.y + sz };
  };
  const usedBefore = parseInt(await page.textContent('#usagePill'), 10);

  // 3) Auswählen und neues Land erstellen
  await page.click('#selectModeBtn');
  for (const c of free) { const p = await at(c); await page.mouse.click(p.x, p.y); }
  check('3 Chunks ausgewählt', (await page.textContent('#selectionCount')).startsWith('3'), await page.textContent('#selectionCount'));
  check('"Neues Land" ist klickbar', await page.isEnabled('#newLandBtn'));
  await page.click('#newLandBtn');
  const landName = 'Dorf ' + run;
  await page.fill('#textDialogInput', landName);
  await page.click('#textDialogOk');
  await page.waitForFunction(n => document.querySelector('#myLands').textContent.includes(n), landName, { timeout: 8000 });
  check('Land erstellt', true);
  check('Auswahlmodus ist danach aus', !((await page.getAttribute('#mapCanvas', 'class')) || '').includes('selecting'));
  check('Chunk-Zähler +3', (await page.textContent('#usagePill')).startsWith((usedBefore + 3) + ' /'), usedBefore + ' -> ' + await page.textContent('#usagePill'));

  // 4) Spieler B einladen
  const item = page.locator('#myLands .item', { hasText: landName });
  await item.locator('input[list=knownPlayers]').fill(nameB);
  await item.locator('button', { hasText: 'Einladen' }).click();
  await page.waitForFunction(n => [...document.querySelectorAll('#myLands .item')].some(i => i.textContent.includes(n) && i.textContent.includes('Eingeladen')), landName, { timeout: 8000 });
  check('Mitspieler steht als eingeladen da', true);

  // 5) Klick auf einen Chunk zeigt Infos
  const mid = await at(free[1]);
  await page.mouse.click(mid.x, mid.y);
  await page.waitForSelector('#chunkCard:not(.hidden)');
  const card = await page.textContent('#chunkCard');
  check('Chunk-Infokarte zeigt das Land', card.includes(landName), card);
  check('Chunk-Infokarte ohne "null"', !card.includes('null'), card);

  // 6) Marker mit HTML im Namen (muss als Text erscheinen) + Nether-Umrechnung
  const markerName = '<b>Portal ' + run + '</b>';
  await page.fill('#markerName', markerName);
  await page.selectOption('#markerType', 'portal');
  await page.fill('#coordX', '-100');
  await page.fill('#coordZ', '250');
  await page.dispatchEvent('#coordX', 'input');
  check('Nether-Umrechnung (-100 / 250 -> -13 / 31)', (await page.textContent('#convertHint')).includes('X -13 · Z 31'), await page.textContent('#convertHint'));
  await page.click('#markerSubmit');
  await page.waitForFunction(n => document.querySelector('#markerList').textContent.includes(n), markerName, { timeout: 8000 });
  check('HTML im Markernamen wird als Text gezeigt', (await page.locator('#markerList b').count()) === 0);
  await page.locator('#markerList .item', { hasText: markerName }).locator('button', { hasText: 'TP-Befehl' }).first().click();
  await page.waitForSelector('#toast.show');
  check('Kopieren gibt Rückmeldung', (await page.textContent('#toast')).includes('/tp @s -100 64 250'), await page.textContent('#toast'));

  // 7) Handy-Ansicht
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check('Handy: kein seitliches Scrollen', overflow <= 0, overflow + 'px');
  await page.setViewportSize({ width: 1400, height: 950 });

  // 8) Abmelden
  await page.click('#accountBox button:has-text("Abmelden")');
  await page.waitForSelector('#loginView:not(.hidden)');
  check('Abmelden führt zum Login', true);

  // 9) Spieler B nimmt die Einladung an
  await page.fill('#loginCode', codeB);
  await page.click('#loginForm button');
  await page.waitForSelector('#appView:not(.hidden)');
  check('B: kein "null" im Kopfbereich', !(await page.textContent('#accountBox')).includes('null'));
  check('B: kein Admin-Abzeichen', !(await page.textContent('#accountBox')).includes('Admin'));
  await page.waitForSelector('#invitesPanel:not(.hidden)');
  check('B sieht die Einladung', (await page.textContent('#invitesList')).includes(landName));
  await page.locator('#invitesList .item', { hasText: landName }).locator('button', { hasText: 'Annehmen' }).click();
  await page.waitForFunction(n => document.querySelector('#myLands').textContent.includes(n), landName, { timeout: 8000 });
  check('B besitzt das Land jetzt mit', (await page.locator('#myLands .item', { hasText: landName }).textContent()).includes(nameA));
  check('B sieht keine Admin-Knöpfe', (await page.locator('#allLands button', { hasText: 'Admin' }).count()) === 0);

  // Aufräumen: beide treten aus (letzter Besitzer -> Land frei), Marker löschen
  await page.evaluate(async n => { const l = state.lands.find(x => x.name === n); await api(`api/lands/${l.id}/leave`, {}); }, landName);
  await page.click('#accountBox button:has-text("Abmelden")');
  check('Keine Fehler unserer Seite in der Konsole', problems.length === 0, problems.join(' | '));
  await browser.close();
  console.log('(Hinweis: Land und Marker von Spieler A bleiben zum Ansehen stehen.)');
  process.exit(summary('Browser-Kern') ? 1 : 0);
})().catch(e => { console.error('ABBRUCH:', e.stack || e.message); process.exit(1); });
