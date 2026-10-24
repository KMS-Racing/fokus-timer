// Koordinaten-Werkzeuge. Reine Rechnungen ohne Seite – deshalb mit node --test prüfbar (tests/web/).
//
// Minecraft-Regeln:
// - Ein Chunk ist 16 × 16 Blöcke. Chunk = abrunden(Block / 16), auch bei negativen Zahlen (-1 liegt in Chunk -1).
// - Eine Region (Datei r.X.Z.mca) ist 32 × 32 Chunks = 512 × 512 Blöcke.
// - Nether ↔ Oberwelt: 1 Block im Nether entspricht 8 Blöcken in der Oberwelt (nur X und Z, Y bleibt).
// - Norden ist −Z, Osten ist +X.

export const OVERWORLD = 'minecraft:overworld';
export const NETHER = 'minecraft:the_nether';
export const END = 'minecraft:the_end';
export const WORLD_LIMIT = 30_000_000;

export const blockToChunk = b => Math.floor(b / 16);
export const chunkToRegion = c => Math.floor(c / 32);
export const blockToRegion = b => chunkToRegion(blockToChunk(b));

/** Blöcke eines Chunks (einschließlich) */
export function chunkBounds(cx, cz) {
  return { minX: cx * 16, maxX: cx * 16 + 15, minZ: cz * 16, maxZ: cz * 16 + 15 };
}

/** Block in der Oberwelt → passender Block im Nether (für Portale). */
export function overworldToNether(x, z) {
  return { x: Math.floor(x / 8), z: Math.floor(z / 8) };
}

/** Block im Nether → passender Block in der Oberwelt. */
export function netherToOverworld(x, z) {
  return { x: Math.floor(x) * 8, z: Math.floor(z) * 8 };
}

/** Umrechnung je nach Dimension, sonst null (das Ende hat keine Entsprechung). */
export function convertForPortal(dim, x, z) {
  if (dim === OVERWORLD) return { dim: NETHER, ...overworldToNether(x, z) };
  if (dim === NETHER) return { dim: OVERWORLD, ...netherToOverworld(x, z) };
  return null;
}

export function distance2d(a, b) {
  return Math.hypot(b.x - a.x, b.z - a.z);
}

export function distance3d(a, b) {
  return Math.hypot(b.x - a.x, (b.y ?? 0) - (a.y ?? 0), b.z - a.z);
}

const DIRECTIONS = ['Norden', 'Nordosten', 'Osten', 'Südosten', 'Süden', 'Südwesten', 'Westen', 'Nordwesten'];

/** Himmelsrichtung von a nach b (Minecraft: −Z = Norden, +X = Osten). */
export function direction(a, b) {
  const dx = b.x - a.x, dz = b.z - a.z;
  if (dx === 0 && dz === 0) return 'hier';
  // Winkel im Uhrzeigersinn ab Norden
  const deg = (Math.atan2(dx, -dz) * 180 / Math.PI + 360) % 360;
  return DIRECTIONS[Math.round(deg / 45) % 8];
}

/**
 * Liest Koordinaten aus fast jedem Text: „100 64 -200“, „100, -200“, „X: 100 Y: 64 Z: -200“,
 * „/tp @s 100 64 -200“, F3-Anzeige „XYZ: 100.5 / 64.0 / -200.3“ oder „Block: 100 64 -200“.
 * Relative Angaben wie „~ ~ ~“ gehen nicht (dafür bräuchte man die eigene Position).
 * Zwei Zahlen = X und Z, drei Zahlen = X, Y, Z. Gibt null zurück, wenn es keine gültigen Koordinaten sind.
 */
export function parseCoords(text) {
  if (typeof text !== 'string') return null;
  const s = text.trim();
  if (!s || s.length > 120) return null;
  // Benannte Werte (X: … Y: … Z: …) haben Vorrang
  const named = {};
  for (const m of s.matchAll(/\b([xyz])\s*[:=]\s*(-?\d+(?:[.,]\d+)?)/gi)) named[m[1].toLowerCase()] = toNumber(m[2]);
  if (named.x !== undefined && named.z !== undefined) return finish(named.x, named.y, named.z);
  // Sonst: alle Zahlen der Reihe nach (Komma als Dezimaltrenner nur direkt zwischen Ziffern ohne Leerzeichen)
  const cleaned = s.replace(/^\/?(tp|teleport)\s+(@[a-z]\S*|\w+)\s+/i, '').replace(/^(xyz|block):\s*/i, '');
  const numbers = [...cleaned.matchAll(/-?\d+(?:\.\d+)?/g)].map(m => Number(m[0]));
  const rest = cleaned.replace(/-?\d+(?:\.\d+)?/g, '').replace(/[\s,;/|]+/g, '');
  if (rest.length > 0 && !/^[xyz:]*$/i.test(rest)) return null; // da steht noch etwas anderes als Zahlen
  if (numbers.length === 2) return finish(numbers[0], undefined, numbers[1]);
  if (numbers.length === 3) return finish(numbers[0], numbers[1], numbers[2]);
  return null;
}

function toNumber(raw) {
  return Number(raw.replace(',', '.'));
}

function finish(x, y, z) {
  if (![x, z].every(Number.isFinite) || Math.abs(x) > WORLD_LIMIT || Math.abs(z) > WORLD_LIMIT) return null;
  const out = { x: Math.floor(x), z: Math.floor(z) };
  if (y !== undefined && Number.isFinite(y) && Math.abs(y) <= 4096) out.y = Math.floor(y);
  return out;
}

/** Teleport-Befehl zum Kopieren (für Admins oder Einzelspieler-Tests). */
export function tpCommand(dim, x, y, z) {
  const c = `${Math.floor(x)} ${Math.floor(y ?? 64)} ${Math.floor(z)}`;
  return dim === OVERWORLD || !dim ? `/tp @s ${c}` : `/execute in ${dim} run tp @s ${c}`;
}

export function fmtCoords(p) {
  return p.y == null ? `X ${p.x} · Z ${p.z}` : `X ${p.x} · Y ${p.y} · Z ${p.z}`;
}

/** Entfernungen lesbar: 950 Blöcke, 12,3 km */
export function fmtDistance(blocks) {
  if (blocks < 1000) return `${Math.round(blocks)} Blöcke`;
  return `${(blocks / 1000).toLocaleString('de-DE', { maximumFractionDigits: 1 })} km`;
}
