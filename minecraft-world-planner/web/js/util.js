// Kleine Helfer für die ganze Seite.
// Sicherheitsregel: Texte von Spielern immer als Text einsetzen (textContent), nie als HTML.

export const $ = id => document.getElementById(id);
export const HEX = /^#[0-9a-f]{6}$/i;
export const DIM_NAMES = { 'minecraft:overworld': 'Oberwelt', 'minecraft:the_nether': 'Nether', 'minecraft:the_end': 'Ende' };
export const dimName = d => DIM_NAMES[d] || d;

export function storageGet(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}
export function storageSet(key, value) {
  try { if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch { /* privater Modus */ }
}
export function storageJson(key, fallback) {
  try { const v = JSON.parse(storageGet(key) || 'null'); return v ?? fallback; } catch { return fallback; }
}

/**
 * Baut ein HTML-Element. props: class, text, on<Event>, sonst Attribute.
 * Kinder (Text oder Elemente) werden sicher angehängt; null/false werden übersprungen.
 */
export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k === 'value') node.value = v;
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) node.append(c instanceof Node ? c : String(c));
  return node;
}

/** Ersetzt den Inhalt. Anders als replaceChildren() wird null übersprungen statt als Text „null“ gezeigt. */
export function fill(node, ...children) {
  node.replaceChildren(...children.flat(Infinity).filter(c => c != null && c !== false));
  return node;
}

export function swatch(color, extraClass = '') {
  const s = el('span', { class: 'swatch ' + extraClass, 'aria-hidden': 'true' });
  if (HEX.test(color || '')) s.style.background = color; // per JS, weil die CSP style-Attribute im HTML verbietet
  return s;
}

