// Handel (Web-Auktionshaus). WICHTIG: Diese Seite überträgt keine Items und kein Geld. Sie dokumentiert Angebote,
// Gebote und Absprachen. Übergeben wird im Spiel; danach bestätigen beide Seiten die Übergabe hier.
import { enc, tradeAction } from '../api.js';
import { navigate } from '../router.js';
import { load, myTeams, options, serverNow, store, tradeById } from '../store.js';
import { act, confirmDialog, el, fill, fmtAgo, fmtDateTime, fmtLeft, fmtNum, formDialog, pill } from '../util.js';
import { bind, favButton, pageHead, searchInput, segmented, selectInput, teamBadgeById } from './common.js';

const state = { tab: 'market', kind: '', category: '', q: '', sort: 'ending' };
const HISTORY = {
  created: 'angelegt', bid: 'Gebot', extended: 'verlängert', sold: 'verkauft', expired: 'abgelaufen', cancelled: 'abgebrochen',
  cancelled_by_admin: 'von Admin abgebrochen', offer: 'Gegenangebot', offer_withdrawn: 'Gegenangebot zurückgezogen', confirmed: 'Übergabe bestätigt',
  handover_completed: 'Übergabe abgeschlossen', disputed: 'Streitfall gemeldet', resolved: 'Streitfall geklärt', team_dissolved: 'Team aufgelöst',
};

const unit = () => store.tradeUnit || (store.meta && store.meta.tradeUnit) || 'Diamanten';
/** Einzahl für die üblichen Einheiten („1 Diamant“ statt „1 Diamanten“) – sonst bleibt die Einheit, wie sie ist. */
const SINGULAR = { Diamanten: 'Diamant', Smaragde: 'Smaragd', Diamantblöcke: 'Diamantblock', Smaragdblöcke: 'Smaragdblock' };
const money = n => `${fmtNum(n)} ${n === 1 ? (SINGULAR[unit()] || unit()) : unit()}`;

async function refresh() {
  await load.trades();
}

async function run(path, body, okMessage) {
  const r = await act(() => tradeAction(path, body), okMessage);
  if (r) await refresh();
  return r;
}

function webModeNote() {
  return el('div', { class: 'notice', role: 'note' },
    el('strong', { text: 'Web-Modus: ' }),
    `Hier werden Angebote, Gebote und Absprachen festgehalten. Die Website überträgt keine Items und keine ${unit()} – ihr tauscht im Spiel selbst und bestätigt danach beide die Übergabe. Bei Problemen gibt es den Streitfall für die Admins.`);
}

function priceLine(l) {
  if (l.kind === 'auction') {
    return [
      el('span', { class: 'price' }, l.highestBid != null ? `Höchstgebot ${money(l.highestBid)}` : `Start ${money(l.startPrice)}`),
      l.buyNowPrice != null ? el('span', { text: `Sofortkauf ${money(l.buyNowPrice)}` }) : null,
      el('span', { text: `${l.bidCount} Gebot${l.bidCount === 1 ? '' : 'e'}` }),
    ];
  }
  if (l.kind === 'fixed') return [el('span', { class: 'price', text: `Preis ${money(l.price)}` })];
  return [el('span', { class: 'price', text: (l.side === 'request' ? 'Bietet dafür: ' : 'Möchte dafür: ') + l.wants })];
}

function myBadges(l) {
  const out = [];
  if (l.mine) out.push(pill('Dein Angebot', 'ok'));
  if (l.status === 'active' && l.iBid) out.push(l.iAmHighest ? pill('Du bist Höchstbietender', 'ok') : pill('Du wurdest überboten', 'warn'));
  if (l.iWon) out.push(pill(l.kind === 'trade' ? 'Mit dir vereinbart' : 'Du hast gekauft', 'ok'));
  if (l.handover && l.handover.status === 'pending' && ((l.mine && !l.handover.sellerConfirmedAt) || (l.iWon && !l.handover.buyerConfirmedAt))) out.push(pill('Übergabe bestätigen!', 'warn'));
  if (l.handover && l.handover.status === 'disputed') out.push(pill('Streitfall', 'danger'));
  return out;
}

