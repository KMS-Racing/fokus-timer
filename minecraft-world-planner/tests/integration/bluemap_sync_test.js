// Prüft, ob BlueMap die Position und den Zoom der Claim-Karte WIRKLICH übernimmt.
// Gemessen wird die echte Kamera im BlueMap-iframe (window.bluemap), nicht nur die URL.
// Aufruf: node bluemap_sync_test.js <login-code>
'use strict';
const { launch, makeChecker, waitUntil, collectProblems, login } = require('./lib');

const code = process.argv[2];
if (!code) { console.error('Aufruf: node bluemap_sync_test.js <login-code>'); process.exit(2); }

(async () => {
  const { check, summary } = makeChecker();
  const browser = await launch();
  const context = await browser.newContext({ viewport: { width: 1400, height: 950 } });
  const page = await context.newPage();
  const problems = collectProblems(page);

  await login(page, code);
  await page.waitForFunction(() => state.lands.length > 0 && state.world);
  const origin = await page.evaluate(() => new URL(mapUrl('live')).origin);
  const frame = () => page.frames().find(f => f.url().startsWith(origin));
  const camera = () => frame().evaluate(() => {
    const c = window.bluemap.mapViewer.controlsManager;
    return { x: c.position.x, z: c.position.z, distance: c.distance, map: window.bluemap.mapViewer.map.data.id };
  });
  const near = (a, b, tol) => Math.abs(a - b) <= tol;
  const cameraAt = async (t, tolXZ = 1, tolDist = 2) => {
    let last = null;
    const ok = await waitUntil(async () => {
      last = await camera();
      return near(last.x, t.x, tolXZ) && near(last.z, t.z, tolXZ) && near(last.distance, t.distance, tolDist);
    }, 20000);
    return { ok, last };
  };

  // 1) Auf der Claim-Karte zu einem Land navigieren, dann Live-Welt öffnen
  await page.evaluate(() => zoomToLand(state.lands.find(l => l.name === 'Testdorf')));
  const viewBefore = await page.evaluate(() => ({ ...state.view }));
  const target1 = await page.evaluate(() => claimViewTarget());
  await page.click('.tab[data-tab=live]');
  await page.waitForFunction(() => document.querySelector('#liveFrame').getAttribute('src'), null, { timeout: 15000 });
  await waitUntil(() => frame() && frame().evaluate(() => !!(window.bluemap && window.bluemap.mapViewer && window.bluemap.mapViewer.map)), 30000);
  let r = await cameraAt(target1);
  check('BlueMap-Kamera steht auf der Position der Claim-Karte', r.ok, JSON.stringify({ soll: target1, ist: r.last }));
  check('Zoom passt (Abstand ' + target1.distance + ' Blöcke statt 1500)', r.ok && target1.distance < 1500, JSON.stringify(r.last));
  check('Richtige BlueMap-Karte (Oberwelt)', r.last && r.last.map === 'world', r.last && r.last.map);
  await frame().evaluate(() => { window.__planerTest = 'nicht neu geladen'; });

  // 2) In BlueMap selbst bewegen – beim nächsten Öffnen darf BlueMap NICHT zurückspringen
  await frame().evaluate(() => { const c = window.bluemap.mapViewer.controlsManager; c.position.x += 300; c.position.z += 200; });
  await page.waitForTimeout(500);
  const moved = await camera();
  await page.click('.tab[data-tab=claim]');
  const viewAfter = await page.evaluate(() => ({ ...state.view }));
  check('Claim-Karte behält ihre Ansicht nach dem 3D-Tab', near(viewAfter.x, viewBefore.x, 0.01) && near(viewAfter.z, viewBefore.z, 0.01) && viewAfter.zoom === viewBefore.zoom,
    JSON.stringify({ viewBefore, viewAfter }));
  await page.click('.tab[data-tab=live]');
  await page.waitForTimeout(800);
  const stay = await camera();
  check('Ohne Änderung der Claim-Karte bleibt BlueMap, wo man war', near(stay.x, moved.x, 1) && near(stay.z, moved.z, 1), JSON.stringify({ moved, stay }));

  // 3) Claim-Karte verschieben → BlueMap zieht nach, OHNE neu zu laden
  await page.click('.tab[data-tab=claim]');
  await page.evaluate(() => centerOn(-200, 300, state.view.zoom));
  const target2 = await page.evaluate(() => claimViewTarget());
  await page.click('.tab[data-tab=live]');
  r = await cameraAt(target2);
  check('Nach Verschieben der Claim-Karte folgt BlueMap', r.ok, JSON.stringify({ soll: target2, ist: r.last }));
  check('BlueMap wurde dafür nicht neu geladen', (await frame().evaluate(() => window.__planerTest)) === 'nicht neu geladen');

  // 4) Weit herausgezoomt → Abstand wird auf BlueMaps Grenze (2000) begrenzt
  await page.click('.tab[data-tab=claim]');
  await page.evaluate(() => centerOn(96, -32, 0.03));
  const target3 = await page.evaluate(() => claimViewTarget());
  check('Sehr weit herausgezoomt ergibt höchstens 2000 Blöcke Abstand', target3.distance === 2000, target3.distance);
  await page.click('.tab[data-tab=live]');
  r = await cameraAt(target3, 1, 6);
  check('BlueMap übernimmt den begrenzten Abstand', r.ok, JSON.stringify({ soll: target3, ist: r.last }));

  // 5) Dimension wechseln, während die 3D-Karte offen ist
  await page.evaluate(() => setDimension('minecraft:the_nether'));
  const netherOk = await waitUntil(async () => (await camera()).map === 'world_the_nether', 15000);
  check('Dimension wechseln wechselt auch die BlueMap-Karte', netherOk, (await camera()).map);
  await page.evaluate(() => setDimension('minecraft:overworld'));
  await waitUntil(async () => (await camera()).map === 'world', 15000);

  // 6) "In 3D zeigen" aus der Chunk-Infokarte
  await page.click('.tab[data-tab=claim]');
  await page.evaluate(() => { centerOn(96, 16, 2); showChunkCard({ x: 6, z: 1 }); });
  await page.click('#chunkCard button:has-text("In 3D zeigen")');
  r = await cameraAt({ x: 104, z: 24, distance: 150 });
  check('"In 3D zeigen" fährt nah an den Chunk (Abstand 150)', r.ok, JSON.stringify(r.last));
  const syncedView = await page.evaluate(() => ({ x: state.view.x, z: state.view.z, d: distanceForZoom(state.view.zoom) }));
  check('Die Claim-Karte steht danach auf demselben Punkt', near(syncedView.x, 104, 0.5) && near(syncedView.z, 24, 0.5) && near(syncedView.d, 150, 2), JSON.stringify(syncedView));

  // 7) Hinweis-Leiste: unentdecktes Gebiet wird erklärt, entdecktes nicht
  await page.click('.tab[data-tab=claim]');
  await page.evaluate(() => centerOn(5000, 5000, 0.8));
  await page.click('.tab[data-tab=live]');
  await page.waitForSelector('#liveStatus:not(.hidden)');
  let statusText = await page.textContent('#liveStatus');
  check('Unentdecktes Gebiet: Leiste erklärt die schwarze Fläche', statusText.includes('Hier war noch niemand'), statusText);
  check('Leiste sagt, dass Schwarz kein Fehler ist', statusText.includes('kein Fehler'), statusText);
  await page.click('.tab[data-tab=claim]');
  await page.evaluate(() => zoomToLand(state.lands.find(l => l.name === 'Testdorf')));
  await page.click('.tab[data-tab=live]');
  statusText = await page.textContent('#liveStatus');
  check('Entdecktes Gebiet: keine "noch niemand"-Warnung', !statusText.includes('Hier war noch niemand'), statusText);
  await page.evaluate(() => { state.blueMapStatus = { available: true, renderQueue: 3, renderThreads: 1, rendering: true, paused: false }; updateMapStatus('live'); });
  statusText = await page.textContent('#liveStatus');
  check('Render-Warteschlange wird angezeigt', statusText.includes('rendert gerade (3 Aufgaben'), statusText);
  await page.screenshot({ path: (process.env.SHOT_DIR || '.') + '/bluemap-sync.png' });

  // 8) BlueMap nicht erreichbar → klare Meldung statt schwarzer Fläche, Claim-Karte geht weiter
  const page2 = await context.newPage();
  const problems2 = collectProblems(page2);
  await context.route(origin + '/**', route => route.abort());
  await page2.goto(require('./lib').PLANNER_URL);
  await page2.waitForSelector('#appView:not(.hidden)', { timeout: 15000 });
  await page2.click('.tab[data-tab=live]');
  await page2.waitForSelector('#liveEmpty:not(.hidden) button', { timeout: 15000 });
  const errText = await page2.textContent('#liveEmpty');
  check('Nicht erreichbar: verständliche Meldung', errText.includes('nicht erreichbar'), errText);
  check('Nicht erreichbar: kein leeres iframe sichtbar', await page2.isHidden('#liveFrame'));
  await page2.click('.tab[data-tab=claim]');
  check('Claim-Karte funktioniert trotzdem', await page2.isVisible('#mapCanvas') && (await page2.evaluate(() => state.lands.length)) > 0);
  await context.unroute(origin + '/**');
  await page2.click('.tab[data-tab=live]');
  const recovered1 = await waitUntil(async () => page2.isVisible('#liveFrame'), 15000);
  check('Erneutes Öffnen des 3D-Tabs versucht es automatisch nochmal', recovered1);
  // Der Knopf "Erneut versuchen" (ohne Tab-Wechsel)
  await context.route(origin + '/**', route => route.abort());
  await page2.evaluate(() => { state.mapReach.live = null; state.framesLoaded.live = false; ensureFrame('live'); });
  await page2.waitForSelector('#liveEmpty:not(.hidden) button', { timeout: 15000 });
  await context.unroute(origin + '/**');
  await page2.click('#liveEmpty button:has-text("Erneut versuchen")');
  const recovered2 = await waitUntil(async () => page2.isVisible('#liveFrame'), 15000);
  check('Knopf "Erneut versuchen" lädt die Karte, sobald BlueMap wieder da ist', recovered2);

  const ownProblems = problems.concat(problems2.filter(p => !p.includes('net::ERR_FAILED')));
  check('Keine Fehler unserer Seite in der Konsole', ownProblems.length === 0, ownProblems.join(' | '));
  await browser.close();
  process.exit(summary('BlueMap-Abgleich') ? 1 : 0);
})().catch(e => { console.error('ABBRUCH:', e.stack || e.message); process.exit(1); });
