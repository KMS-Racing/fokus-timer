// Diagramme für die Statistik: Wärmekarte (Wochentag × Stunde), Säulen (ein Wert pro Tag) und Balken-Listen.
// Regeln: eine Farbe pro Diagramm, dünne Balken mit runder Spitze, Beschriftung nur am Höchstwert, alle Werte im
// Tooltip (Maus und Pfeiltasten) und in der Tabellen-Ansicht. Texte nie in Datenfarbe. Farben kommen aus style.css
// (--viz-…, hell und dunkel getrennt). Spielernamen und Texte vom Server immer als Text einsetzen, nie als HTML.
import { WEEKDAYS } from './statfmt.js';
import { el, fill } from './util.js';

const NS = 'http://www.w3.org/2000/svg';
const HEAT_STEPS = 7;

function svg(tag, attrs = {}, ...children) {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v != null && v !== false) node.setAttribute(k, String(v));
  for (const c of children.flat()) if (c != null && c !== false) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return node;
}

// ------------------------------------------------------------------ Tooltip

let tipNode = null;

function tip() {
  if (!tipNode || !tipNode.isConnected) {
    tipNode = el('div', { class: 'viz-tip hidden', role: 'status', 'aria-live': 'polite' });
    document.body.append(tipNode);
  }
  return tipNode;
}

/** Wert zuerst (fett), Beschriftung danach – wer hinzeigt, kennt die Reihe und will die Zahl. */
function showTip(anchor, value, label) {
  const t = tip();
  fill(t, el('strong', { text: value }), el('span', { text: label }));
  t.classList.remove('hidden');
  const r = anchor.getBoundingClientRect();
  const w = t.offsetWidth, h = t.offsetHeight;
  let x = r.left + r.width / 2 - w / 2;
  x = Math.max(8, Math.min(window.innerWidth - w - 8, x));
  let y = r.top - h - 8;
  if (y < 8) y = r.bottom + 8;
  t.style.left = `${Math.round(x + window.scrollX)}px`;
  t.style.top = `${Math.round(y + window.scrollY)}px`;
}

export function hideTip() {
  if (tipNode) tipNode.classList.add('hidden');
}

/** Pfeiltasten bewegen eine Markierung über die Werte – dieselbe Anzeige wie mit der Maus. */
function keyboardCursor(root, count, columns, show, clear) {
  let index = -1;
  root.setAttribute('tabindex', '0');
  root.addEventListener('keydown', e => {
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: columns, ArrowUp: -columns, Home: -Infinity, End: Infinity }[e.key];
    if (step === undefined) return;
    e.preventDefault();
    if (index < 0) index = 0;
    else if (step === -Infinity) index = 0;
    else if (step === Infinity) index = count - 1;
    else index = Math.max(0, Math.min(count - 1, index + step));
    show(index);
  });
  root.addEventListener('blur', () => { index = -1; clear(); hideTip(); });
}

/** Ungefähr verfügbare Breite in einer Karte (Seitenrand und Kartenrand abgezogen) – für die Größe der Zeichnungen. */
function availableWidth() {
  const w = typeof window !== 'undefined' && window.innerWidth ? window.innerWidth : 1280;
  const page = w > 900 ? w - 210 - 48 : w - 28; // Navigation und Seitenrand
  const card = w > 900 ? (page - 14) / 2 : page; // zwei Spalten ab 900 px
  return Math.max(260, Math.min(640, card - 34));
}

// ------------------------------------------------------------------ Karte mit Tabellen-Umschalter

/**
 * Rahmen für ein Diagramm: Titel, Untertitel, Umschalter „Tabelle“ (die Ansicht ohne Farben und ohne Maus).
 * table: Funktion, die die Tabelle baut (erst beim Umschalten).
 */
export function chartCard({ title, subtitle, chart, table, note, id, legend }) {
  const body = el('div', { class: 'viz-body' }, chart);
  let showingTable = false;
  const toggle = table ? el('button', { class: 'small ghost', 'aria-pressed': 'false' }, 'Tabelle') : null;
  if (toggle) {
    toggle.addEventListener('click', () => {
      showingTable = !showingTable;
      toggle.textContent = showingTable ? 'Diagramm' : 'Tabelle';
      toggle.setAttribute('aria-pressed', showingTable ? 'true' : 'false');
      fill(body, showingTable ? table() : chart);
      hideTip();
    });
  }
  return el('section', { class: 'card viz-card', id },
    el('div', { class: 'viz-head' },
      el('div', {}, el('h2', { text: title }), subtitle ? el('p', { class: 'muted small', text: subtitle }) : null),
      toggle),
    legend || null, body, note ? el('p', { class: 'muted small', text: note }) : null);
}

