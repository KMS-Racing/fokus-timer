// Verbindung zur Mod (/api/...). Siehe ApiServer.java für alle Adressen.
import { requestId, storageGet, storageSet } from './util.js';

const KEYS = { token: 'mcwp-token-v2', apiBase: 'mcwp-api-base-v2' };

export const session = {
  token: storageGet(KEYS.token),
  apiBase: storageGet(KEYS.apiBase) || '',
  onUnauthorized: null, // wird von main.js gesetzt
};

export function setToken(token) {
  session.token = token;
  storageSet(KEYS.token, token);
}

export function setApiBase(value) {
  session.apiBase = value;
  storageSet(KEYS.apiBase, value || null);
}

function apiUrl(path) {
  let base = session.apiBase;
  if (base) { if (!base.endsWith('/')) base += '/'; }
  else if (location.protocol.startsWith('http')) base = location.href;
  else throw new Error('Keine Server-Adresse eingestellt (siehe „Erweitert“ beim Login).');
  return new URL(path, base).href;
}

/** GET ohne body, POST mit body. Wirft Error mit der deutschen Meldung des Servers. */
export async function api(path, body) {
  const opts = { method: body === undefined ? 'GET' : 'POST', headers: { Accept: 'application/json' }, cache: 'no-store' };
  const usedToken = session.token;
  if (usedToken) opts.headers.Authorization = 'Bearer ' + usedToken;
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(apiUrl(path), opts);
  } catch (e) {
    throw new Error(e.message.includes('Server-Adresse') ? e.message : 'Server nicht erreichbar. Läuft der Minecraft-Server?');
  }
  let data = {};
  try { data = await res.json(); } catch { /* keine JSON-Antwort */ }
  // Nur abmelden, wenn das 401 zum aktuellen Login gehört – eine alte Anfrage soll keinen neuen Login beenden.
  if (res.status === 401 && path !== 'api/login' && usedToken && usedToken === session.token && session.onUnauthorized) {
    session.onUnauthorized(data.error);
  }
  if (!res.ok) {
    const err = new Error(data.error || 'Fehler ' + res.status);
    err.status = res.status;
    throw err;
  }
  return data;
}

/**
 * Handels-Aktion mit requestId. Klickt jemand doppelt oder bricht die Verbindung ab und der Browser
 * wiederholt, führt der Server die Aktion trotzdem nur einmal aus.
 */
export function tradeAction(path, body = {}) {
  return api(path, { ...body, requestId: requestId() });
}

export const enc = encodeURIComponent;