function timeLeft(l) {
  if (l.status !== 'active') return el('span', { text: l.statusLabel });
  return el('span', { class: 'countdown', 'data-ends': l.endsAt, text: '⏱ ' + fmtLeft(l.endsAt - serverNow()) });
}

function listingCard(l) {
  return el('article', { class: 'item listing' + (l.mine ? ' mine' : '') },
    el('div', { class: 'item-top' },
      el('a', { class: 'title-link', href: '#/handel/' + enc(l.id) }, el('strong', { text: l.title })),
      el('div', { class: 'row tight' }, favButton('listings', l.id), pill(l.kind === 'trade' ? (l.side === 'request' ? 'Suche' : 'Biete') : l.kindLabel, 'info'),
        l.status !== 'active' ? pill(l.statusLabel, l.status === 'sold' ? 'ok' : 'muted') : null)),
    el('div', { class: 'meta' },
      el('span', { text: l.categoryLabel }),
      l.itemName ? el('span', { text: `${l.quantity > 1 ? l.quantity + ' × ' : ''}${l.itemName}` }) : null,
      el('span', { text: 'von ' + l.seller.name }),
      l.teamId ? teamBadgeById(l.teamId) : null,
      timeLeft(l)),
    el('div', { class: 'meta prices' }, priceLine(l)),
    el('div', { class: 'row tight' }, myBadges(l)));
}

function matches(l) {
  const q = state.q.trim().toLowerCase();
  if (state.kind && l.kind !== state.kind) return false;
  if (state.category && l.category !== state.category) return false;
  if (q && !(l.title.toLowerCase().includes(q) || (l.itemName || '').toLowerCase().includes(q) || (l.description || '').toLowerCase().includes(q)
    || (l.wants || '').toLowerCase().includes(q) || l.seller.name.toLowerCase().includes(q))) return false;
  if (state.tab === 'market') return l.status === 'active';
  if (state.tab === 'mine') return l.mine || l.iBid || l.iWon || (l.offers || []).some(o => o.mine);
  if (state.tab === 'handover') return l.status === 'sold' && (l.mine || l.iWon) && l.handover && ['pending', 'disputed'].includes(l.handover.status);
  if (state.tab === 'fav') return ((store.prefs.favorites || {}).listings || []).includes(l.id);
  return l.status !== 'active';
}

function sortListings(items) {
  const price = l => l.kind === 'auction' ? (l.highestBid ?? l.startPrice) : l.kind === 'fixed' ? l.price : Infinity;
  const sorts = {
    ending: (a, b) => (a.status === 'active' ? a.endsAt : Infinity) - (b.status === 'active' ? b.endsAt : Infinity),
    new: (a, b) => b.createdAt - a.createdAt,
    cheap: (a, b) => price(a) - price(b),
  };
  return items.sort(sorts[state.sort] || sorts.ending);
}

