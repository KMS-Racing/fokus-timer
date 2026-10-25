// World Planner – Start der Webseite: Login, Navigation, Aktualisieren der Daten.
// Spricht mit der Mod über /api/... (siehe ApiServer.java). Spielertexte werden immer als Text eingesetzt, nie als HTML.
import { api, session, setApiBase, setToken } from './api.js';
import * as bluemap from './bluemap.js';
import * as coords from './coords.js';
import * as mapModule from './map.js';
import { navigate, onRoute, parseHash } from './router.js';
import { setupSearch, searchAll } from './search.js';
import { emit, load, loadAll, myTeams, on, store } from './store.js';
import { $, el, fill, safeUrl, toast } from './util.js';
import claims from './views/claims.js';
import dashboard, { todoItems } from './views/dashboard.js';
import karte, { centerOnSpawn } from './views/karte.js';
import live from './views/live.js';
import markers from './views/markers.js';
import players from './views/players.js';
import projects from './views/projects.js';
import settings, { applyTheme } from './views/settings.js';
import teams from './views/teams.js';
import trade from './views/trade.js';

const VIEWS = {
  dashboard, karte, live, seed: live, claims, marker: markers, teams, handel: trade, projekte: projects, spieler: players, einstellungen: settings,
};
const NAV = [
  ['dashboard', 'Dashboard', '⌂'], ['karte', 'Karte', '▦'], ['live', 'Live-Welt', '◈'], ['claims', 'Claims', '⚑'], ['marker', 'Marker', '◆'],
  ['teams', 'Teams', '⚔'], ['handel', 'Handel', '⚖'], ['projekte', 'Projekte', '⚒'], ['spieler', 'Spieler', '☺'], ['einstellungen', 'Einstellungen', '⚙'],
];

let cleanup = null;
let current = null;
let started = false;

// Wird die Seite unter /planer statt /planer/ geöffnet, würden relative Adressen falsch auflösen.
(function fixTrailingSlash() {
  const p = location.pathname;
  if (location.protocol.startsWith('http') && p !== '/' && !p.endsWith('/') && !p.split('/').pop().includes('.')) {
    location.replace(p + '/' + location.search + location.hash);
  }
})();

// ------------------------------------------------------------------ Navigation

function buildNav() {
  fill($('sideNav'), NAV.map(([name, text, icon]) => el('a', { href: '#/' + name, 'data-route': name, class: 'nav-link' },
    el('span', { class: 'nav-icon', 'aria-hidden': 'true', text: icon }), el('span', { class: 'nav-text', text }), el('span', { class: 'badge hidden', 'data-badge': name }))));
}

function updateBadges() {
  const me = store.me || {};
  const counts = {
    claims: store.lands.filter(l => l.invitedMe).length,
    teams: store.teams.filter(t => t.myInvite || (t.pendingTransfer && t.pendingTransfer.to.uuid === me.uuid)).length
      + myTeams().filter(t => t.can && t.can.decide).reduce((n, t) => n + (t.applications || []).filter(a => a.status === 'pending').length, 0),
    handel: store.trades.filter(l => (l.status === 'active' && l.iBid && !l.iAmHighest)
      || (l.status === 'sold' && l.handover && l.handover.status === 'pending' && ((l.mine && !l.handover.sellerConfirmedAt) || (l.iWon && !l.handover.buyerConfirmedAt)))
      || (me.admin && l.handover && l.handover.status === 'disputed')).length,
    dashboard: 0,
  };
  counts.dashboard = todoItems().length;
  for (const b of document.querySelectorAll('[data-badge]')) {
    const n = counts[b.dataset.badge] || 0;
    b.textContent = n > 99 ? '99+' : String(n);
    b.classList.toggle('hidden', n === 0);
  }
}

async function show(route) {
  if (!store.me) return;
  const view = VIEWS[route.name] || dashboard;
  if (cleanup) { try { cleanup(); } catch (e) { console.error(e); } cleanup = null; }
  const stage = view.stage || null;
  $('stage').classList.toggle('hidden', !stage);
  $('viewRoot').classList.toggle('hidden', !!stage);
  $('claimPane').classList.toggle('hidden', stage !== 'claim');
  $('liveHost').classList.toggle('hidden', stage !== 'live');
  const host = stage === 'claim' ? $('claimPane') : stage === 'live' ? $('liveHost') : $('viewRoot');
  if (!stage) { fill(host); window.scrollTo(0, 0); }
  current = route.name;
  document.title = `${view.title} · World Planner`;
  const navName = route.name === 'seed' ? 'live' : (VIEWS[route.name] ? route.name : 'dashboard');
  for (const a of document.querySelectorAll('.nav-link')) {
    const active = a.dataset.route === navName;
    a.classList.toggle('active', active);
    if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  }
  document.body.classList.remove('nav-open');
  try {
    const c = await view.render(host, route);
    cleanup = typeof c === 'function' ? c : null;
  } catch (e) {
    console.error(e);
    fill(host, el('div', { class: 'empty' }, el('p', { text: 'Diese Ansicht konnte nicht geladen werden: ' + e.message })));
  }
}

// ------------------------------------------------------------------ Login

function showLogin(message = '') {
  $('appView').classList.add('hidden');
  $('loginView').classList.remove('hidden');
  fill($('accountBox'));
  $('loginError').textContent = message;
  $('apiBaseInput').value = session.apiBase;
  if (!location.protocol.startsWith('http')) $('advancedBox').open = true;
}

function loggedOut(message) {
  setToken(null);
  store.me = null;
  stopPolling();
  if (cleanup) { cleanup(); cleanup = null; }
  showLogin(message || 'Bitte neu einloggen.');
}
session.onUnauthorized = loggedOut;

