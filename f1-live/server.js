'use strict';

// =====================================================================
//  Brücken-Programm: F1 25  ->  Browser
// ---------------------------------------------------------------------
//  1. Hört per UDP auf die Telemetrie von F1 25 (Port 20777).
//  2. Liest die Werte deines Autos heraus (siehe f1parser.js).
//  3. Liefert das Live-Dashboard aus und schiebt die Daten ~10x pro
//     Sekunde per "Server-Sent Events" (SSE) in den Browser.
//
//  Start:            node server.js
//  Mit Debug-Infos:  node server.js --debug
// =====================================================================

const dgram = require('dgram');
const http  = require('http');
const fs    = require('fs');
const path  = require('path');
const { parse } = require('./f1parser');

const UDP_PORT = 20777;   // Muss zum Port in den F1-25-Einstellungen passen
const WEB_PORT = 3000;    // Hier öffnest du die App im Browser
const DEBUG    = process.argv.includes('--debug');

const zustand = {};          // aktueller Live-Zustand deines Autos
let letzteRundenNr = null;   // um eine frisch beendete Runde zu erkennen
const fertigeRunden = [];    // abgeschlossene Rundenzeiten (ms) dieser Session

// Alle verbundenen Browser-Fenster (SSE-Verbindungen)
const clients = new Set();

// --------------------------------------------------------------
// UDP: Telemetrie vom Spiel empfangen
// --------------------------------------------------------------
const udp = dgram.createSocket('udp4');

udp.on('message', (msg) => {
  try {
    parse(msg, zustand);

    // Neue, gerade beendete Runde erkennen (Rundennummer springt hoch)
    if (typeof zustand.lapNum === 'number') {
      if (letzteRundenNr === null) {
        letzteRundenNr = zustand.lapNum;
      } else if (zustand.lapNum > letzteRundenNr) {
        // Die soeben beendete Runde steckt in "lastLapMs"
        if (zustand.lastLapMs > 0) {
          fertigeRunden.push(zustand.lastLapMs);
          if (DEBUG) console.log('🏁 Runde fertig:', (zustand.lastLapMs / 1000).toFixed(3), 's');
        }
        letzteRundenNr = zustand.lapNum;
      }
    }
  } catch (e) {
    if (DEBUG) console.error('Parse-Fehler:', e.message);
  }
});

udp.on('listening', () => {
  console.log(`✅ Höre auf F1-Telemetrie an UDP-Port ${UDP_PORT}`);
});

udp.on('error', (e) => {
  console.error('UDP-Fehler:', e.message);
});

udp.bind(UDP_PORT);

// --------------------------------------------------------------
// Daten ~10x pro Sekunde an alle Browser schicken (schont die Leitung)
// --------------------------------------------------------------
setInterval(() => {
  if (clients.size === 0) return;
  const daten = JSON.stringify({ ...zustand, fertigeRunden });
  for (const res of clients) {
    res.write(`data: ${daten}\n\n`);
  }
}, 100);

// Debug-Übersicht alle 2 Sekunden – zeigt, ob die Werte plausibel sind
if (DEBUG) {
  setInterval(() => {
    if (!zustand.packetFormat) {
      console.log('… noch keine Daten. Läuft F1 25 und ist die Telemetrie an?');
      return;
    }
    const r = zustand.reifenAbnutzung || {};
    console.log(
      `F1 ${zustand.gameYear ?? '?'} (Format ${zustand.packetFormat}) | Reifen ${zustand.reifen ? zustand.reifen.name : '?'} ` +
      `(Alter ${zustand.reifenAlter ?? '?'}) | Abnutzung FL ${fmt(r.FL)} FR ${fmt(r.FR)} RL ${fmt(r.RL)} RR ${fmt(r.RR)} | ` +
      `Sprit ${zustand.fuelInTank != null ? zustand.fuelInTank.toFixed(1) + 'kg' : '?'} | ` +
      `letzte Runde ${zustand.lastLapMs ? (zustand.lastLapMs / 1000).toFixed(3) + 's' : '?'}`
    );
  }, 2000);
}
function fmt(v) { return v == null ? '?' : v.toFixed(1) + '%'; }

// --------------------------------------------------------------
// HTTP: Dashboard ausliefern + Live-Stream (SSE)
// --------------------------------------------------------------
const server = http.createServer((req, res) => {
  if (req.url === '/stream') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    });
    res.write('\n');
    clients.add(res);
    // Sofort den aktuellen Stand schicken, damit die Seite nicht leer ist
    res.write(`data: ${JSON.stringify({ ...zustand, fertigeRunden })}\n\n`);
    req.on('close', () => clients.delete(res));
    return;
  }

  // Alles andere: das Dashboard (index.html) ausliefern
  const datei = path.join(__dirname, 'public', 'index.html');
  fs.readFile(datei, (err, inhalt) => {
    if (err) { res.writeHead(500); res.end('Dashboard nicht gefunden'); return; }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(inhalt);
  });
});

server.listen(WEB_PORT, () => {
  console.log(`✅ Dashboard offen unter:  http://localhost:${WEB_PORT}`);
  if (DEBUG) console.log('🔧 Debug-Modus an – zeigt empfangene Werte im Terminal.');
  console.log('   (Zum Beenden: Strg + C)');
});