async function createListing() {
  const adminTeams = myTeams().filter(t => ['admin', 'owner'].includes(t.myRole));
  const maxH = ((store.meta && store.meta.maxListingDays) || 14) * 24;
  const durations = [[1, '1 Stunde'], [6, '6 Stunden'], [12, '12 Stunden'], [24, '1 Tag'], [48, '2 Tage'], [72, '3 Tage'], [168, '7 Tage'], [336, '14 Tage']]
    .filter(([h]) => h <= maxH).map(([h, t]) => [String(h), t]);
  const v = await formDialog({
    title: 'Neues Angebot',
    intro: `Preise in ${unit()}. Die Website überträgt nichts – die Übergabe macht ihr im Spiel.`,
    fields: [
      { name: 'kind', label: 'Art', type: 'select', value: 'auction', options: options('tradeKinds') },
      { name: 'side', label: 'Biete oder suche?', type: 'select', value: 'offer', options: [['offer', 'Ich biete etwas an'], ['request', 'Ich suche etwas']], showIf: x => x.kind === 'trade' },
      { name: 'title', label: 'Titel', required: true, maxLength: 64, autofocus: true, placeholder: 'z. B. Netherite-Spitzhacke (Effizienz V)' },
      { name: 'category', label: 'Kategorie', type: 'select', value: 'other', options: options('tradeCategories'),
        help: 'Für Hilfe (Bauen, Kämpfen …) gibt es eigene Kategorien – dann braucht es kein Item.' },
      { name: 'itemName', label: 'Item', maxLength: 64, placeholder: 'z. B. Diamantspitzhacke' },
      { name: 'quantity', label: 'Menge', type: 'number', value: 1, min: 1, max: 100000, step: 1 },
      { name: 'startPrice', label: `Startgebot (${unit()})`, type: 'number', min: 1, step: 1, showIf: x => x.kind === 'auction' },
      { name: 'minIncrement', label: 'Mindestschritt pro Gebot', type: 'number', value: (store.meta && store.meta.tradeMinIncrement) || 1, min: 1, step: 1, showIf: x => x.kind === 'auction' },
      { name: 'buyNowPrice', label: `Sofortkaufpreis (optional, ${unit()})`, type: 'number', min: 1, step: 1, showIf: x => x.kind === 'auction' },
      { name: 'price', label: `Festpreis (${unit()})`, type: 'number', min: 1, step: 1, showIf: x => x.kind === 'fixed' },
      { name: 'wants', label: 'Gegenleistung', maxLength: 200, placeholder: 'z. B. 2 Stacks Eisen oder 2 Stunden Hilfe', showIf: x => x.kind === 'trade' },
      { name: 'durationHours', label: 'Laufzeit', type: 'select', value: durations.some(d => d[0] === '72') ? '72' : durations[durations.length - 1][0], options: durations },
      { name: 'description', label: 'Beschreibung', type: 'textarea', maxLength: 1000 },
      { name: 'teamId', label: 'Im Namen von', type: 'select', value: '', options: [['', 'mir selbst'], ...adminTeams.map(t => [t.id, `Team [${t.tag}] ${t.name}`])], showIf: () => adminTeams.length > 0 },
    ],
    validate: x => {
      if (x.title.length < 3) return 'Der Titel braucht mindestens 3 Zeichen.';
      if (x.kind === 'auction' && !(x.startPrice >= 1)) return 'Bitte ein Startgebot angeben.';
      if (x.kind === 'auction' && x.buyNowPrice != null && x.buyNowPrice <= x.startPrice) return 'Der Sofortkaufpreis muss über dem Startgebot liegen.';
      if (x.kind === 'fixed' && !(x.price >= 1)) return 'Bitte einen Preis angeben.';
      if (x.kind === 'trade' && !x.wants) return 'Bitte die Gegenleistung angeben.';
      if (x.kind !== 'trade' && !x.category.startsWith('help_') && !x.itemName) return 'Bitte das Item angeben.';
      return null;
    },
    submitLabel: 'Angebot veröffentlichen',
  });
  if (!v) return;
  const body = { kind: v.kind, title: v.title, category: v.category, itemName: v.itemName, quantity: v.quantity || 1, description: v.description,
    durationHours: Number(v.durationHours), teamId: v.teamId || '' };
  if (v.kind === 'trade') { body.side = v.side; body.wants = v.wants; }
  if (v.kind === 'auction') { body.startPrice = v.startPrice; body.minIncrement = v.minIncrement || 1; if (v.buyNowPrice != null) body.buyNowPrice = v.buyNowPrice; }
  if (v.kind === 'fixed') body.price = v.price;
  const r = await run('api/trades', body, 'Angebot veröffentlicht.');
  if (r) navigate('#/handel/' + enc(r.id));
}

