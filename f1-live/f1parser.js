'use strict';

// =====================================================================
//  F1 24 / F1 25 UDP-Telemetrie – Parser
// ---------------------------------------------------------------------
//  F1 25 verschickt laufend kleine Daten-Pakete ("Packets") per UDP.
//  Jedes Paket hat einen 29 Byte langen Kopf (Header) und danach die
//  eigentlichen Daten – meistens für ALLE 22 Autos hintereinander.
//
//  Wir lesen nur die Daten DEINES Autos. Welches das ist, steht im
//  Header (playerCarIndex). Damit springen wir an die richtige Stelle.
//
//  Die Byte-Positionen (Offsets) stammen aus der offiziellen
//  F1-24-Spezifikation; F1 25 ist nahezu identisch. Falls ein Wert mal
//  unsinnig aussieht, muss evtl. eine der Größen unten minimal angepasst
//  werden – dabei hilft der Debug-Modus (siehe server.js).
// =====================================================================

const HEADER_SIZE = 29;

// Größe eines einzelnen Auto-Blocks je Paket-Art (in Bytes):
const LAP_DATA_SIZE      = 57;  // Lap-Data-Paket   (ID 2)
const CAR_TELEMETRY_SIZE = 60;  // Telemetrie-Paket (ID 6)
const CAR_STATUS_SIZE    = 55;  // Status-Paket     (ID 7)
const CAR_DAMAGE_SIZE    = 42;  // Schaden-Paket    (ID 10)

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
    packetFormat:   buf.readUInt16LE(0),  // z.B. 2024 oder 2025
    packetId:       buf.readUInt8(6),     // welche Art Paket
    playerCarIndex: buf.readUInt8(27),    // welches Auto ist deins
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
  const idx = h.playerCarIndex;
  zustand.packetFormat = h.packetFormat;

  // Startposition der Daten deines Autos im jeweiligen Paket
  const start = (stride) => HEADER_SIZE + idx * stride;

  switch (h.packetId) {

    case 2: { // ---- Lap Data: Rundenzeiten & Position ----
      const o = start(LAP_DATA_SIZE);
      if (buf.length < o + LAP_DATA_SIZE) break;
      zustand.lastLapMs    = buf.readUInt32LE(o + 0);  // letzte fertige Runde (ms)
      zustand.currentLapMs = buf.readUInt32LE(o + 4);  // laufende Runde (ms)
      zustand.position     = buf.readUInt8(o + 32);
      zustand.lapNum       = buf.readUInt8(o + 33);
      break;
    }

    case 6: { // ---- Car Telemetry: Tempo & Reifen-Temperaturen ----
      const o = start(CAR_TELEMETRY_SIZE);
      if (buf.length < o + CAR_TELEMETRY_SIZE) break;
      zustand.speed = buf.readUInt16LE(o + 0); // km/h
      const oberflaeche = [0, 1, 2, 3].map(i => buf.readUInt8(o + 30 + i));
      zustand.reifenTemp = vierRaeder(oberflaeche); // °C an der Reifenoberfläche
      break;
    }

    case 7: { // ---- Car Status: Reifen-Mischung, Alter, Sprit ----
      const o = start(CAR_STATUS_SIZE);
      if (buf.length < o + CAR_STATUS_SIZE) break;
      zustand.fuelInTank        = buf.readFloatLE(o + 5);   // kg Sprit im Tank
      zustand.fuelRemainingLaps = buf.readFloatLE(o + 13);  // Reichweite in Runden
      const visual = buf.readUInt8(o + 26);                 // Reifen-Mischung
      zustand.reifen      = REIFEN[visual] || { name: '—', farbe: '#888' };
      zustand.reifenAlter = buf.readUInt8(o + 27);          // Runden auf dem Reifen
      break;
    }

    case 10: { // ---- Car Damage: Reifen-Abnutzung in Prozent ----
      const o = start(CAR_DAMAGE_SIZE);
      if (buf.length < o + CAR_DAMAGE_SIZE) break;
      const wear = [0, 1, 2, 3].map(i => buf.readFloatLE(o + i * 4)); // % pro Reifen
      zustand.reifenAbnutzung = vierRaeder(wear);
      break;
    }

    case 1: { // ---- Session: Strecken- & Lufttemperatur ----
      if (buf.length < HEADER_SIZE + 4) break;
      zustand.trackTemp = buf.readInt8(HEADER_SIZE + 1); // °C Asphalt
      zustand.airTemp   = buf.readInt8(HEADER_SIZE + 2); // °C Luft
      zustand.totalLaps = buf.readUInt8(HEADER_SIZE + 3);
      break;
    }
  }

  return zustand;
}

module.exports = { parse, HEADER_SIZE };
