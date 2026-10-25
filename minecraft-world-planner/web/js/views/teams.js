// Teams: Gründen, Einladen (nur mit Zustimmung), Bewerbungen, Rollen, Übergabe, Auflösen.
// Alle Regeln prüft der Server – die Seite zeigt nur Knöpfe, die laut Server ("can") erlaubt sind.
import { api, enc } from '../api.js';
import { navigate } from '../router.js';
import { load, myTeams, store, teamById } from '../store.js';
import { act, confirmDialog, el, fill, fmtAgo, fmtDate, formDialog, pill, plural } from '../util.js';
import { bind, favButton, pageHead, playerDatalist, searchInput } from './common.js';
import { landItem } from './claims.js';

const RANK = { recruit: 1, member: 2, moderator: 3, admin: 4, owner: 5 };
const ROLE_LABEL = { recruit: 'Anwärter', member: 'Mitglied', moderator: 'Moderator', admin: 'Admin', owner: 'Owner' };
const state = { q: '' };

async function refresh() {
  await Promise.all([load.teams(), load.me(), load.lands()]);
}

async function teamAction(team, action, body = {}, okMessage) {
  const r = await act(() => api(`api/teams/${enc(team.id)}/${action}`, body), okMessage);
  if (r) await refresh();
  return r;
}

function teamFields(t = {}) {
  const symbols = (store.meta && store.meta.teamSymbols) || [];
  return [
    { name: 'name', label: 'Teamname (3–32 Zeichen)', value: t.name || '', required: true, maxLength: 32, autofocus: true },
    { name: 'tag', label: 'Kürzel (2–5 Großbuchstaben/Ziffern)', value: t.tag || '', required: true, maxLength: 7, placeholder: 'z. B. KMS' },
    { name: 'description', label: 'Beschreibung', type: 'textarea', value: t.description || '', maxLength: 500 },
    { name: 'color', label: 'Teamfarbe', type: 'color', value: t.color || '#64b5f6' },
    { name: 'symbol', label: 'Logo (Symbol)', type: 'select', value: t.symbol || '', options: [['', '— keins —'], ...symbols.map(s => [s, s])],
      help: 'Statt Bild-Upload: Ein Upload müsste moderiert werden und wäre ein Sicherheitsrisiko.' },
    { name: 'visibility', label: 'Sichtbarkeit', type: 'select', value: t.visibility || 'public',
      options: [['public', 'Öffentlich – alle sehen das Team'], ['private', 'Privat – nur Mitglieder und Eingeladene']],
      help: store.meta && store.meta.chatTeamTags ? 'Öffentliche Teams bekommen im Spiel ihr Kürzel vor den Namen (Chat, Tab-Liste).' : null },
    { name: 'joinPolicy', label: 'Beitritt', type: 'select', value: t.joinPolicy || 'invite',
      options: [['invite', 'Nur per Einladung'], ['apply', 'Bewerbung möglich (nur bei öffentlichen Teams)']] },
  ];
}

async function createTeam() {
  const v = await formDialog({ title: 'Team gründen', intro: 'Du wirst Owner. Mitglieder kommen nur dazu, wenn sie eine Einladung annehmen oder ihr eine Bewerbung annehmt.', fields: teamFields(), submitLabel: 'Gründen' });
  if (!v) return;
  const r = await act(() => api('api/teams', v), `Team [${v.tag.toUpperCase()}] ${v.name} gegründet!`);
  if (r) { await refresh(); navigate('#/teams/' + enc(r.id)); }
}

function teamCard(t) {
  const logo = el('span', { class: 'team-logo', 'aria-hidden': 'true', text: t.symbol || t.tag.slice(0, 2) });
  logo.style.background = t.color;
  return el('article', { class: 'item team-card' + (t.myRole ? ' mine' : '') },
    el('div', { class: 'item-top' },
      el('a', { class: 'row tight title-link', href: '#/teams/' + enc(t.id) }, logo, el('strong', { text: `[${t.tag}] ${t.name}` })),
      el('div', { class: 'row tight' }, favButton('teams', t.id),
        t.myRole ? pill(t.myRoleLabel, 'ok') : null,
        t.visibility === 'private' ? pill('Privat', 'muted') : null,
        t.joinPolicy === 'apply' ? pill('Bewerbung möglich', 'info') : null)),
    el('div', { class: 'meta' }, el('span', { text: plural(t.memberCount, 'Mitglied', 'Mitglieder') }), el('span', { text: 'Owner: ' + (t.owner ? t.owner.name : '?') }),
      el('span', { text: 'seit ' + fmtDate(t.createdAt) })),
    t.description ? el('p', { class: 'notes', text: t.description }) : null);
}

