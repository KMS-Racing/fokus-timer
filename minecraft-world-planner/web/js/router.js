// Adressen der Seite: #/bereich/id?schluessel=wert – so kann man Links zu Ländern, Teams usw. teilen,
// und „Zurück“ im Browser funktioniert.

let handler = null;

export function parseHash(hash = location.hash) {
  const raw = hash.replace(/^#\/?/, '');
  const [pathPart, queryPart = ''] = raw.split('?');
  const path = pathPart.split('/').filter(Boolean).map(decodeURIComponent);
  const query = Object.fromEntries(new URLSearchParams(queryPart));
  return { path, query, name: path[0] || 'dashboard', id: path[1] || null };
}

export function navigate(hash) {
  if (location.hash === hash) { if (handler) handler(parseHash()); return; }
  location.hash = hash;
}

export function onRoute(fn) {
  handler = fn;
  window.addEventListener('hashchange', () => fn(parseHash()));
}

export function link(hash, text, cls = '') {
  const a = document.createElement('a');
  a.href = hash;
  a.textContent = text;
  if (cls) a.className = cls;
  return a;
}
