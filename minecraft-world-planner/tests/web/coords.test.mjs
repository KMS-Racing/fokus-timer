// Unit-Tests für die Koordinaten-Werkzeuge der Webseite. Aufruf: node --test tests/web/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  blockToChunk, blockToRegion, chunkBounds, chunkToRegion, convertForPortal, direction, distance2d, fmtDistance,
  netherToOverworld, overworldToNether, parseCoords, tpCommand,
} from '../../web/js/coords.js';

test('Block → Chunk rundet auch bei negativen Zahlen ab', () => {
  assert.equal(blockToChunk(0), 0);
  assert.equal(blockToChunk(15), 0);
  assert.equal(blockToChunk(16), 1);
  assert.equal(blockToChunk(-1), -1);
  assert.equal(blockToChunk(-16), -1);
  assert.equal(blockToChunk(-17), -2);
});

test('Chunk → Region (32 Chunks)', () => {
  assert.equal(chunkToRegion(31), 0);
  assert.equal(chunkToRegion(32), 1);
  assert.equal(chunkToRegion(-1), -1);
  assert.equal(blockToRegion(-513), -2);
  assert.deepEqual(chunkBounds(-1, 2), { minX: -16, maxX: -1, minZ: 32, maxZ: 47 });
});

test('Nether-Umrechnung', () => {
  assert.deepEqual(overworldToNether(-100, 250), { x: -13, z: 31 });
  assert.deepEqual(overworldToNether(800, -800), { x: 100, z: -100 });
  assert.deepEqual(netherToOverworld(-13, 31), { x: -104, z: 248 });
  assert.deepEqual(convertForPortal('minecraft:overworld', 80, 8), { dim: 'minecraft:the_nether', x: 10, z: 1 });
  assert.equal(convertForPortal('minecraft:the_end', 1, 1), null);
});

test('Entfernung und Himmelsrichtung (−Z = Norden)', () => {
  assert.equal(distance2d({ x: 0, z: 0 }, { x: 3, z: 4 }), 5);
  assert.equal(direction({ x: 0, z: 0 }, { x: 0, z: -10 }), 'Norden');
  assert.equal(direction({ x: 0, z: 0 }, { x: 10, z: 0 }), 'Osten');
  assert.equal(direction({ x: 0, z: 0 }, { x: -10, z: 10 }), 'Südwesten');
  assert.equal(direction({ x: 5, z: 5 }, { x: 5, z: 5 }), 'hier');
  assert.equal(fmtDistance(950), '950 Blöcke');
  assert.equal(fmtDistance(12345), '12,3 km');
});

test('Koordinaten aus verschiedenen Schreibweisen', () => {
  assert.deepEqual(parseCoords('100 64 -200'), { x: 100, y: 64, z: -200 });
  assert.deepEqual(parseCoords('100, -200'), { x: 100, z: -200 });
  assert.deepEqual(parseCoords('X: 100 Y: 64 Z: -200'), { x: 100, y: 64, z: -200 });
  assert.deepEqual(parseCoords('x=12, z=-5'), { x: 12, z: -5 });
  assert.deepEqual(parseCoords('/tp @s 100 64 -200'), { x: 100, y: 64, z: -200 });
  assert.deepEqual(parseCoords('XYZ: 100.5 / 64.0 / -200.3'), { x: 100, y: 64, z: -201 });
  assert.deepEqual(parseCoords('Block: -17 70 33'), { x: -17, y: 70, z: 33 });
});

test('Unsinn wird abgelehnt', () => {
  assert.equal(parseCoords(''), null);
  assert.equal(parseCoords('Basis'), null);
  assert.equal(parseCoords('100'), null);
  assert.equal(parseCoords('1 2 3 4'), null);
  assert.equal(parseCoords('100 abc 200'), null);
  assert.equal(parseCoords('40000000 0'), null, 'außerhalb der Welt');
  assert.equal(parseCoords(null), null);
});

test('Teleport-Befehl je Dimension', () => {
  assert.equal(tpCommand('minecraft:overworld', 1.7, 64, -2.2), '/tp @s 1 64 -3');
  assert.equal(tpCommand('minecraft:the_nether', 10, 70, 20), '/execute in minecraft:the_nether run tp @s 10 70 20');
  assert.equal(tpCommand('minecraft:overworld', 1, undefined, 2), '/tp @s 1 64 2');
});