function inviteBox() {
  const invited = store.teams.filter(t => t.myInvite);
  if (!invited.length) return null;
  return el('section', { class: 'card highlight', id: 'teamInvites' },
    el('h2', { text: 'Team-Einladungen für dich' }),
    el('div', { class: 'list' }, invited.map(t => el('div', { class: 'item' },
      el('div', { class: 'item-top' }, el('strong', { text: `[${t.tag}] ${t.name}` }), el('small', { class: 'muted', text: 'von ' + t.myInvite.by.name })),
      el('small', { class: 'muted', text: 'Gültig bis ' + fmtDate(t.myInvite.expiresAt) }),
      el('div', { class: 'row' },
        el('button', { class: 'primary small', onclick: () => teamAction(t, 'accept', {}, `Willkommen bei [${t.tag}]!`) }, 'Annehmen'),
        el('button', { class: 'small', onclick: () => teamAction(t, 'decline', {}, 'Einladung abgelehnt.') }, 'Ablehnen'),
        el('a', { class: 'button small', href: '#/teams/' + enc(t.id) }, 'Ansehen'))))));
}

function renderList(root) {
  const head = el('div'), mine = el('div'), others = el('div');
  const draw = () => {
    const max = (store.meta && store.meta.maxTeamsPerPlayer) || 1;
    const my = myTeams();
    fill(head, pageHead('Teams', max === 1 ? 'Du kannst in einem Team sein.' : `Du kannst in bis zu ${max} Teams sein.`,
      my.length < max ? el('button', { class: 'primary', onclick: createTeam }, '+ Team gründen') : null), inviteBox());
    fill(mine, my.length ? el('section', {}, el('h2', { text: 'Dein Team' + (my.length > 1 ? 's' : '') }), el('div', { class: 'list' }, my.map(teamCard))) : null);
    drawOthers();
  };
  const drawOthers = () => {
    const q = state.q.trim().toLowerCase();
    const list = store.teams.filter(t => !t.myRole && (!q || t.name.toLowerCase().includes(q) || t.tag.toLowerCase().includes(q)));
    fill(others, el('section', {},
      el('div', { class: 'row between' }, el('h2', { text: 'Alle Teams' }), searchInput(state.q, v => { state.q = v; drawOthers(); }, 'Team suchen …')),
      list.length ? el('div', { class: 'list', id: 'teamList' }, list.map(teamCard))
        : el('div', { class: 'empty' }, el('p', { text: store.teams.length ? 'Keine weiteren Teams gefunden.' : 'Noch gibt es keine Teams. Gründe das erste!' }))));
  };
  fill(root, head, mine, others);
  return bind(root, ['teams', 'meta', 'me', 'prefs'], draw);
}

// ------------------------------------------------------------------ Detail

function memberRow(t, m) {
  const me = store.me || {};
  const myRank = RANK[t.myRole] || 0;
  const theirRank = RANK[m.role];
  const manageable = t.can.manageRoles && theirRank < myRank && m.uuid !== me.uuid;
  let roleCell = pill(m.roleLabel, m.role === 'owner' ? 'gold' : '');
  if (manageable) {
    const sel = el('select', { 'aria-label': 'Rolle von ' + m.name },
      Object.keys(RANK).filter(r => RANK[r] < myRank).map(r => el('option', { value: r, text: ROLE_LABEL[r] })));
    sel.value = m.role;
    sel.addEventListener('change', () => teamAction(t, 'role', { player: m.name, role: sel.value }, `${m.name} ist jetzt ${ROLE_LABEL[sel.value]}.`));
    roleCell = sel;
  }
  const canKick = (t.can.manageRoles && theirRank < myRank && m.uuid !== me.uuid) || (me.admin && m.role !== 'owner' && m.uuid !== me.uuid);
  return el('div', { class: 'item row between member' },
    el('div', {}, el('strong', { text: m.name + (m.uuid === me.uuid ? ' (du)' : '') }), el('small', { class: 'muted', text: ' · dabei seit ' + fmtDate(m.joinedAt) })),
    el('div', { class: 'row tight' }, roleCell,
      canKick ? el('button', { class: 'small danger-text', onclick: async () => {
        if (await confirmDialog({ title: 'Aus dem Team entfernen?', text: `${m.name} aus [${t.tag}] entfernen? Länder, Projekte und Marker von ${m.name} bleiben erhalten.`, confirmLabel: 'Entfernen', danger: true })) {
          await teamAction(t, 'kick', { player: m.name }, `${m.name} wurde entfernt.`);
        }
      } }, 'Entfernen') : null));
}