export function dataTable(head, rows) {
  return el('div', { class: 'table-wrap' }, el('table', { class: 'data-table' },
    el('thead', {}, el('tr', {}, head.map(h => el('th', { scope: 'col', text: h })))),
    el('tbody', {}, rows.map(r => el('tr', {}, r.map((c, i) => (i === 0 ? el('th', { scope: 'row', text: c }) : el('td', { text: c }))))))));
}

// ------------------------------------------------------------------ Wärmekarte

/**
 * Wärmekarte Wochentag × Stunde. values: 7 Zeilen (Mo–So) mit je 24 Zahlen oder null (= keine Messung).
 * Eine Farbe, hell = wenig, kräftig = viel (im Dunkelmodus umgekehrt verankert). 0 bekommt eine eigene, leere Fläche.
 */
export function heatmap({ values, format, label, ariaLabel }) {
  // Auf dem Handy kleinere Kästchen statt winziger Schrift: Die Zeichnung wird so breit wie der Platz (Text bleibt 11 px).
  const left = 26, top = 16, gap = 2;
  const pitch = Math.max(11, Math.min(22, Math.floor((availableWidth() - left) / 24)));
  const cell = pitch - gap, every = pitch < 16 ? 6 : 3;
  const width = left + 24 * pitch, height = top + 7 * pitch;
  let max = 0;
  for (const row of values) for (const v of row) if (v != null && v > max) max = v;
  const root = svg('svg', { viewBox: `0 0 ${width} ${height}`, width, height, class: 'viz heatmap', role: 'img', 'aria-label': ariaLabel });
  for (let h = 0; h < 24; h += every) root.append(svg('text', { x: left + h * pitch + cell / 2, y: 11, class: 'viz-axis', 'text-anchor': 'middle' }, String(h)));
  const cells = [];
  values.forEach((row, wd) => {
    root.append(svg('text', { x: 0, y: top + wd * pitch + cell / 2 + 4, class: 'viz-axis' }, WEEKDAYS[wd]));
    row.forEach((v, h) => {
      const step = v == null ? 'none' : v <= 0 || max <= 0 ? 'zero' : String(Math.max(1, Math.min(HEAT_STEPS, Math.ceil((v / max) * HEAT_STEPS))));
      const rect = svg('rect', { x: left + h * pitch, y: top + wd * pitch, width: cell, height: cell, rx: cell > 12 ? 3 : 2, class: 'heat-cell heat-' + step });
      const text = [v == null ? 'keine Messung' : format(v), `${WEEKDAYS[wd]} ${h}–${h + 1} Uhr${label ? ' · ' + label : ''}`];
      rect.addEventListener('pointerenter', () => showTip(rect, ...text));
      rect.addEventListener('pointerleave', hideTip);
      cells.push({ rect, text });
      root.append(rect);
    });
  });
  let marked = null;
  keyboardCursor(root, cells.length, 24, i => {
    if (marked) marked.classList.remove('focused');
    marked = cells[i].rect;
    marked.classList.add('focused');
    showTip(marked, ...cells[i].text);
  }, () => { if (marked) marked.classList.remove('focused'); marked = null; });
  return root;
}

/** Legende der Wärmekarte: wenig → viel, dazu „0“ und „keine Messung“. */
export function heatLegend(maxText, hasNone) {
  const steps = [];
  for (let i = 1; i <= HEAT_STEPS; i++) steps.push(el('span', { class: 'heat-swatch heat-' + i, 'aria-hidden': 'true' }));
  return el('div', { class: 'viz-legend' },
    el('span', { class: 'heat-swatch heat-zero', 'aria-hidden': 'true' }), el('span', { class: 'muted small', text: '0' }),
    el('span', { class: 'muted small', text: 'wenig' }), el('span', { class: 'heat-scale' }, steps),
    el('span', { class: 'muted small', text: 'viel' + (maxText ? ` (${maxText})` : '') }),
    hasNone ? [el('span', { class: 'heat-swatch heat-none', 'aria-hidden': 'true' }), el('span', { class: 'muted small', text: 'keine Messung' })] : null);
}

// ------------------------------------------------------------------ Säulen (ein Wert pro Tag)

