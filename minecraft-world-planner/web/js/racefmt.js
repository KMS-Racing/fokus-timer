// Rundenzeiten wie im Spiel (RaceStore.time): "1:23,46" – Hundertstel, genauer misst Minecraft nicht.

export function fmtLap(ms) {
  const cs = Math.round(ms / 10);
  const m = Math.floor(cs / 6000), s = Math.floor(cs / 100) % 60, h = cs % 100;
  return `${m > 0 ? `${m}:${String(s).padStart(2, '0')}` : s},${String(h).padStart(2, '0')}`;
}

/** Abstand: "+0,32" oder "−0,12" (mit echtem Minuszeichen). */
export function fmtDelta(ms) {
  return (ms < 0 ? '−' : '+') + fmtLap(Math.abs(ms));
}

/**
 * Streckenplan: Tore und die Verbindung ihrer Mitten, auf eine Fläche von width × height skaliert.
 * Norden (−Z) ist oben, wie auf der Karte. Gibt Linien in Bildkoordinaten zurück.
 */
export function trackPlan(gates, type, width, height, pad = 18) {
  if (!gates.length) return { gates: [], path: [] };
  const pts = [];
  const ends = gates.map(g => {
    const rad = g.yaw * Math.PI / 180, half = g.width / 2;
    const ax = Math.cos(rad), az = Math.sin(rad); // quer zur Fahrtrichtung
    const a = [g.x - ax * half, g.z - az * half], b = [g.x + ax * half, g.z + az * half];
    pts.push(a, b, [g.x, g.z]);
    return { a, b, c: [g.x, g.z], forward: [-Math.sin(rad), Math.cos(rad)] };
  });
  const xs = pts.map(p => p[0]), zs = pts.map(p => p[1]);
  const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs);
  const scale = Math.min((width - 2 * pad) / Math.max(1, maxX - minX), (height - 2 * pad) / Math.max(1, maxZ - minZ));
  const ox = (width - (maxX - minX) * scale) / 2, oz = (height - (maxZ - minZ) * scale) / 2;
  const map = ([x, z]) => [ox + (x - minX) * scale, oz + (z - minZ) * scale];
  const path = ends.map(e => map(e.c));
  if (type !== 'sprint' && path.length > 1) path.push(path[0]);
  return { gates: ends.map(e => ({ a: map(e.a), b: map(e.b), c: map(e.c), forward: e.forward })), path };
}
