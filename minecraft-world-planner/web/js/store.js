// Gemeinsamer Datenstand der Seite. Alle Daten kommen vom Server – hier wird nichts erfunden.
// Bereiche melden Änderungen über on(key, fn); die Ansichten zeichnen sich dann neu.
import { api, enc } from './api.js';

export const store = {
  me: null, meta: null, world: null,
  lands: [], landsVersion: -1,
  markers: [], teams: [], projects: [], events: [], recap: null,
  trades: [], tradeUnit: '', serverOffset: 0, // serverOffset = Serverzeit - Browserzeit
  people: [], onlineHidden: false,
  players: [], playersHidden: false,
  activity: [], prefs: { favorites: {} }, sessions: [],
  knownPlayers: [],
  blueMapStatus: null,
  loaded: {},
};

const listeners = new Map();

export function on(key, fn) {
  if (!listeners.has(key)) listeners.set(key, new Set());
  listeners.get(key).add(fn);
  return () => listeners.get(key).delete(fn);
}

export function emit(key) {
  for (const fn of listeners.get(key) || []) {
    try { fn(store); } catch (e) { console.error(e); }
  }
}

const fingerprints = {};

/**
 * Meldet eine Änderung nur, wenn sich die Daten wirklich geändert haben. Sonst würden Seiten alle paar Sekunden
 * neu gezeichnet – eine halb ausgefüllte Auswahl könnte dabei zurückspringen.
 */
function mark(key, data) {
  store.loaded[key] = Date.now();
  const fp = JSON.stringify(data);
  if (fingerprints[key] === fp) return;
  fingerprints[key] = fp;
  emit(key);
}

export const load = {
  async me() { store.me = await api('api/me'); mark('me', store.me); },
  async meta() {
    store.meta = await api('api/meta');
    const { serverTime, ...rest } = store.meta; // die Serverzeit ändert sich bei jeder Anfrage
    mark('meta', rest);
  },
  async world() { store.world = await api('api/world'); mark('world', store.world); },
  async lands() {
    const r = await api('api/lands');
    store.lands = r.lands || [];
    store.landsVersion = r.version;
    mark('lands', r);
  },
  async markers() { store.markers = (await api('api/markers')).markers || []; mark('markers', store.markers); },
  async teams() { store.teams = (await api('api/teams')).teams || []; mark('teams', store.teams); },
  async projects() { store.projects = (await api('api/projects')).projects || []; mark('projects', store.projects); },
  async recap() { store.recap = await api('api/recap'); const { from, to, ...rest } = store.recap; mark('recap', rest); },
  async events() {
    if (store.meta && store.meta.gameFeatures && store.meta.gameFeatures.events === false) { store.events = []; mark('events', []); return; }
    store.events = (await api('api/events')).events || [];
    mark('events', store.events);
  },
  async trades() {
    const r = await api('api/trades');
    store.trades = r.listings || [];
    store.tradeUnit = r.unit || '';
    store.serverOffset = (r.serverTime || Date.now()) - Date.now();
    mark('trades', [r.unit, r.listings]);
  },
  async people() {
    const r = await api('api/people');
    store.people = r.players || [];
    store.onlineHidden = !!r.onlineHidden;
    mark('people', r);
  },
  async players() {
    const r = await api('api/players');
    store.players = r.players || [];
    store.playersHidden = !!r.hidden;
    mark('players', [r.players, r.hidden]);
  },
  async activity() { store.activity = (await api('api/activity?limit=100')).events || []; mark('activity', store.activity); },
  async prefs() { store.prefs = await api('api/prefs'); mark('prefs', store.prefs); },
  async sessions() { store.sessions = (await api('api/sessions')).sessions || []; mark('sessions', store.sessions); },
  async knownPlayers() { store.knownPlayers = (await api('api/known-players')).players || []; mark('knownPlayers', store.knownPlayers); },
  async blueMapStatus() { store.blueMapStatus = await api('api/bluemap-status'); mark('blueMapStatus', store.blueMapStatus); },
};

/** Lädt mehrere Bereiche parallel; ein einzelner Fehler stoppt die anderen nicht. */
export async function loadAll(keys) {
  const results = await Promise.allSettled(keys.map(k => load[k]()));
  const failed = results.find(r => r.status === 'rejected');
  if (failed) throw failed.reason;
}

// ------------------------------------------------------------------ Abfragen

export const byId = (list, id) => list.find(x => x.id === id) || null;
export const landById = id => byId(store.lands, id);
export const teamById = id => byId(store.teams, id);
export const projectById = id => byId(store.projects, id);
export const tradeById = id => byId(store.trades, id);
export const serverNow = () => Date.now() + store.serverOffset;

export function myTeams() {
  return store.teams.filter(t => t.myRole);
}

export function teamTag(teamId) {
  const t = teamById(teamId);
  return t ? t.tag : null;
}

export function isFavorite(kind, id) {
  return ((store.prefs.favorites || {})[kind] || []).includes(id);
}

/** Schalter fürs Spiel (Nachrichten, Land-Anzeige, Todespunkt) – gelten sofort im Spiel. */
export async function setGameSwitch(key, on) {
  store.prefs = await api('api/prefs/game', { key, on });
  mark('prefs', store.prefs);
}

export async function toggleFavorite(kind, id) {
  store.prefs = await api('api/prefs/favorite', { kind, id, on: !isFavorite(kind, id) });
  mark('prefs', store.prefs);
}

/** Typ- und Kategorie-Namen vom Server (eine Quelle für alle Auswahl-Listen). */
export function label(group, key) {
  const m = store.meta && store.meta[group];
  return (m && m[key]) || key;
}

export function options(group) {
  const m = (store.meta && store.meta[group]) || {};
  return Object.entries(m);
}

export { enc };
