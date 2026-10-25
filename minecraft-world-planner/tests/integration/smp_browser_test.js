// Browser-Test der SMP-Funktionen mit zwei gleichzeitig eingeloggten Spielern (zwei Browser-Profile):
// Team gründen und einladen, Rolle ändern, Team-Projekt mit Aufgaben, Festpreis-Kauf mit Übergabe,
// Auktion mit Gebot, Team auflösen ohne Datenverlust.
// Aufruf: node smp_browser_test.js <code-A> <code-B> <name-B>   (beide keine OPs)
'use strict';
const { launch, makeChecker, collectProblems, login } = require('./lib');

const [codeA, codeB, nameB] = process.argv.slice(2);
if (!codeA || !codeB || !nameB) { console.error('Aufruf: node smp_browser_test.js <code-A> <code-B> <name-B>'); process.exit(2); }
const run = Math.random().toString(36).slice(2, 6).toUpperCase().replace(/[^A-Z]/g, 'X');

async function submitDialog(page) {
  await page.click('#modal button[type=submit]');
  await page.waitForSelector('#modal', { state: 'hidden' });
}

async function go(page, hash) {
  await page.evaluate(h => { location.hash = h; }, hash);
  await page.waitForFunction(h => location.hash === h, hash);
  await page.waitForTimeout(250);
}

/** Teams früherer, abgebrochener Testläufe aufräumen (nur „Testteam …“). */
async function cleanup(page) {
  await page.evaluate(async () => {
    const me = await planner.api('api/me');
    for (const t of me.teams) {
      if (!t.name.startsWith('Testteam ')) throw new Error('Testspieler ist in einem echten Team: ' + t.name);
      if (t.role === 'owner') await planner.api(`api/teams/${t.id}/dissolve`, { confirmName: t.name });
      else await planner.api(`api/teams/${t.id}/leave`, {});
    }
  });
}