function renderList(root) {
  const head = el('div'), controls = el('div', { class: 'toolbar' }), listBox = el('div');
  const draw = () => {
    fill(head, pageHead('Handel', 'Auktionen, Festpreise und Tausch – auch Hilfe beim Bauen oder Kämpfen.',
      el('button', { class: 'primary', onclick: createListing }, '+ Neues Angebot')), webModeNote());
    const handovers = store.trades.filter(l => l.status === 'sold' && (l.mine || l.iWon) && l.handover && ['pending', 'disputed'].includes(l.handover.status)).length;
    fill(controls,
      segmented([['market', 'Marktplatz'], ['mine', 'Meine'], ['handover', `Übergaben${handovers ? ' (' + handovers + ')' : ''}`], ['fav', '★'], ['closed', 'Abgeschlossen']], state.tab, v => { state.tab = v; draw(); }),
      selectInput([['', 'Alle Arten'], ...options('tradeKinds')], state.kind, v => { state.kind = v; draw(); }, 'Art'),
      selectInput([['', 'Alle Kategorien'], ...options('tradeCategories')], state.category, v => { state.category = v; draw(); }, 'Kategorie'),
      selectInput([['ending', 'Endet bald'], ['new', 'Neueste'], ['cheap', 'Günstigste']], state.sort, v => { state.sort = v; draw(); }, 'Sortierung'),
      searchInput(state.q, v => { state.q = v; drawList(); }, 'Angebote suchen …'));
    drawList();
  };
  const drawList = () => {
    const items = sortListings(store.trades.filter(matches));
    const emptyText = {
      market: 'Gerade gibt es keine aktiven Angebote. Stell doch selbst etwas ein!',
      mine: 'Du hast noch nichts angeboten, geboten oder gekauft.',
      handover: 'Keine offenen Übergaben.',
      fav: 'Keine Favoriten.',
      closed: 'Noch keine abgeschlossenen Angebote.',
    }[state.tab];
    fill(listBox, items.length ? el('div', { class: 'list', id: 'tradeList' }, items.map(listingCard)) : el('div', { class: 'empty' }, el('p', { text: emptyText })));
  };
  fill(root, head, controls, listBox);
  return bind(root, ['trades', 'meta', 'prefs', 'teams'], draw);
}

// ------------------------------------------------------------------ Detail

function bidBox(l) {
  if (!l.can.bid) return null;
  if (l.iAmHighest) return el('p', { class: 'ok-text', text: 'Du bist Höchstbietender. Wirst du überboten, siehst du es hier und im Dashboard.' });
  const input = el('input', { id: 'bidAmount', type: 'number', min: l.minimumBid, step: 1, value: l.minimumBid, 'aria-label': 'Dein Gebot' });
  return el('form', { class: 'row', onsubmit: async e => {
    e.preventDefault();
    const amount = Number(input.value);
    if (!Number.isInteger(amount)) return;
    if (!await confirmDialog({ title: `${money(amount)} bieten?`, text: `Gewinnst du, verpflichtest du dich, ${l.seller.name} im Spiel ${money(amount)} zu übergeben. Ein Gebot kurz vor Schluss verlängert die Auktion um 2 Minuten.`, confirmLabel: 'Verbindlich bieten' })) return;
    await run(`api/trades/${enc(l.id)}/bid`, { amount }, r => (r.extended ? 'Gebot abgegeben – die Auktion wurde um 2 Minuten verlängert.' : 'Gebot abgegeben.'));
  } }, el('label', { class: 'field inline' }, el('span', { text: `Mindestens ${money(l.minimumBid)}` }), input), el('button', { class: 'primary', type: 'submit' }, 'Bieten'));
}

function buyButton(l) {
  if (!l.can.buy) return null;
  const price = l.kind === 'fixed' ? l.price : l.buyNowPrice;
  return el('button', { class: 'primary', id: 'buyBtn', onclick: async () => {
    if (!await confirmDialog({ title: `Für ${money(price)} kaufen?`, text: `Du verpflichtest dich, ${l.seller.name} im Spiel ${money(price)} zu übergeben. ${l.seller.name} gibt dir dafür: ${l.quantity > 1 ? l.quantity + ' × ' : ''}${l.itemName || l.title}. Danach bestätigt ihr beide die Übergabe.`, confirmLabel: 'Verbindlich kaufen' })) return;
    await run(`api/trades/${enc(l.id)}/buy`, {}, 'Gekauft! Jetzt im Spiel übergeben und danach bestätigen.');
  } }, l.kind === 'fixed' ? `Kaufen für ${money(price)}` : `Sofort kaufen für ${money(price)}`);
}