function managementSection(t) {
  if (!t.invites && !t.applications) return null;
  const pendingApps = (t.applications || []).filter(a => a.status === 'pending');
  const invites = (t.invites || []).slice().reverse();
  const STATUS = { pending: 'offen', accepted: 'angenommen', declined: 'abgelehnt', withdrawn: 'zurückgezogen', expired: 'abgelaufen' };
  return el('section', { class: 'card' },
    el('h2', { text: 'Bewerbungen und Einladungen' }),
    el('h3', { text: `Offene Bewerbungen (${pendingApps.length})` }),
    pendingApps.length ? el('div', { class: 'list' }, pendingApps.map(a => el('div', { class: 'item' },
      el('div', { class: 'item-top' }, el('strong', { text: a.player.name }), el('small', { class: 'muted', text: fmtAgo(a.createdAt) })),
      a.message ? el('p', { class: 'notes', text: a.message }) : null,
      el('div', { class: 'row' },
        el('button', { class: 'primary small', onclick: () => teamAction(t, `applications/${enc(a.id)}/accept`, {}, `${a.player.name} ist jetzt Anwärter.`) }, 'Annehmen'),
        el('button', { class: 'small', onclick: () => teamAction(t, `applications/${enc(a.id)}/decline`, {}, 'Bewerbung abgelehnt.') }, 'Ablehnen')))))
      : el('p', { class: 'muted', text: 'Keine offenen Bewerbungen.' }),
    el('h3', { text: 'Einladungen' }),
    invites.length ? el('div', { class: 'list' }, invites.slice(0, 20).map(i => el('div', { class: 'item row between' },
      el('span', { text: `${i.player.name} – ${STATUS[i.status] || i.status}` + (i.status === 'pending' ? ` (bis ${fmtDate(i.expiresAt)})` : '') }),
      i.status === 'pending' ? el('button', { class: 'small', onclick: () => teamAction(t, `invites/${enc(i.id)}/withdraw`, {}, 'Einladung zurückgezogen.') }, 'Zurückziehen') : null)))
      : el('p', { class: 'muted', text: 'Noch keine Einladungen.' }));
}

async function transfer(t) {
  const others = t.members.filter(m => m.role !== 'owner');
  if (!others.length) { await confirmDialog({ title: 'Niemand da', text: 'Übergeben kannst du nur an ein Mitglied des Teams.', confirmLabel: 'OK' }); return; }
  const v = await formDialog({
    title: 'Team übergeben',
    intro: 'Der neue Owner muss die Übergabe selbst annehmen. Bis dahin bleibst du Owner. Danach bist du Admin.',
    fields: [
      { name: 'player', label: 'Neuer Owner', type: 'select', options: others.map(m => [m.name, `${m.name} (${m.roleLabel})`]) },
      { name: 'confirmName', label: `Zur Bestätigung den Teamnamen eintippen: ${t.name}`, required: true },
    ],
    validate: x => (x.confirmName.toLowerCase() !== t.name.toLowerCase() ? 'Der Teamname stimmt nicht.' : null),
    submitLabel: 'Übergabe anbieten',
  });
  if (v) await teamAction(t, 'transfer', v, `Übergabe an ${v.player} angeboten.`);
}

/** Hinweis, ob und wie das Kürzel im Spiel erscheint (Server-Einstellung chatTeamTags). */
function chatTagNote(t) {
  if (!store.meta || !store.meta.chatTeamTags) return null;
  return el('p', { class: 'muted small', id: 'chatTagNote' }, t.visibility === 'public'
    ? `Im Spiel steht [${t.tag}] vor den Namen der Mitglieder – im Chat, in der Tab-Liste und über dem Kopf.`
    : 'Private Teams bekommen im Spiel kein Kürzel – sonst wüsste jeder, wer drin ist.');
}

