// Ansicht „Live-Welt“: BlueMap (echte Welt) und Seed-Vorschau – an derselben Stelle wie die Claim-Karte.
import { blueMapPane, closeBlueMap, openBlueMap } from '../bluemap.js';
import { map, setDimension } from '../map.js';
import { store } from '../store.js';
import { act, dimName, el, fill } from '../util.js';

const NOTES = {
  live: 'Die echte Welt, gerendert von BlueMap – an derselben Stelle wie die Claim-Karte. Länder und öffentliche Marker sind dort eigene Ebenen (Menü in BlueMap).',
  seed: 'Die Seed-Vorschau kommt von einem extra Server mit demselben Seed. Sie zeigt auch Unentdecktes – aber nichts Gebautes.',
};

let built = null;

function build(host) {
  const tabs = el('div', { class: 'tabs', role: 'tablist' },
    el('a', { class: 'tab', href: '#/live', 'data-tab': 'live', role: 'tab' }, 'Live-Welt'),
    el('a', { class: 'tab', href: '#/seed', 'data-tab': 'seed', role: 'tab' }, 'Seed-Vorschau'));
  const dim = el('select', { id: 'liveDimSelect', 'aria-label': 'Dimension', onchange: e => act(() => setDimension(e.target.value)) });
  const note = el('div', { class: 'toolbar-note' });
  const body = el('div', { class: 'map-body' }, blueMapPane('live'), blueMapPane('seed'));
  fill(host, el('div', { class: 'map-toolbar' }, tabs, dim, el('a', { class: 'button small', href: '#/karte' }, '← Claim-Karte'), note), body);
  built = { tabs, dim, note };
}

export default {
  title: 'Live-Welt',
  stage: 'live',
  render(host, route) {
    if (!built) build(host);
    const kind = route.name === 'seed' ? 'seed' : 'live';
    built.tabs.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === kind));
    document.getElementById('livePane').classList.toggle('hidden', kind !== 'live');
    document.getElementById('seedPane').classList.toggle('hidden', kind !== 'seed');
    const dims = (store.world && store.world.dimensions) || ['minecraft:overworld'];
    fill(built.dim, dims.map(d => el('option', { value: d, text: dimName(d) })));
    built.dim.value = map.dim;
    built.note.textContent = NOTES[kind];
    openBlueMap(kind);
    return () => closeBlueMap();
  },
};