function offersBox(l) {
  if (l.kind !== 'trade') return null;
  const STATUS = { open: 'offen', accepted: 'angenommen', declined: 'abgelehnt', withdrawn: 'zurückgezogen' };
  const text = el('textarea', { rows: 2, maxlength: 300, placeholder: 'Was bietest du an?', 'aria-label': 'Gegenangebot' });
  return el('section', { class: 'card' },
    el('h2', { text: 'Gegenangebote' }),
    el('p', { class: 'muted small', text: l.mine ? 'Du siehst alle Gegenangebote und nimmst eins an.' : `${l.openOfferCount} offen. Du siehst nur dein eigenes.` }),
    (l.offers || []).length ? el('div', { class: 'list' }, l.offers.map(o => el('div', { class: 'item' },
      el('div', { class: 'item-top' }, el('strong', { text: o.from.name + (o.mine ? ' (du)' : '') }), pill(STATUS[o.status] || o.status, o.status === 'accepted' ? 'ok' : 'muted')),
      el('p', { class: 'notes', text: o.text }),
      el('div', { class: 'row' },
        l.can.acceptOffer && o.status === 'open' ? el('button', { class: 'primary small', onclick: async () => {
          if (await confirmDialog({ title: 'Gegenangebot annehmen?', text: `Ihr vereinbart den Tausch mit ${o.from.name}. Alle anderen Gegenangebote werden abgelehnt.`, confirmLabel: 'Annehmen' })) {
            await run(`api/trades/${enc(l.id)}/offers/${enc(o.id)}/accept`, {}, 'Vereinbart! Jetzt im Spiel tauschen und danach bestätigen.');
          }
        } }, 'Annehmen') : null,
        o.mine && o.status === 'open' ? el('button', { class: 'small', onclick: () => run(`api/trades/${enc(l.id)}/offers/${enc(o.id)}/withdraw`, {}, 'Zurückgezogen.') }, 'Zurückziehen') : null))))
      : el('p', { class: 'muted', text: 'Noch keine Gegenangebote.' }),
    l.can.offer ? el('form', { class: 'stack', onsubmit: async e => {
      e.preventDefault();
      if (text.value.trim().length >= 3) await run(`api/trades/${enc(l.id)}/offer`, { text: text.value.trim() }, 'Gegenangebot geschickt.');
    } }, text, el('button', { class: 'primary', type: 'submit' }, 'Gegenangebot schicken')) : null);
}