function teamStuff(t) {
  const lands = store.lands.filter(l => l.teamId === t.id);
  const projects = store.projects.filter(p => p.teamId === t.id);
  const listings = store.trades.filter(x => x.teamId === t.id && x.status === 'active');
  return el('section', { class: 'card' },
    el('h2', { text: 'Team-Länder, Projekte und Angebote' }),
    el('h3', { text: `Länder (${lands.length})` }),
    lands.length ? el('div', { class: 'list' }, lands.map(landItem)) : el('p', { class: 'muted', text: 'Noch keine Länder dem Team zugeordnet. Besitzer können das auf der Land-Seite tun.' }),
    el('h3', { text: `Projekte (${projects.length})` }),
    projects.length ? el('ul', {}, projects.map(p => el('li', {}, el('a', { href: '#/projekte/' + enc(p.id), text: p.name }), el('span', { class: 'muted', text: ` · ${p.progress.done}/${p.progress.total} Aufgaben` }))))
      : el('p', { class: 'muted', text: t.can.createProjects ? 'Noch keine Team-Projekte. Lege eins unter „Projekte“ an.' : 'Keine Team-Projekte.' }),
    el('h3', { text: `Aktive Angebote im Handel (${listings.length})` }),
    listings.length ? el('ul', {}, listings.map(x => el('li', {}, el('a', { href: '#/handel/' + enc(x.id), text: x.title })))) : el('p', { class: 'muted', text: 'Keine aktiven Team-Angebote.' }));
}

