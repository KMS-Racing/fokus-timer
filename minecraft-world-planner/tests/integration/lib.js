// Gemeinsame Helfer für die Browser-Tests (Playwright).
// Die Tests laufen gegen einen ECHTEN Minecraft-Testserver mit der Mod (siehe tests/README.md).
'use strict';

function loadPlaywright() {
  const candidates = [process.env.PLAYWRIGHT_MODULE, 'playwright', '/opt/node-tools/node_modules/playwright'].filter(Boolean);
  for (const c of candidates) {
    try { return require(c); } catch { /* nächsten Pfad probieren */ }
  }
  throw new Error('Playwright nicht gefunden. Installiere es mit "npm install playwright" oder setze PLAYWRIGHT_MODULE.');
}

const PLANNER_URL = process.env.PLANNER_URL || 'http://127.0.0.1:8765/';

async function launch() {
  const { chromium } = loadPlaywright();
  const opts = {
    // WebGL im Testbrowser ohne Grafikkarte, damit BlueMap überhaupt startet
    args: ['--no-proxy-server', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  };
  if (process.env.CHROMIUM) opts.executablePath = process.env.CHROMIUM;
  return chromium.launch(opts);
}

function makeChecker() {
  const results = [];
  const check = (name, cond, info = '') => {
    results.push({ name, ok: !!cond });
    console.log((cond ? 'OK   ' : 'FAIL ') + name + (cond ? '' : '  -> ' + info));
  };
  const summary = label => {
    const ok = results.filter(r => r.ok).length;
    console.log(`\n${label}: ${ok} bestanden, ${results.length - ok} fehlgeschlagen, 0 übersprungen`);
    return results.length - ok;
  };
  return { check, summary };
}

/** Wartet, bis fn() true liefert (oder gibt nach timeout false zurück). */
async function waitUntil(fn, timeout = 10000, step = 200) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    try { if (await fn()) return true; } catch { /* noch nicht bereit */ }
    await new Promise(r => setTimeout(r, step));
  }
  return false;
}

/** Fehlermeldungen aus der Browser-Konsole sammeln – aber nur von unserer Seite, nicht aus dem BlueMap-iframe. */
function collectProblems(page) {
  const problems = [];
  page.on('console', m => {
    const url = m.location().url || '';
    if ((m.type() === 'error' || m.type() === 'warning') && (!url || url.startsWith(PLANNER_URL))) {
      problems.push(`${m.type()}: ${m.text()} @ ${url}`);
    }
  });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  return problems;
}

async function login(page, code) {
  await page.goto(PLANNER_URL + '#login=' + code);
  await page.waitForSelector('#appView:not(.hidden)', { timeout: 15000 });
}

module.exports = { PLANNER_URL, launch, makeChecker, waitUntil, collectProblems, login };
