// Bauprojekte mit Aufgaben, Fortschritt, Verantwortlichen, Updates – optional mit Land, Team und Ort.
import { api, enc } from '../api.js';
import { showPoint } from '../map.js';
import { navigate } from '../router.js';
import { landById, load, myTeams, options, projectById, store, label } from '../store.js';
import { act, confirmDialog, dimName, el, fill, fmtAgo, fmtDate, formDialog, pill, progressBar } from '../util.js';
import { bind, favButton, pageHead, playerDatalist, searchInput, segmented, selectInput, teamBadgeById } from './common.js';
import { myPosition } from './markers.js';

const state = { filter: 'open', q: '', sort: 'due' };
const STATUS_KIND = { planned: 'muted', in_progress: 'info', paused: 'warn', done: 'ok' };
const PRIORITY_KIND = { low: 'muted', normal: '', high: 'danger' };

async function refresh() {
  await load.projects();
}

async function projectAction(p, path, body, okMessage) {
  const r = await act(() => api(`api/projects/${enc(p.id)}/${path}`, body), okMessage);
  if (r) await refresh();
  return r;
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

function dueInfo(p) {
  if (!p.dueDate) return null;
  const overdue = p.status !== 'done' && p.dueDate < today();
  return el('span', { class: overdue ? 'warn' : '', text: (overdue ? '⚠ überfällig seit ' : 'bis ') + fmtDate(Date.parse(p.dueDate)) });
}

function projectCard(p) {
  return el('article', { class: 'item project' },
    el('div', { class: 'item-top' },
      el('a', { class: 'title-link', href: '#/projekte/' + enc(p.id) }, el('strong', { text: p.name })),
      el('div', { class: 'row tight' }, favButton('projects', p.id), pill(label('projectStatus', p.status), STATUS_KIND[p.status]),
        p.priority !== 'normal' ? pill(label('projectPriority', p.priority), PRIORITY_KIND[p.priority]) : null,
        p.visibility !== 'public' ? pill(label('projectVisibility', p.visibility), 'muted') : null)),
    p.progress.total ? el('div', { class: 'row tight' }, progressBar(p.progress.done, p.progress.total, 'Aufgaben erledigt'),
      el('small', { text: `${p.progress.done}/${p.progress.total}` })) : el('small', { class: 'muted', text: 'Noch keine Aufgaben' }),
    el('div', { class: 'meta' },
      dueInfo(p),
      p.teamId ? teamBadgeById(p.teamId) : null,
      p.landId && landById(p.landId) ? el('a', { href: '#/claims/' + enc(p.landId), text: '⌂ ' + landById(p.landId).name }) : null,
      p.responsible.length ? el('span', { text: '👤 ' + p.responsible.map(r => r.name).join(', ') }) : el('span', { text: 'von ' + p.createdBy.name })),
    p.description ? el('p', { class: 'notes clamp', text: p.description }) : null);
}

function projectFields(p = {}) {
  const teams = myTeams().filter(t => t.myRole !== 'recruit');
  const lands = store.lands.filter(l => l.mine || (l.teamId && teams.some(t => t.id === l.teamId)));
  const dims = (store.world && store.world.dimensions) || ['minecraft:overworld'];
  const loc = p.location || {};
  return [
    { name: 'name', label: 'Projektname', value: p.name || '', required: true, maxLength: 48, autofocus: true },
    { name: 'description', label: 'Beschreibung', type: 'textarea', value: p.description || '', maxLength: 1000 },
    { name: 'status', label: 'Status', type: 'select', value: p.status || 'planned', options: options('projectStatus') },
    { name: 'priority', label: 'Priorität', type: 'select', value: p.priority || 'normal', options: options('projectPriority') },
    { name: 'startDate', label: 'Start (optional)', type: 'date', value: p.startDate || '' },
    { name: 'dueDate', label: 'Ziel (optional)', type: 'date', value: p.dueDate || '' },
    { name: 'teamId', label: 'Team-Projekt', type: 'select', value: p.teamId || '', options: [['', '— kein Team —'], ...teams.map(t => [t.id, `[${t.tag}] ${t.name}`])],
      help: teams.length ? 'Bei Team-Projekten dürfen alle Mitglieder (ab „Mitglied“) mitarbeiten.' : 'Du bist in keinem Team.' },
    { name: 'visibility', label: 'Wer sieht es?', type: 'select', value: p.visibility || 'public', options: options('projectVisibility'),
      help: '„Nur Team“ geht nur bei Team-Projekten.' },
    { name: 'landId', label: 'Land (optional)', type: 'select', value: p.landId || '', options: [['', '— keins —'], ...lands.map(l => [l.id, l.name])] },
    { name: 'responsible', label: 'Verantwortliche (Namen, mit Komma getrennt)', value: (p.responsible || []).map(r => r.name).join(', '), list: playerDatalist() },
    { name: 'useLocation', label: 'Ort auf der Karte festlegen', type: 'checkbox', value: !!p.location },
    { name: 'dimension', label: 'Dimension', type: 'select', value: loc.dimension || dims[0], options: dims.map(d => [d, dimName(d)]), showIf: v => v.useLocation },
    { name: 'x', label: 'X', type: 'number', value: loc.x ?? '', step: 1, showIf: v => v.useLocation },
    { name: 'z', label: 'Z', type: 'number', value: loc.z ?? '', step: 1, showIf: v => v.useLocation },
    { name: 'notes', label: 'Notizen', type: 'textarea', value: p.notes || '', maxLength: 2000 },
  ];
}

function toBody(v) {
  const body = {
    name: v.name, description: v.description, status: v.status, priority: v.priority, startDate: v.startDate, dueDate: v.dueDate,
    teamId: v.teamId, visibility: v.visibility, landId: v.landId, notes: v.notes,
    responsible: v.responsible ? v.responsible.split(',').map(s => s.trim()).filter(Boolean) : [],
    location: v.useLocation && Number.isFinite(v.x) && Number.isFinite(v.z) ? { dimension: v.dimension, x: v.x, z: v.z } : null,
  };
  return body;
}

function validateProject(v) {
  if (v.name.length < 2) return 'Der Name braucht mindestens 2 Zeichen.';
  if (v.visibility === 'team' && !v.teamId) return '„Nur Team“ geht nur bei einem Team-Projekt.';
  if (v.startDate && v.dueDate && v.dueDate < v.startDate) return 'Das Ziel liegt vor dem Start.';
  if (v.useLocation && (!Number.isFinite(v.x) || !Number.isFinite(v.z))) return 'Für den Ort bitte X und Z angeben.';
  return null;
}

export async function createProject(prefill = {}) {
  const fields = projectFields(prefill);
  fields.splice(2, 0, { name: 'tasks', label: 'Aufgaben (eine pro Zeile)', type: 'textarea', rows: 4, placeholder: 'Fundament\nMauern\nDach' });
  const v = await formDialog({
    title: 'Neues Projekt', fields, validate: validateProject, submitLabel: 'Projekt anlegen',
    tools: [{ label: '📍 Meine Position als Ort', onClick: inputs => {
      const me = myPosition();
      if (!me) return;
      inputs.useLocation.checked = true; inputs.dimension.value = me.dimension; inputs.x.value = Math.floor(me.x); inputs.z.value = Math.floor(me.z);
    } }],
  });
  if (!v) return;
  const body = toBody(v);
  body.tasks = (v.tasks || '').split('\n').map(s => s.trim()).filter(Boolean);
  const r = await act(() => api('api/projects', body), `Projekt »${v.name}« angelegt.`);
  if (r) { await refresh(); navigate('#/projekte/' + enc(r.id)); }
}

function filtered() {
  const q = state.q.trim().toLowerCase();
  const myTeamIds = new Set(myTeams().map(t => t.id));
  const items = store.projects.filter(p => {
    if (state.filter === 'open' && p.status === 'done') return false;
    if (state.filter === 'done' && p.status !== 'done') return false;
    if (state.filter === 'mine' && !p.mine) return false;
    if (state.filter === 'team' && !(p.teamId && myTeamIds.has(p.teamId))) return false;
    if (q && !(p.name.toLowerCase().includes(q) || (p.description || '').toLowerCase().includes(q))) return false;
    return true;
  });
  const prio = { high: 0, normal: 1, low: 2 };
  const sorts = {
    due: (a, b) => (a.dueDate || '9999').localeCompare(b.dueDate || '9999'),
    priority: (a, b) => prio[a.priority] - prio[b.priority],
    progress: (a, b) => (b.progress.total ? b.progress.done / b.progress.total : 0) - (a.progress.total ? a.progress.done / a.progress.total : 0),
    new: (a, b) => b.createdAt - a.createdAt,
  };
  return items.sort(sorts[state.sort] || sorts.due);
}

function renderList(root) {
  const controls = el('div', { class: 'toolbar' }), listBox = el('div');
  const draw = () => {
    fill(controls,
      segmented([['open', 'Offen'], ['mine', 'Meine'], ['team', 'Team'], ['done', 'Abgeschlossen'], ['all', 'Alle']], state.filter, v => { state.filter = v; draw(); }),
      selectInput([['due', 'Nach Ziel-Datum'], ['priority', 'Nach Priorität'], ['progress', 'Nach Fortschritt'], ['new', 'Neueste']], state.sort, v => { state.sort = v; draw(); }, 'Sortierung'),
      searchInput(state.q, v => { state.q = v; drawList(); }, 'Projekt suchen …'));
    drawList();
  };
  const drawList = () => {
    const items = filtered();
    fill(listBox, items.length ? el('div', { class: 'list', id: 'projectList' }, items.map(projectCard))
      : el('div', { class: 'empty' }, el('p', { text: store.projects.length ? 'Kein Projekt passt zu den Filtern.' : 'Noch keine Projekte. Plant euer erstes Bauprojekt – mit Aufgaben und Ziel-Datum.' })));
  };
  fill(root, pageHead('Projekte', 'Was wird gebaut, wer macht mit, was fehlt noch?', el('button', { class: 'primary', onclick: () => createProject() }, '+ Neues Projekt')),
    controls, listBox);
  return bind(root, ['projects', 'teams', 'lands', 'prefs'], draw);
}

// ------------------------------------------------------------------ Detail

function tasksSection(p) {
  const input = el('input', { placeholder: 'Neue Aufgabe', maxlength: 200, 'aria-label': 'Neue Aufgabe' });
  return el('section', { class: 'card' },
    el('div', { class: 'row between' }, el('h2', { text: 'Aufgaben' }),
      p.progress.total ? el('div', { class: 'row tight' }, progressBar(p.progress.done, p.progress.total), el('strong', { text: `${p.progress.done}/${p.progress.total}` })) : null),
    p.tasks.length ? el('ul', { class: 'tasks', id: 'taskList' }, p.tasks.map(t => {
      const cb = el('input', { type: 'checkbox', 'aria-label': t.text, disabled: !p.can.edit });
      cb.checked = t.done;
      cb.addEventListener('change', () => projectAction(p, `tasks/${enc(t.id)}/edit`, { done: cb.checked }));
      return el('li', { class: t.done ? 'done' : '' },
        el('label', { class: 'check' }, cb, el('span', { text: t.text })),
        t.done && t.doneBy ? el('small', { class: 'muted', text: `✓ ${t.doneBy.name}` }) : null,
        p.can.edit ? el('button', { class: 'icon', 'aria-label': 'Aufgabe löschen', onclick: () => projectAction(p, `tasks/${enc(t.id)}/delete`, {}) }, '×') : null);
    })) : el('p', { class: 'muted', text: 'Noch keine Aufgaben. Ohne Aufgaben gibt es keinen Fortschritt.' }),
    p.can.edit ? el('form', { class: 'row', onsubmit: async e => {
      e.preventDefault();
      if (input.value.trim()) await projectAction(p, 'tasks', { text: input.value.trim() });
    } }, input, el('button', { class: 'small', type: 'submit' }, 'Hinzufügen')) : null);
}

function updatesSection(p) {
  const text = el('textarea', { rows: 2, maxlength: 500, placeholder: 'Was gibt es Neues?', 'aria-label': 'Update schreiben' });
  return el('section', { class: 'card' },
    el('h2', { text: 'Updates' }),
    p.can.edit ? el('form', { class: 'stack', onsubmit: async e => {
      e.preventDefault();
      if (text.value.trim()) await projectAction(p, 'updates', { text: text.value.trim() }, 'Update gespeichert.');
    } }, text, el('button', { class: 'small', type: 'submit' }, 'Update schreiben')) : null,
    p.updates.length ? el('ul', { class: 'timeline' }, p.updates.slice().reverse().map(u => el('li', {},
      el('strong', { text: u.author.name }), el('small', { class: 'muted', text: ' · ' + fmtAgo(u.createdAt) }), el('p', { class: 'notes', text: u.text }))))
      : el('p', { class: 'muted', text: 'Noch keine Updates.' }));
}

function renderDetail(root, id) {
  const draw = () => {
    const p = projectById(id);
    if (!p) {
      fill(root, pageHead('Projekt nicht gefunden', 'Es wurde gelöscht oder du darfst es nicht sehen.'), el('a', { class: 'button', href: '#/projekte' }, '← Alle Projekte'));
      return;
    }
    const land = p.landId ? landById(p.landId) : null;
    fill(root,
      el('a', { class: 'back', href: '#/projekte' }, '← Alle Projekte'),
      el('div', { class: 'page-head' },
        el('div', {}, el('h1', { text: p.name }), el('div', { class: 'row tight' },
          pill(label('projectStatus', p.status), STATUS_KIND[p.status]), pill('Priorität: ' + label('projectPriority', p.priority), PRIORITY_KIND[p.priority]),
          pill(label('projectVisibility', p.visibility), 'muted'), favButton('projects', p.id))),
        el('div', { class: 'row' },
          p.location ? el('button', { onclick: () => { navigate('#/karte'); showPoint(p.location.dimension, p.location.x + 0.5, p.location.z + 0.5, 1.5); } }, 'Auf Karte') : null,
          p.can.edit ? el('button', { class: 'primary', onclick: async () => {
            const v = await formDialog({ title: 'Projekt bearbeiten', fields: projectFields(p), validate: validateProject });
            if (v) await projectAction(p, 'edit', toBody(v), 'Gespeichert.');
          } }, 'Bearbeiten') : null)),
      el('div', { class: 'grid two' },
        el('section', { class: 'card' },
          el('h2', { text: 'Übersicht' }),
          p.description ? el('p', { class: 'notes', text: p.description }) : el('p', { class: 'muted', text: 'Keine Beschreibung.' }),
          el('dl', { class: 'facts' },
            el('dt', { text: 'Start' }), el('dd', { text: p.startDate ? fmtDate(Date.parse(p.startDate)) : '—' }),
            el('dt', { text: 'Ziel' }), el('dd', {}, dueInfo(p) || '—'),
            el('dt', { text: 'Team' }), el('dd', {}, p.teamId ? teamBadgeById(p.teamId) : '—'),
            el('dt', { text: 'Land' }), el('dd', {}, land ? el('a', { href: '#/claims/' + enc(land.id), text: land.name }) : '—'),
            el('dt', { text: 'Ort' }), el('dd', { text: p.location ? `${dimName(p.location.dimension)} · X ${p.location.x} · Z ${p.location.z}` : '—' }),
            el('dt', { text: 'Verantwortlich' }), el('dd', { text: p.responsible.length ? p.responsible.map(r => r.name).join(', ') : '—' }),
            el('dt', { text: 'Angelegt' }), el('dd', { text: `${fmtDate(p.createdAt)} von ${p.createdBy.name}` }),
            el('dt', { text: 'Geändert' }), el('dd', { text: fmtAgo(p.updatedAt) })),
          p.notes ? el('div', {}, el('h3', { text: 'Notizen' }), el('p', { class: 'notes', text: p.notes })) : null),
        tasksSection(p)),
      updatesSection(p),
      p.can.delete ? el('section', { class: 'card danger-zone' }, el('h2', { text: 'Löschen' }),
        el('button', { class: 'danger', onclick: async () => {
          if (!await confirmDialog({ title: 'Projekt löschen?', text: `»${p.name}« mit allen Aufgaben und Updates löschen? Das geht nicht rückgängig.`, confirmLabel: 'Löschen', danger: true, requireText: p.name })) return;
          if (await projectAction(p, 'delete', {}, 'Projekt gelöscht.')) navigate('#/projekte');
        } }, 'Projekt löschen')) : null);
  };
  return bind(root, ['projects', 'lands', 'teams', 'prefs'], draw);
}

export default {
  title: 'Projekte',
  render(root, route) {
    load.projects().catch(() => {});
    return route.id ? renderDetail(root, route.id) : renderList(root);
  },
};