function handoverBox(l) {
  if (!l.handover) return null;
  const h = l.handover;
  return el('section', { class: 'card ' + (h.status === 'disputed' ? 'danger-zone' : 'highlight'), id: 'handoverBox' },
    el('h2', { text: 'Übergabe' }),
    el('p', {}, el('strong', { text: h.label }), l.buyer ? ` – zwischen ${l.seller.name} und ${l.buyer.name}` : ''),
    el('ul', {},
      el('li', { text: (h.sellerConfirmedAt ? '✓ ' : '○ ') + `${l.kind === 'trade' ? 'Ersteller' : 'Verkäufer'} hat bestätigt` + (h.sellerConfirmedAt ? ` (${fmtDateTime(h.sellerConfirmedAt)})` : '') }),
      el('li', { text: (h.buyerConfirmedAt ? '✓ ' : '○ ') + `${l.kind === 'trade' ? 'Partner' : 'Käufer'} hat bestätigt` + (h.buyerConfirmedAt ? ` (${fmtDateTime(h.buyerConfirmedAt)})` : '') })),
    h.disputeReason ? el('p', { class: 'notes', text: `Streitfall von ${h.disputeBy.name}: ${h.disputeReason}` }) : null,
    h.resolutionNote ? el('p', { class: 'notes', text: `Geklärt von ${h.resolvedBy.name}: ${h.resolutionNote}` }) : null,
    el('p', { class: 'muted small', text: 'Bestätige erst, wenn du deinen Teil im Spiel wirklich bekommen hast. Die Website prüft das nicht – sie hält nur fest, was ihr bestätigt.' }),
    el('div', { class: 'row' },
      l.can.confirm ? el('button', { class: 'primary', id: 'confirmHandoverBtn', onclick: async () => {
        if (await confirmDialog({ title: 'Übergabe bestätigen?', text: 'Hast du deinen Teil im Spiel bekommen bzw. übergeben?', confirmLabel: 'Ja, bestätigen' })) {
          await run(`api/trades/${enc(l.id)}/confirm`, {}, 'Bestätigt.');
        }
      } }, 'Übergabe bestätigen') : null,
      l.can.dispute ? el('button', { class: 'danger-text', onclick: async () => {
        const v = await formDialog({ title: 'Streitfall melden', intro: 'Ein Admin sieht sich das an. Beschreibe kurz, was nicht stimmt.',
          fields: [{ name: 'reason', label: 'Was ist passiert?', type: 'textarea', required: true, maxLength: 300, autofocus: true }],
          validate: x => (x.reason.length < 5 ? 'Bitte etwas genauer (mindestens 5 Zeichen).' : null), submitLabel: 'Melden' });
        if (v) await run(`api/trades/${enc(l.id)}/dispute`, v, 'Streitfall gemeldet.');
      } }, 'Problem melden') : null,
      l.can.resolve ? el('button', { class: 'primary', onclick: async () => {
        const v = await formDialog({ title: 'Streitfall klären (Admin)', fields: [
          { name: 'completed', label: 'Ergebnis', type: 'select', value: 'true', options: [['true', 'Erledigt – der Handel gilt'], ['false', 'Abgebrochen – der Handel gilt nicht']] },
          { name: 'note', label: 'Notiz für beide', type: 'textarea', required: true, maxLength: 300 }],
          validate: x => (x.note.length < 3 ? 'Bitte eine Notiz schreiben.' : null), submitLabel: 'Klären' });
        if (v) await run(`api/trades/${enc(l.id)}/resolve`, { completed: v.completed === 'true', note: v.note }, 'Streitfall geklärt.');
      } }, 'Streitfall klären') : null));
}