function renderDetail(root, id) {
  const draw = () => {
    const t = teamById(id);
    if (!t) {
      fill(root, pageHead('Team nicht gefunden', 'Es wurde aufgelöst oder ist privat.'), el('a', { class: 'button', href: '#/teams' }, '← Alle Teams'));
      return;
    }
    const me = store.me || {};
    const invite = el('input', { list: playerDatalist(), placeholder: 'Spielername', 'aria-label': 'Spieler einladen', autocomplete: 'off' });
    const logo = el('span', { class: 'team-logo big', 'aria-hidden': 'true', text: t.symbol || t.tag.slice(0, 2) });
    logo.style.background = t.color;
    const tr = t.pendingTransfer;
    fill(root,
      el('a', { class: 'back', href: '#/teams' }, '← Alle Teams'),
      el('div', { class: 'page-head' },
        el('div', { class: 'row tight' }, logo, el('div', {}, el('h1', { text: `[${t.tag}] ${t.name}` }),
          el('div', { class: 'row tight' }, t.myRole ? pill('Du: ' + t.myRoleLabel, 'ok') : null, pill(t.visibility === 'public' ? 'Öffentlich' : 'Privat', 'muted'),
            pill(t.joinPolicy === 'apply' ? 'Bewerbung möglich' : 'Nur per Einladung', 'info'), favButton('teams', t.id)))),
        el('div', { class: 'row' }, t.can.edit ? el('button', { onclick: async () => {
          const v = await formDialog({ title: 'Team bearbeiten', fields: teamFields(t) });
          if (v) await teamAction(t, 'edit', v, 'Gespeichert.');
        } }, 'Bearbeiten') : null)),
      t.myInvite ? el('section', { class: 'card highlight' }, el('p', { text: `${t.myInvite.by.name} lädt dich ins Team ein.` }),
        el('div', { class: 'row' },
          el('button', { class: 'primary', onclick: () => teamAction(t, 'accept', {}, `Willkommen bei [${t.tag}]!`) }, 'Annehmen'),
          el('button', { onclick: () => teamAction(t, 'decline', {}, 'Einladung abgelehnt.') }, 'Ablehnen'))) : null,
      tr ? el('section', { class: 'card highlight' },
        el('p', { text: tr.to.uuid === me.uuid ? 'Dir wird dieses Team übergeben. Nimmst du an, wirst du Owner.' : `Übergabe an ${tr.to.name} angeboten – wartet auf Annahme.` }),
        el('div', { class: 'row' },
          tr.to.uuid === me.uuid ? el('button', { class: 'primary', onclick: () => teamAction(t, 'transfer-accept', {}, 'Du bist jetzt Owner.') }, 'Übergabe annehmen') : null,
          el('button', { onclick: () => teamAction(t, 'transfer-cancel', {}, 'Übergabe abgebrochen.') }, tr.to.uuid === me.uuid ? 'Ablehnen' : 'Abbrechen'))) : null,
      t.description ? el('p', { class: 'lead', text: t.description }) : null,
      chatTagNote(t),
      el('div', { class: 'grid two' },
        el('section', { class: 'card' },
          el('h2', { text: `Mitglieder (${t.memberCount})` }),
          el('div', { class: 'list', id: 'memberList' }, t.members.slice().sort((a, b) => RANK[b.role] - RANK[a.role] || a.name.localeCompare(b.name)).map(m => memberRow(t, m))),
          t.can.invite ? el('form', { class: 'row', onsubmit: async e => {
            e.preventDefault();
            const name = invite.value.trim();
            if (name) await teamAction(t, 'invite', { player: name }, `${name} ist eingeladen. Erst nach der Annahme ist ${name} im Team.`);
          } }, invite, el('button', { class: 'small', type: 'submit' }, 'Einladen')) : null,
          el('p', { class: 'muted small', text: 'Rollen: Owner > Admin > Moderator > Mitglied > Anwärter. Man verwaltet nur Leute unter dem eigenen Rang.' })),
        managementSection(t) || teamStuff(t)),
      managementSection(t) ? teamStuff(t) : null,
      el('section', { class: 'card danger-zone' },
        el('h2', { text: 'Mitgliedschaft' }),
        el('div', { class: 'row' },
          t.can.apply ? el('button', { class: 'primary', onclick: async () => {
            const v = await formDialog({ title: `Bei [${t.tag}] bewerben`, fields: [{ name: 'message', label: 'Nachricht an das Team', type: 'textarea', maxLength: 300, autofocus: true }], submitLabel: 'Bewerben' });
            if (v) await teamAction(t, 'apply', v, 'Bewerbung abgeschickt.');
          } }, 'Bewerben') : null,
          t.myApplication ? el('button', { onclick: () => teamAction(t, 'withdraw-application', {}, 'Bewerbung zurückgezogen.') }, 'Bewerbung zurückziehen') : null,
          t.can.leave ? el('button', { onclick: async () => {
            if (await confirmDialog({ title: 'Team verlassen?', text: `Du verlässt [${t.tag}] ${t.name}. Deine Länder, Projekte und Marker bleiben deine.`, confirmLabel: 'Verlassen', danger: true })) {
              if (await teamAction(t, 'leave', {}, 'Du hast das Team verlassen.')) navigate('#/teams');
            }
          } }, 'Team verlassen') : null,
          t.can.transfer ? el('button', { onclick: () => transfer(t) }, 'Team übergeben') : null,
          t.can.dissolve ? el('button', { class: 'danger', onclick: async () => {
            const typed = await confirmDialog({ title: 'Team auflösen?', text: 'Das Team verschwindet. Länder, Projekte, Marker und Angebote bleiben erhalten – sie gehören dann wieder nur ihren Besitzern. Team-interne Projekte und Marker werden privat.',
              confirmLabel: 'Auflösen', danger: true, requireText: t.name });
            if (typed && await teamAction(t, 'dissolve', { confirmName: typed }, 'Team aufgelöst.')) {
              await Promise.all([load.projects(), load.markers(), load.trades()]);
              navigate('#/teams');
            }
          } }, me.admin && t.myRole !== 'owner' ? 'Auflösen (Admin)' : 'Team auflösen') : null),
        t.myRole === 'owner' ? el('p', { class: 'muted small', text: 'Als Owner kannst du nicht einfach austreten: Übergib das Team zuerst oder löse es auf.' }) : null));
  };
  return bind(root, ['teams', 'me', 'lands', 'projects', 'trades', 'prefs'], draw);
}

export default {
  title: 'Teams',
  render(root, route) {
    load.teams().catch(() => {});
    if (route.id) {
      load.projects().catch(() => {});
      load.trades().catch(() => {});
      return renderDetail(root, route.id);
    }
    return renderList(root);
  },
};