/** Runde Zahl für die Achse: 7 → 10, 23 → 25, 1340 → 1500. */
export function niceMax(v) {
  if (!(v > 0)) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

function compact(v) {
  if (v >= 1e6) return `${(v / 1e6).toLocaleString('de-DE', { maximumFractionDigits: 1 })} Mio.`;
  if (v >= 1000) return `${(v / 1000).toLocaleString('de-DE', { maximumFractionDigits: 1 })} Tsd.`;
  return v.toLocaleString('de-DE', { maximumFractionDigits: 1 });
}

/** Säule mit runder Spitze (4 px) und gerader Kante an der Grundlinie. */
function columnPath(x, y, w, base) {
  const r = Math.min(4, w / 2, base - y);
  return `M${x},${base}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${base}Z`;
}

/**
 * items: [{label: "Mo 20.10.", short: "20.10.", value: Zahl oder null}]. axis: Zahl → kurzer Text für die Achse
 * (z. B. Stunden statt Millisekunden), format: Zahl → Text für Tooltip und Beschriftung. integer: nur ganze Zahlen auf der Achse.
 */
export function columns({ items, format, axis = compact, ariaLabel, height = 150, unitLabel = '', integer = false }) {
  const width = Math.round(availableWidth()), left = 40, top = 14, bottom = 22, plotH = height - top - bottom;
  const max = Math.max(0, ...items.map(i => i.value || 0));
  let yMax = niceMax(max);
  if (integer) yMax = Math.max(2, Math.ceil(yMax / 2) * 2); // ganze Zahlen (Spieler): Achse 0 / 2 / 4 statt 0 / 1,5 / 3
  const band = (width - left) / Math.max(1, items.length);
  const barW = Math.min(24, Math.max(2, band - 4));
  const base = top + plotH;
  const root = svg('svg', { viewBox: `0 0 ${width} ${height}`, width, height, class: 'viz columns', role: 'img', 'aria-label': ariaLabel });
  for (const t of [0, yMax / 2, yMax]) {
    const y = base - (t / yMax) * plotH;
    root.append(svg('line', { x1: left, x2: width, y1: y, y2: y, class: t === 0 ? 'viz-base' : 'viz-grid' }));
    root.append(svg('text', { x: left - 6, y: y + 4, class: 'viz-axis', 'text-anchor': 'end' }, axis(t)));
  }
  const labelEvery = Math.max(1, Math.ceil(items.length / (width < 420 ? 4 : 6)));
  const hits = [];
  let maxShown = false;
  items.forEach((item, i) => {
    const cx = left + band * i + band / 2;
    if (item.value != null && item.value > 0) {
      const y = base - (item.value / yMax) * plotH;
      root.append(svg('path', { d: columnPath(cx - barW / 2, y, barW, base), class: 'viz-bar' }));
      if (!maxShown && item.value === max) { // nur der Höchstwert bekommt eine Beschriftung
        maxShown = true;
        root.append(svg('text', { x: cx, y: Math.max(10, y - 4), class: 'viz-label', 'text-anchor': 'middle' }, format(item.value)));
      }
    }
    if ((items.length - 1 - i) % labelEvery === 0) root.append(svg('text', { x: cx, y: height - 6, class: 'viz-axis', 'text-anchor': 'middle' }, item.short));
    const hit = svg('rect', { x: left + band * i, y: top, width: band, height: plotH, class: 'viz-hit' });
    const text = [item.value == null ? 'keine Messung' : format(item.value), item.label + (unitLabel ? ' · ' + unitLabel : '')];
    hit.addEventListener('pointerenter', () => { hit.classList.add('hover'); showTip(hit, ...text); });
    hit.addEventListener('pointerleave', () => { hit.classList.remove('hover'); hideTip(); });
    hits.push({ hit, text });
    root.append(hit);
  });
  let marked = null;
  keyboardCursor(root, hits.length, 1, i => {
    if (marked) marked.classList.remove('hover');
    marked = hits[i].hit;
    marked.classList.add('hover');
    showTip(marked, ...hits[i].text);
  }, () => { if (marked) marked.classList.remove('hover'); marked = null; });
  return root;
}

// ------------------------------------------------------------------ Balken-Liste (Ranglisten, Top-Listen)

/**
 * Rangliste als Liste: Platz, Name, dünner Balken (Anteil am Ersten), Wert als Text. items: [{place, name, value, href, mine}].
 * Jeder Wert steht als Text daneben – der Balken zeigt nur das Verhältnis.
 */
export function barList(items, format, emptyText) {
  if (!items.length) return el('p', { class: 'muted small', text: emptyText || 'Noch keine Werte.' });
  const max = Math.max(...items.map(i => i.value), 1);
  return el('ol', { class: 'bar-list' }, items.map(i => {
    const fillBar = el('span', { class: 'bar-fill' });
    fillBar.style.width = `${Math.max(1.5, (i.value / max) * 100)}%`;
    return el('li', { class: 'bar-row' + (i.mine ? ' mine' : '') },
      el('span', { class: 'bar-place', text: i.place != null ? `${i.place}.` : '' }),
      el('span', { class: 'bar-name' }, i.href ? el('a', { href: i.href, text: i.name }) : el('span', { text: i.name }),
        i.mine ? el('span', { class: 'pill ok', text: 'du' }) : null),
      el('span', { class: 'bar-track', 'aria-hidden': 'true' }, fillBar),
      el('span', { class: 'bar-value', text: format(i.value) }));
  }));
}
