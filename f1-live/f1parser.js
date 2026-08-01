'use strict';

// =====================================================================
//  F1 25 / F1 26 UDP-Telemetrie – Parser
// ---------------------------------------------------------------------
//  F1 verschickt laufend kleine Daten-Pakete ("Packets") per UDP.
//  Jedes Paket hat einen 29 Byte langen Kopf (Header) und danach die
//  eigentlichen Daten – meistens für ALLE 22 Autos hintereinander.
//
//  Wir lesen nur die Daten DEINES Autos. Welches das ist, steht im
//  Header (playerCarIndex). Damit springen wir an die richtige Stelle.
//
//  WICHTIG – mehrere Spiel-Jahre:
//  Jedes F1-Spiel schreibt im Header sein "packetFormat" (z.B. 2025 oder
//  2026). Die Byte-Positionen können sich zwischen den Jahren leicht
//  unterscheiden. Deshalb hat unten JEDES Jahr seine eigene Tabelle
//  (LAYOUTS). Für F1 26 nehmen wir bis auf Weiteres dieselbe Tabelle wie
//  F1 25 – sollte ein Wert unsinnig sein, wird NUR die 2026-Tabelle
//  angepasst (siehe Debug-Modus in server.js).
// =====================================================================

const HEADER_SIZE = 29;

// ---- Positions-Tabellen je Spiel-Jahr -------------------------------
// size            = Größe eines Auto-Blocks in diesem Paket (Bytes)
// die übrigen Zahlen sind Offsets INNERHALB eines Auto-Blocks
// (session-Offsets sind relativ zum Header-Ende).
const LAYOUT_25 = {
  lapData:   { size: 57, lastLapMs: 0, currentLapMs: 4, position: 32, lapNum: 33 },
  telemetry: { size: 60, speed: 0, surfaceTempStart: 30 },
  status:    { size: 55, fuelInTank: 5, fuelRemainingLaps: 13, visualCompound: 26, tyresAge: 27 },
  damage:    { size: 42, tyresWearStart: 0 },
  session:   { trackTemp: 1, airTemp: 2, totalLaps: 3 },
};

// F1 26: vorerst identisch zu F1 25 (Basis-Annahme, bei Bedarf hier anpassen)
const LAYOUT_26 = JSON.parse(JSON.stringify(LAYOUT_25));

const LAYOUTS = {
  2024: LAYOUT_25,   // F1 24 nutzt (fast) dasselbe Layout
  2025: LAYOUT_25,
  2026: LAYOUT_26,
};

// Wählt die passende Tabelle; unbekannte Formate -> F1-25-Basis
function layoutFuer(packetFormat) {
  return LAYOUTS[packetFormat] || LAYOUT_25;
}

// Reifen-Mischung (visualTyreCompound) -> lesbarer Name + Farbe
const REIFEN = {
  16: { name: 'Soft',   farbe: '#ff5f57' },
  17: { name: 'Medium', farbe: '#e3b341' },
  18: { name: 'Hard',   farbe: '#e0e0e0' },
  7:  { name: 'Inter',  farbe: '#3fb950' },
  8:  { name: 'Wet',    farbe: '#4a9eff' },
};

// Liest den Kopf (Header) eines jeden Pakets
function parseHeader(buf) {
  return {
    packetFormat:   buf.readUInt16LE(0),  // z.B. 2025 oder 2026
    gameYear:       buf.readUInt8(2),      // letzte zwei Ziffern, z.B. 25 oder 26
    packetId:       buf.readUInt8(6),      // welche Art Paket
    playerCarIndex: buf.readUInt8(27),     // welches Auto ist deins
  };
}

// F1 speichert 4er-Reifenwerte in der Reihenfolge: RL, RR, FL, FR.
// Wir machen daraus ein handliches Objekt {FL, FR, RL, RR}.
function vierRaeder(werte) {
  return { RL: werte[0], RR: werte[1], FL: werte[2], FR: werte[3] };
}

// Hauptfunktion: nimmt ein Paket (buf) und schreibt die Werte deines
// Autos in das übergebene "zustand"-Objekt.
function parse(buf, zustand) {
  if (buf.length < HEADER_SIZE) return zustand;

  const h = parseHeader(buf);
  const L = layoutFuer(h.packetFormat);
  const idx = h.playerCarIndex;
  zustand.packetFormat = h.packetFormat;
  zustand.gameYear = h.gameYear;

  // Startposition der Daten deines Autos im jeweiligen Paket
  const start = (stride) => HEADER_SIZE + idx * stride;

  switch (h.packetId) {

    case 2: { // ---- Lap Data: Rundenzeiten & Position ----
      const f = L.lapData;
      const o = start(f.size);
      if (buf.length < o + f.size) break;
      zustand.lastLapMs    = buf.readUInt32LE(o + f.lastLapMs);
      zustand.currentLapMs = buf.readUInt32LE(o + f.currentLapMs);
      zustand.position     = buf.readUInt8(o + f.position);
      zustand.lapNum       = buf.readUInt8(o + f.lapNum);
      break;
    }

    case 6: { // ---- Car Telemetry: Tempo & Reifen-Temperaturen ----
      const f = L.telemetry;
      const o = start(f.size);
      if (buf.length < o + f.size) break;
      zustand.speed = buf.readUInt16LE(o + f.speed); // km/h
      const oberflaeche = [0, 1, 2, 3].map(i => buf.readUInt8(o + f.surfaceTempStart + i));
      zustand.reifenTemp = vierRaeder(oberflaeche); // °C an der Reifenoberfläche
      break;
    }

    case 7: { // ---- Car Status: Reifen-Mischung, Alter, Sprit ----
      const f = L.status;
      const o = start(f.size);
      if (buf.length < o + f.size) break;
      zustand.fuelInTank        = buf.readFloatLE(o + f.fuelInTank);        // kg Sprit im Tank
      zustand.fuelRemainingLaps = buf.readFloatLE(o + f.fuelRemainingLaps); // Reichweite in Runden
      const visual = buf.readUInt8(o + f.visualCompound);                   // Reifen-Mischung
      zustand.reifen      = REIFEN[visual] || { name: '—', farbe: '#888' };
      zustand.reifenAlter = buf.readUInt8(o + f.tyresAge);                  // Runden auf dem Reifen
      break;
    }

    case 10: { // ---- Car Damage: Reifen-Abnutzung in Prozent ----
      const f = L.damage;
      const o = start(f.size);
      if (buf.length < o + f.size) break;
      const wear = [0, 1, 2, 3].map(i => buf.readFloatLE(o + f.tyresWearStart + i * 4)); // % pro Reifen
      zustand.reifenAbnutzung = vierRaeder(wear);
      break;
    }

    case 1: { // ---- Session: Strecken- & Lufttemperatur ----
      const f = L.session;
      if (buf.length < HEADER_SIZE + 4) break;
      zustand.trackTemp = buf.readInt8(HEADER_SIZE + f.trackTemp); // °C Asphalt
      zustand.airTemp   = buf.readInt8(HEADER_SIZE + f.airTemp);   // °C Luft
      zustand.totalLaps = buf.readUInt8(HEADER_SIZE + f.totalLaps);
      break;
    }
  }

  return zustand;
}

module.exports = { parse, HEADER_SIZE, LAYOUTS };