function renderDetail(root, id) {
  const draw = () => {
    const l = tradeById(id);
    if (!l) {
      fill(root, pageHead('Angebot nicht gefunden'), el('a', { class: 'button', href: '#/handel' }, '← Zum Handel'));
      return;
    }
    fill(root,
      el('a', { class: 'back', href: '#/handel' }, '← Zum Handel'),
      el('div', { class: 'page-head' },
        el('div', {}, el('h1', { text: l.title }),
          el('div', { class: 'row tight' }, pill(l.kind === 'trade' ? (l.side === 'request' ? 'Suche' : 'Biete') : l.kindLabel, 'info'), pill(l.statusLabel, l.status === 'active' ? 'ok' : 'muted'),
            favButton('listings', l.id), myBadges(l))),
        el('div', { class: 'row' }, buyButton(l))),
      webModeNote(),
      el('div', { class: 'grid two' },
        el('section', { class: 'card' },
          el('h2', { text: 'Angebot' }),
          el('dl', { class: 'facts' },
            el('dt', { text: 'Kategorie' }), el('dd', { text: l.categoryLabel }),
            l.itemName ? [el('dt', { text: 'Item' }), el('dd', { text: `${l.quantity > 1 ? l.quantity + ' × ' : ''}${l.itemName}` })] : null,
            el('dt', { text: l.kind === 'trade' && l.side === 'request' ? 'Sucht' : 'Von' }), el('dd', {}, l.seller.name, ' ', l.teamId ? teamBadgeById(l.teamId) : null),
            l.kind === 'trade' ? [el('dt', { text: l.side === 'request' ? 'Bietet dafür' : 'Möchte dafür' }), el('dd', { text: l.wants })] : null,
            l.kind === 'auction' ? [el('dt', { text: 'Startgebot' }), el('dd', { text: money(l.startPrice) }), el('dt', { text: 'Mindestschritt' }), el('dd', { text: money(l.minIncrement) })] : null,
            l.buyNowPrice != null ? [el('dt', { text: 'Sofortkauf' }), el('dd', { text: money(l.buyNowPrice) })] : null,
            l.kind === 'fixed' ? [el('dt', { text: 'Preis' }), el('dd', { text: money(l.price) })] : null,
            el('dt', { text: 'Eingestellt' }), el('dd', { text: fmtDateTime(l.createdAt) }),
            el('dt', { text: l.status === 'active' ? 'Endet' : 'Ende' }), el('dd', {}, fmtDateTime(l.endsAt), ' ', l.status === 'active' ? timeLeft(l) : null),
            l.status === 'sold' && l.finalPrice != null ? [el('dt', { text: 'Verkauft für' }), el('dd', { text: money(l.finalPrice) })] : null),
          l.description ? el('p', { class: 'notes', text: l.description }) : null),
        l.kind === 'auction' ? el('section', { class: 'card' },
          el('h2', { text: 'Gebote' }),
          l.highestBid != null ? el('p', { class: 'big-number', text: money(l.highestBid) }) : el('p', { class: 'muted', text: 'Noch keine Gebote.' }),
          bidBox(l),
          l.bids.length ? el('ol', { class: 'bids', reversed: true }, l.bids.slice().reverse().map(b => el('li', {},
            el('strong', { text: money(b.amount) }), ' · ', b.bidder ? b.bidder.name + (b.mine ? ' (du)' : '') : b.alias, el('small', { class: 'muted', text: ' · ' + fmtAgo(b.at, serverNow()) }))))
            : null,
          el('p', { class: 'muted small', text: 'Wer geboten hat, sieht nur der Verkäufer. Für alle anderen heißen Bieter „Bieter 1, 2, …“.' }))
          : offersBox(l)),
      handoverBox(l),
      l.history ? el('section', { class: 'card' }, el('h2', { text: 'Verlauf' }),
        el('ul', { class: 'timeline' }, l.history.slice().reverse().map(e => el('li', {},
          el('span', { text: (HISTORY[e.action] || e.action) + (e.detail ? ': ' + e.detail : '') + (e.by ? ' – ' + e.by.name : '') }),
          el('small', { class: 'muted', text: ' · ' + fmtDateTime(e.at) }))))) : null,
      (l.can.cancel) ? el('section', { class: 'card danger-zone' },
        el('h2', { text: 'Abbrechen' }),
        el('button', { class: 'danger', onclick: async () => {
          const isAdminCancel = !l.mine;
          const v = await formDialog({ title: isAdminCancel ? 'Angebot abbrechen (Admin)' : 'Angebot zurückziehen?',
            fields: [{ name: 'reason', label: isAdminCancel ? 'Grund (Pflicht, sehen die Beteiligten)' : 'Grund (optional)', type: 'textarea', maxLength: 200, required: isAdminCancel }],
            validate: x => (isAdminCancel && !x.reason ? 'Bitte einen Grund angeben.' : null), submitLabel: 'Abbrechen bestätigen' });
          if (v) await run(`api/trades/${enc(l.id)}/cancel`, v, 'Angebot abgebrochen.');
        } }, l.mine ? 'Angebot zurückziehen' : 'Abbrechen (Admin)'),
        l.kind === 'auction' ? el('p', { class: 'muted small', text: 'Sobald es Gebote gibt, kann eine Auktion nur noch ein Admin abbrechen.' }) : null) : null);
  };
  return bind(root, ['trades', 'prefs', 'teams'], draw);
}

/** Restzeiten jede Sekunde aktualisieren (ohne die Seite neu zu bauen). */
let ticker = null;
function startTicker() {
  stopTicker();
  ticker = setInterval(() => {
    for (const n of document.querySelectorAll('.countdown[data-ends]')) {
      n.textContent = '⏱ ' + fmtLeft(Number(n.dataset.ends) - serverNow());
    }
  }, 1000);
}
function stopTicker() {
  if (ticker) clearInterval(ticker);
  ticker = null;
}

export default {
  title: 'Handel',
  render(root, route) {
    load.trades().catch(() => {});
    startTicker();
    const cleanup = route.id ? renderDetail(root, route.id) : renderList(root);
    return () => { cleanup(); stopTicker(); };
  },
};
