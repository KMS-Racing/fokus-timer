// Gemeinsamer Datenstand der Seite. Alle Daten kommen vom Server – hier wird nichts erfunden.
// Bereiche melden Änderungen über on(key, fn); die Ansichten zeichnen sich dann neu.
import { api, enc } from './api.js';

export const store = {
  me: null, meta: null, world: null,
  lands: [], landsVersion: -1,
  markers: [], teams: [], projects: [],
  trades: [], tradeUnit: '', serverOffset: 0, // serverOffset = Serverzeit - Browserzeit
  people: [], onlineHidden: false,
  players: [], playersHidden: false,
  activity: [], prefs: { favorites: {} },
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

function mark(key) {
  store.loaded[key] = Date.now();
  emit(key);
}

export const load = {
  async me() { store.me = await api('api/me'); mark('me'); },
  async meta() { store.meta = await api('api/meta'); mark('meta'); },
  async world() { store.world = await api('api/world'); mark('world'); },
  async lands() {
    const r = await api('api/lands');
    store.lands = r.lands || [];
    store.landsVersion = r.version;
    mark('lands');
  },
  async markers() { store.markers = (await api('api/markers')).markers || []; mark('markers'); },
  async teams() { store.teams = (await api('api/teams')).teams || []; mark('teams'); },
  async projects() { store.projects = (await api('api/projects')).projects || []; mark('projects'); },
  async trades() {
    const r = await api('api/trades');
    store.trades = r.listings || [];
    store.tradeUnit = r.unit || '';
    store.serverOffset = (r.serverTime || Date.now()) - Date.now();
    mark('trades');
  },
  async people() {
    const r = await api('api/people');
    store.people = r.players || [];
    store.onlineHidden = !!r.onlineHidden;
    mark('people');
  },
  async players() {
    const r = await api('api/players');
    store.players = r.players || [];
    store.playersHidden = !!r.hidden;
    mark('players');
  },
  async activity() { store.activity = (await api('api/activity?limit=100')).events || []; mark('activity'); },
  async prefs() { store.prefs = await api('api/prefs'); mark('prefs'); },
  async knownPlayers() { store.knownPlayers = (await api('api/known-players')).players || []; mark('knownPlayers'); },
  async blueMapStatus() { store.blueMapStatus = await api('api/bluemap-status'); mark('blueMapStatus'); },
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

export async function toggleFavorite(kind, id) {
  store.prefs = await api('api/prefs/favorite', { kind, id, on: !isFavorite(kind, id) });
  emit('prefs');
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