(async () => {
  const { check, summary } = makeChecker();
  const browser = await launch();
  const a = await (await browser.newContext({ viewport: { width: 1400, height: 950 } })).newPage();
  const b = await (await browser.newContext({ viewport: { width: 1400, height: 950 } })).newPage();
  const probA = collectProblems(a), probB = collectProblems(b);
  await login(a, codeA);
  await login(b, codeB);
  await cleanup(a);
  await cleanup(b);
  const nameA = await a.evaluate(() => planner.store.me.name);

  // 1) Team gründen
  const teamName = 'Testteam ' + run;
  const tag = 'T' + run.slice(0, 3);
  await go(a, '#/teams');
  await a.click('button:has-text("Team gründen")');
  await a.fill('#modal input[name=name]', teamName);
  await a.fill('#modal input[name=tag]', tag.toLowerCase());
  await a.fill('#modal textarea[name=description]', 'Wir bauen <b>Burgen</b>');
  await submitDialog(a);
  await a.waitForFunction(t => document.querySelector('#viewRoot h1') && document.querySelector('#viewRoot h1').textContent.includes(t), tag, { timeout: 8000 });
  check('Team gegründet, Kürzel groß', (await a.textContent('#viewRoot h1')).includes(`[${tag}] ${teamName}`), await a.textContent('#viewRoot h1'));
  check('A ist Owner', (await a.textContent('#viewRoot')).includes('Du: Owner'));
  check('Beschreibung mit HTML wird als Text gezeigt', (await a.locator('#viewRoot .lead b').count()) === 0 && (await a.textContent('#viewRoot .lead')).includes('<b>Burgen</b>'));
  check('Hinweis: Kürzel erscheint im Spiel', (await a.textContent('#chatTagNote')).includes(`[${tag}] vor den Namen`), await a.textContent('#chatTagNote'));
  const teamUrl = a.url().split('#')[1];

  // 2) B einladen – B ist erst nach Annahme im Team
  await a.fill('input[aria-label="Spieler einladen"]', nameB);
  await a.click('button:has-text("Einladen")');
  await a.waitForFunction(n => document.querySelector('#viewRoot').textContent.includes(n + ' – offen'), nameB, { timeout: 8000 });
  check('Einladung erscheint als offen', true);
  await go(b, '#/teams');
  await b.waitForSelector('#teamInvites', { timeout: 8000 });
  check('B sieht die Team-Einladung', (await b.textContent('#teamInvites')).includes(teamName));
  check('B ist noch nicht Mitglied', !(await b.evaluate(t => planner.store.teams.some(x => x.tag === t && x.myRole), tag)));
  check('Navigation zeigt eine Zahl bei Teams', (await b.textContent('[data-badge=teams]')).trim() === '1');
  await b.locator('#teamInvites button', { hasText: 'Annehmen' }).click();
  await b.waitForFunction(t => planner.store.teams.some(x => x.tag === t && x.myRole === 'member'), tag, { timeout: 8000 });
  check('B ist nach dem Annehmen Mitglied', true);

  // 3) A macht B zum Moderator (Auswahl in der Mitgliederliste)
  await go(a, '#' + teamUrl);
  await a.evaluate(() => planner.load.teams());
  await a.waitForSelector(`select[aria-label="Rolle von ${nameB}"]`, { timeout: 8000 });
  await a.selectOption(`select[aria-label="Rolle von ${nameB}"]`, 'moderator');
  await b.waitForFunction(t => planner.load.teams().then(() => planner.store.teams.some(x => x.tag === t && x.myRole === 'moderator')), tag, { timeout: 10000 });
  check('B ist jetzt Moderator', true);
  check('A kann B keinen Owner-Rang geben (keine Option)', (await a.locator(`select[aria-label="Rolle von ${nameB}"] option[value=owner]`).count()) === 0);

  // 4) Team-Projekt mit Aufgaben
  await go(a, '#/projekte');
  await a.click('button:has-text("Neues Projekt")');
  const projectName = 'Burg ' + run;
  await a.fill('#modal input[name=name]', projectName);
  await a.fill('#modal textarea[name=tasks]', 'Fundament\nMauern\nTurm');
  const teamId = await a.evaluate(t => planner.store.teams.find(x => x.tag === t).id, tag);
  await a.selectOption('#modal select[name=teamId]', teamId);
  await a.selectOption('#modal select[name=visibility]', 'team');
  await submitDialog(a);
  await a.waitForFunction(n => document.querySelector('#viewRoot h1') && document.querySelector('#viewRoot h1').textContent.includes(n), projectName, { timeout: 8000 });
  check('Projekt angelegt mit 3 Aufgaben', (await a.locator('#taskList li').count()) === 3);
  await a.locator('#taskList input[type=checkbox]').first().check();
  await a.waitForFunction(() => document.querySelector('#viewRoot').textContent.includes('1/3'), null, { timeout: 8000 });
  check('Aufgabe abhaken: Fortschritt 1/3', true);
  await go(b, '#/projekte');
  await b.evaluate(() => planner.load.projects());
  await b.waitForFunction(n => document.querySelector('#projectList') && document.querySelector('#projectList').textContent.includes(n), projectName, { timeout: 8000 });
  check('Teammitglied B sieht das Team-Projekt', true);
  await b.locator('#projectList a', { hasText: projectName }).click();
  await b.waitForSelector('#taskList');
  check('B darf mitarbeiten (Häkchen nicht gesperrt)', await b.locator('#taskList input[type=checkbox]').nth(1).isEnabled());

  // 5) Festpreis: A verkauft, B kauft, beide bestätigen die Übergabe
  await go(a, '#/handel');
  check('Handel erklärt den Web-Modus', (await a.textContent('#viewRoot .notice')).includes('überträgt keine Items'));
  await a.click('button:has-text("Neues Angebot")');
  await a.selectOption('#modal select[name=kind]', 'fixed');
  const title = 'Elytra ' + run;
  await a.fill('#modal input[name=title]', title);
  await a.selectOption('#modal select[name=category]', 'armor');
  await a.fill('#modal input[name=itemName]', 'Elytra');
  await a.fill('#modal input[name=price]', '5');
  check('Bei Festpreis kein Startgebot-Feld', await a.locator('#modal input[name=startPrice]').isHidden());
  await submitDialog(a);
  await a.waitForFunction(t => document.querySelector('#viewRoot h1') && document.querySelector('#viewRoot h1').textContent.includes(t), title, { timeout: 8000 });
  const listingHash = '#' + a.url().split('#')[1];
  await go(b, listingHash);
  await b.evaluate(() => planner.load.trades());
  await b.waitForSelector('#buyBtn', { timeout: 8000 });
  await b.click('#buyBtn');
  await b.waitForSelector('#modal[open]');
  const confirmText = await b.textContent('#modal');
  check('Kauf-Dialog erklärt die Pflicht im Spiel', confirmText.includes('verpflichtest dich') && confirmText.includes(nameA), confirmText);
  await submitDialog(b);
  await b.waitForSelector('#handoverBox', { timeout: 8000 });
  check('B: gekauft, Übergabe offen', (await b.textContent('#viewRoot')).includes('Du hast gekauft') && (await b.textContent('#handoverBox')).includes('Übergabe offen'));
  await go(b, '#/dashboard');
  await b.waitForFunction(t => document.querySelector('#todoCard') && document.querySelector('#todoCard').textContent.includes('Du hast »' + t + '« gekauft'), title, { timeout: 8000 });
  check('B: Dashboard erinnert an die Übergabe', true);
  await go(a, listingHash);
  await a.evaluate(() => planner.load.trades());
  await a.waitForSelector('#confirmHandoverBtn', { timeout: 8000 });
  await a.click('#confirmHandoverBtn');
  await submitDialog(a);
  await a.waitForFunction(() => document.querySelector('#handoverBox').textContent.includes('✓ Verkäufer'), null, { timeout: 8000 });
  check('A hat bestätigt – noch nicht abgeschlossen', (await a.textContent('#handoverBox')).includes('Übergabe offen'));
  await go(b, listingHash);
  await b.evaluate(() => planner.load.trades());
  await b.waitForSelector('#confirmHandoverBtn', { timeout: 8000 });
  await b.click('#confirmHandoverBtn');
  await submitDialog(b);
  await b.waitForFunction(() => document.querySelector('#handoverBox').textContent.includes('Übergabe bestätigt'), null, { timeout: 8000 });
  check('Beide bestätigt: Übergabe abgeschlossen', true);

  // 6) Auktion: B bietet, A sieht wer, andere nicht
  await go(a, '#/handel');
  await a.click('button:has-text("Neues Angebot")');
  await a.selectOption('#modal select[name=kind]', 'auction');
  const auctionTitle = 'Dreizack ' + run;
  await a.fill('#modal input[name=title]', auctionTitle);
  await a.selectOption('#modal select[name=category]', 'weapons');
  await a.fill('#modal input[name=itemName]', 'Dreizack');
  await a.fill('#modal input[name=startPrice]', '3');
  await a.selectOption('#modal select[name=durationHours]', '1');
  await submitDialog(a);
  await a.waitForFunction(t => document.querySelector('#viewRoot h1') && document.querySelector('#viewRoot h1').textContent.includes(t), auctionTitle, { timeout: 8000 });
  const auctionHash = '#' + a.url().split('#')[1];
  await go(b, auctionHash);
  await b.evaluate(() => planner.load.trades());
  await b.waitForSelector('#bidAmount', { timeout: 8000 });
  check('Mindestgebot ist vorausgefüllt', (await b.inputValue('#bidAmount')) === '3');
  await b.click('button:has-text("Bieten")');
  await b.waitForSelector('#modal[open]');
  const bidText = await b.textContent('#modal');
  check('Gebots-Dialog nennt die Pflicht und die Verlängerung', bidText.includes('verpflichtest du dich') && bidText.includes('2 Minuten'), bidText);
  await submitDialog(b);
  await b.waitForFunction(() => document.querySelector('#viewRoot .big-number') && document.querySelector('#viewRoot .big-number').textContent.startsWith('3'), null, { timeout: 8000 });
  check('Höchstgebot 3 angezeigt', true);
  check('B ist Höchstbietender', (await b.textContent('#viewRoot')).includes('Du bist Höchstbietender'));
  check('Höchstbietender bekommt kein Gebotsfeld (sich selbst überbieten geht nicht)', (await b.locator('#bidAmount').count()) === 0);
  await go(a, auctionHash);
  await a.evaluate(() => planner.load.trades());
  await a.waitForFunction(n => document.querySelector('ol.bids') && document.querySelector('ol.bids').textContent.includes(n), nameB, { timeout: 8000 });
  check('Verkäufer sieht, wer geboten hat', true);
  check('Verkäufer kann die Auktion mit Gebot nicht mehr zurückziehen', !(await a.isVisible('button:has-text("Angebot zurückziehen")')));

  // 7) Team auflösen: nur mit eingetipptem Namen, Projekt bleibt
  await go(a, '#' + teamUrl);
  await a.click('button:has-text("Team auflösen")');
  await a.waitForSelector('#modal[open]');
  check('Auflösen-Knopf gesperrt, bis der Name stimmt', await a.isDisabled('#modal button[type=submit]'));
  await a.fill('#modal input[aria-label="Zur Bestätigung eintippen"]', teamName);
  await submitDialog(a);
  await a.waitForFunction(() => location.hash === '#/teams', null, { timeout: 8000 });
  check('Team aufgelöst', !(await a.evaluate(t => planner.store.teams.some(x => x.tag === t), tag)));
  await go(a, '#/projekte');
  await a.evaluate(() => planner.load.projects());
  await a.click('button:has-text("Alle")');
  await a.waitForFunction(n => document.querySelector('#projectList') && document.querySelector('#projectList').textContent.includes(n), projectName, { timeout: 8000 });
  check('Projekt bleibt nach dem Auflösen erhalten', (await a.locator('#projectList .item', { hasText: projectName }).textContent()).includes('Nur Beteiligte'));

  // Aufräumen: Projekt löschen (Auktion läuft nach 1 Stunde ab)
  await a.evaluate(async n => { const p = planner.store.projects.find(x => x.name === n); if (p) await planner.api(`api/projects/${p.id}/delete`, {}); }, projectName);
  const own = probA.concat(probB);
  check('Keine Fehler unserer Seite in der Konsole', own.length === 0, own.join(' | '));
  await browser.close();
  process.exit(summary('SMP-Browser') ? 1 : 0);
})().catch(e => { console.error('ABBRUCH:', e.stack || e.message); process.exit(1); });