async function login(code) {
  $('loginError').textContent = '';
  try {
    const r = await api('api/login', { code: code.trim() });
    setToken(r.token);
    $('loginCode').value = '';
    await startApp();
    toast('Willkommen, ' + r.name + '!');
  } catch (e) {
    $('loginError').textContent = e.message;
  }
}

async function logout() {
  try { await api('api/logout', {}); } catch { /* egal */ }
  // Der nächste Login (vielleicht ein anderer Spieler am selben Gerät) beginnt auf dem Dashboard
  history.replaceState(null, '', location.pathname + location.search + '#/dashboard');
  loggedOut('Du bist abgemeldet.');
}

function renderAccount() {
  const me = store.me;
  fill($('accountBox'),
    el('span', { class: 'muted small hide-mobile' }, 'Eingeloggt als '),
    el('strong', { text: me.name }),
    me.admin ? el('span', { class: 'pill gold', text: 'Admin' }) : null,
    el('button', { class: 'small', onclick: logout }, 'Abmelden'));
}

// ------------------------------------------------------------------ Start

async function startApp() {
  await load.me();
  $('loginView').classList.add('hidden');
  $('appView').classList.remove('hidden');
  renderAccount();
  await loadAll(['meta', 'world', 'lands', 'markers', 'players', 'knownPlayers', 'teams', 'prefs']);
  // Ohne gespeicherte Ansicht: zum eigenen Spieler oder zum Spawn statt zu 0/0
  mapModule.mapElement();
  if (!mapModule.map.viewRestored) {
    const p = store.players.find(x => x.uuid === store.me.uuid);
    if (p) {
      await mapModule.setDimension(p.dimension); // baut auch die Länder dieser Dimension neu auf
      mapModule.centerOn(p.x, p.z, 0.8);
    } else await centerOnSpawn();
  }
  mapModule.loadDiscovered(true).catch(() => {});
  Promise.allSettled([load.projects(), load.trades(), load.people(), load.activity(), load.blueMapStatus()]);
  startPolling();
  started = true;
  await show(parseHash());
}

let timers = [];
function startPolling() {
  stopPolling();
  const every = (ms, fn) => timers.push(setInterval(() => {
    if (!document.hidden && store.me) Promise.resolve(fn()).catch(() => { /* nächster Versuch */ });
  }, ms));
  const visible = (...names) => names.includes(current);
  every(3000, () => (visible('karte', 'live', 'seed', 'dashboard', 'spieler') ? load.players() : null));
  every(15000, () => (visible('karte', 'live', 'seed', 'dashboard', 'spieler') ? null : load.players()));
  every(10000, () => Promise.all([load.lands(), load.markers()]));
  every(5000, () => (visible('handel') ? load.trades() : null));
  every(30000, () => (visible('handel') ? null : load.trades()));
  every(20000, () => Promise.all([load.teams(), load.projects(), load.me()]));
  every(30000, () => (visible('dashboard', 'claims') ? load.activity() : null));
  every(5000, () => (visible('live') ? load.blueMapStatus().then(() => bluemap.updateMapStatus('live')) : null));
  every(60000, () => Promise.all([mapModule.loadDiscovered(), load.people(), load.knownPlayers(), load.blueMapStatus(), load.world()]));
}

function stopPolling() {
  timers.forEach(clearInterval);
  timers = [];
}

function setupUi() {
  buildNav();
  $('loginForm').addEventListener('submit', e => { e.preventDefault(); login($('loginCode').value); });
  $('apiBaseForm').addEventListener('submit', e => {
    e.preventDefault();
    const value = $('apiBaseInput').value.trim();
    if (value && !safeUrl(value)) { $('loginError').textContent = 'Die Adresse muss mit http:// oder https:// beginnen.'; return; }
    setApiBase(value);
    toast(value ? 'Server-Adresse gespeichert.' : 'Server-Adresse zurückgesetzt.');
  });
  $('navToggle').addEventListener('click', () => document.body.classList.toggle('nav-open'));
  $('navBackdrop').addEventListener('click', () => document.body.classList.remove('nav-open'));
  $('sideNav').addEventListener('click', e => { if (e.target.closest('a')) document.body.classList.remove('nav-open'); });
  setupSearch($('searchInput'), $('searchResults'));
  for (const k of ['lands', 'teams', 'trades', 'projects', 'me']) on(k, updateBadges);
  // Daten der Karte nach einem Dimensionswechsel
  mapModule.map.onDimension = () => { if (current === 'live' || current === 'seed') bluemap.openBlueMap(current); emit('discovered'); };
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && store.me) Promise.allSettled([load.players(), load.lands(), load.trades()]);
  });
  onRoute(route => { if (started) show(route); });
}

// Für die Browser-Tests (Playwright) und zum Ausprobieren in der Konsole. Enthält nichts, was die Seite nicht ohnehin kann.
window.planner = { store, map: mapModule, bluemap, coords, api, navigate, searchAll, load };

async function init() {
  applyTheme();
  setupUi();
  const match = location.hash.match(/login=([A-Za-z0-9-]+)/);
  if (match) {
    history.replaceState(null, '', location.pathname + location.search + '#/dashboard');
    showLogin();
    $('loginCode').value = match[1];
    await login(match[1]);
    return;
  }
  if (session.token) {
    try {
      await startApp();
    } catch (e) {
      if (session.token) showLogin(e.message); // bei 401 hat loggedOut() schon die Login-Seite gezeigt
    }
    return;
  }
  showLogin();
}

init();