export function hexA(hex, a) {
  const n = parseInt(HEX.test(hex || '') ? hex.slice(1) : '90a4ae', 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

export function safeUrl(value) {
  try { const u = new URL(value); return ['http:', 'https:'].includes(u.protocol) ? u.href : null; } catch { return null; }
}

export function pill(text, kind = '') {
  return el('span', { class: 'pill ' + kind, text });
}

export function plural(n, one, many) {
  return `${fmtNum(n)} ${n === 1 ? one : many}`;
}

export function fmtNum(n) {
  return Number(n || 0).toLocaleString('de-DE');
}

export function fmtDate(ms) {
  if (!ms) return '—';
  return new Date(ms).toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

export function fmtDateTime(ms) {
  if (!ms) return '—';
  return new Date(ms).toLocaleString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** „vor 5 Minuten“, „gerade eben“ … */
export function fmtAgo(ms, now = Date.now()) {
  if (!ms) return 'unbekannt';
  const s = Math.round((now - ms) / 1000);
  if (s < 45) return 'gerade eben';
  const m = Math.round(s / 60);
  if (m < 60) return `vor ${m} ${m === 1 ? 'Minute' : 'Minuten'}`;
  const h = Math.round(m / 60);
  if (h < 24) return `vor ${h} ${h === 1 ? 'Stunde' : 'Stunden'}`;
  const d = Math.round(h / 24);
  if (d < 30) return `vor ${d} ${d === 1 ? 'Tag' : 'Tagen'}`;
  return 'am ' + fmtDate(ms);
}

/** Restzeit: „2 Std. 5 Min.“, „45 Sek.“ */
export function fmtLeft(ms) {
  if (ms <= 0) return 'abgelaufen';
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d} T. ${h} Std.`;
  if (h > 0) return `${h} Std. ${m} Min.`;
  if (m > 0) return `${m} Min. ${s % 60} Sek.`;
  return `${s} Sek.`;
}

export function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

// ------------------------------------------------------------------ Rückmeldungen

let toastTimer;
/** Eine Datei im Browser speichern lassen – nichts geht an einen Server. */
export function downloadFile(name, content, type) {
  const blob = new Blob([content], { type });
  const a = el('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

export function toast(msg, isError = false) {
  const t = $('toast');
  if (!t) return;
  t.textContent = msg;
  t.classList.toggle('error', isError);
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), isError ? 6000 : 2800);
}

/** Führt eine Server-Aktion aus und zeigt Erfolg oder Fehler an. Gibt undefined zurück, wenn es nicht geklappt hat. */
export async function act(fn, okMessage) {
  try {
    const result = await fn();
    if (okMessage) toast(typeof okMessage === 'function' ? okMessage(result) : okMessage);
    return result ?? true;
  } catch (e) {
    toast(e.message, true);
    return undefined;
  }
}

/** Kopieren mit Rückmeldung – mit Ersatzweg, falls die Seite ohne HTTPS läuft. */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('Kopiert: ' + text);
  } catch {
    const ta = el('textarea', { readonly: true });
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    ta.value = text;
    document.body.append(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch { /* geht nicht */ }
    ta.remove();
    toast(ok ? 'Kopiert: ' + text : 'Kopieren ging nicht – bitte abschreiben: ' + text, !ok);
  }
}

// ------------------------------------------------------------------ Dialoge

/** Ein Dialog für alles: Bestätigen, Text eingeben, Formulare. Gibt eine Promise zurück. */
function openDialog(build) {
  return new Promise(resolve => {
    const dialog = $('modal');
    let done = false;
    const close = value => {
      if (done) return;
      done = true;
      dialog.close();
      resolve(value);
    };
    fill(dialog, build(close));
    dialog.onclose = () => close(null);
    dialog.showModal();
    const first = dialog.querySelector('[autofocus], input, select, textarea, button.primary');
    if (first) first.focus();
  });
}

/**
 * Sicherheitsfrage. requireText: Der Spieler muss diesen Text eintippen (z. B. den Teamnamen), sonst bleibt
 * der Knopf gesperrt – gegen versehentliches Löschen.
 */
export function confirmDialog({ title, text, confirmLabel = 'Ja', danger = false, requireText = null }) {
  return openDialog(close => {
    const input = requireText ? el('input', { autocomplete: 'off', 'aria-label': 'Zur Bestätigung eintippen', autofocus: true }) : null;
    const ok = el('button', { class: danger ? 'danger' : 'primary', type: 'submit', disabled: !!requireText }, confirmLabel);
    if (input) input.addEventListener('input', () => { ok.disabled = input.value.trim().toLowerCase() !== requireText.toLowerCase(); });
    return el('form', { class: 'dialog-body', onsubmit: e => { e.preventDefault(); close(requireText ? input.value.trim() : true); } },
      el('h2', { text: title }),
      text ? el('p', { text }) : null,
      requireText ? el('label', { class: 'field' }, el('span', { text: `Tippe zur Bestätigung „${requireText}“ ein:` }), input) : null,
      el('div', { class: 'row end' },
        el('button', { type: 'button', onclick: () => close(null) }, 'Abbrechen'),
        ok));
  });
}

/**
 * Formular-Dialog. fields: [{name, label, type: text|textarea|number|select|color|date|checkbox, value, options:[[value,label]],
 * required, maxLength, min, max, step, placeholder, help, list, showIf(values)}].
 * tools: Zusatzknöpfe [{label, onClick(inputs, values)}], hint(values): Text unter dem Formular (z. B. Nether-Umrechnung).
 * Gibt die Werte zurück oder null.
 */
export function formDialog({ title, intro, fields, submitLabel = 'Speichern', validate, tools = [], hint }) {
  return openDialog(close => {
    const inputs = {};
    const wrappers = {};
    const error = el('p', { class: 'error', role: 'alert' });
    const hintBox = el('p', { class: 'muted small hint' });
    const body = fields.map(f => {
      const input = buildInput(f);
      inputs[f.name] = input;
      const w = f.type === 'checkbox'
        ? el('label', { class: 'check' }, input, el('span', { text: f.label }), f.help ? el('small', { class: 'muted', text: f.help }) : null)
        : el('label', { class: 'field' }, el('span', { text: f.label + (f.required ? ' *' : '') }), input,
          f.help ? el('small', { class: 'muted', text: f.help }) : null);
      wrappers[f.name] = w;
      return w;
    });
    const read = () => {
      const out = {};
      for (const f of fields) {
        const i = inputs[f.name];
        if (f.type === 'checkbox') out[f.name] = i.checked;
        else if (f.type === 'number') out[f.name] = i.value === '' ? null : Number(i.value);
        else out[f.name] = i.value.trim();
      }
      return out;
    };
    const refresh = () => {
      const values = read();
      for (const f of fields) if (f.showIf) wrappers[f.name].classList.toggle('hidden', !f.showIf(values));
      if (hint) hintBox.textContent = hint(values) || '';
    };
    const form = el('form', { class: 'dialog-body', onsubmit: e => {
      e.preventDefault();
      const values = read();
      for (const f of fields) if (f.showIf && !f.showIf(values)) values[f.name] = f.type === 'checkbox' ? false : '';
      const problem = validate ? validate(values) : null;
      if (problem) { error.textContent = problem; return; }
      close(values);
    } },
      el('h2', { text: title }),
      intro ? el('p', { class: 'muted', text: intro }) : null,
      tools.length ? el('div', { class: 'row' }, tools.map(t => el('button', { type: 'button', class: 'small', onclick: () => { t.onClick(inputs, read()); refresh(); } }, t.label))) : null,
      el('div', { class: 'form-grid' }, ...body),
      hint ? hintBox : null,
      error,
      el('div', { class: 'row end' },
        el('button', { type: 'button', onclick: () => close(null) }, 'Abbrechen'),
        el('button', { class: 'primary', type: 'submit' }, submitLabel)));
    form.addEventListener('input', refresh);
    form.addEventListener('change', refresh);
    refresh();
    return form;
  });
}

export function buildInput(f) {
  let input;
  if (f.type === 'textarea') {
    input = el('textarea', { rows: f.rows || 3, maxlength: f.maxLength, placeholder: f.placeholder, name: f.name });
    input.value = f.value ?? '';
  } else if (f.type === 'select') {
    input = el('select', { name: f.name }, (f.options || []).map(([v, label]) => el('option', { value: v, text: label })));
    if (f.value != null) input.value = f.value;
  } else if (f.type === 'checkbox') {
    input = el('input', { type: 'checkbox', name: f.name });
    input.checked = !!f.value;
  } else {
    input = el('input', { type: f.type || 'text', name: f.name, maxlength: f.maxLength, min: f.min, max: f.max, step: f.step,
      placeholder: f.placeholder, list: f.list, autocomplete: 'off', required: !!f.required });
    input.value = f.value ?? '';
  }
  if (f.autofocus) input.setAttribute('autofocus', '');
  return input;
}

/** Leerer Zustand mit Erklärung, statt einer leeren Fläche. */
export function empty(text, action) {
  return el('div', { class: 'empty' }, el('p', { text }), action || null);
}

/** Abschnitt mit Überschrift */
export function section(title, ...children) {
  return el('section', { class: 'card' }, title ? el('h2', { text: title }) : null, ...children);
}

/** Fortschrittsbalken (Wert 0..1) */
export function progressBar(done, total, label) {
  const bar = el('div', { class: 'progress', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': total, 'aria-valuenow': done,
    'aria-label': label || 'Fortschritt' });
  const inner = el('div', { class: 'progress-inner' });
  inner.style.width = (total ? Math.round(100 * done / total) : 0) + '%';
  bar.append(inner);
  return bar;
}

export function requestId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  return [...b].map(x => x.toString(16).padStart(2, '0')).join('').replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, '$1-$2-$3-$4-$5');
}
