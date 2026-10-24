"""Gemeinsame Helfer für die API-Tests gegen einen laufenden Testserver (siehe tests/README.md)."""
import json
import os
import random
import string
import urllib.error
import urllib.request

BASE = os.environ.get("PLANNER_URL", "http://127.0.0.1:8765/")
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(path, body=None, token=None, raw=None, method=None):
    """Schickt eine Anfrage an die API und gibt (HTTP-Status, JSON-Antwort) zurück."""
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method or ("POST" if data is not None else "GET"))
    if token:
        req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", "application/json")
    try:
        with _opener.open(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        text = e.read()
        try:
            return e.code, json.loads(text or b"{}")
        except ValueError:
            return e.code, {"raw": text.decode("utf-8", "replace")}


def login(code):
    status, r = call("api/login", {"code": code})
    if status != 200:
        raise SystemExit(f"Login mit Code {code} fehlgeschlagen: {status} {r}")
    return r["token"], r["name"]


def suffix(n=4):
    """Zufälliger Namenszusatz, damit Tests nicht mit Daten früherer Läufe kollidieren."""
    return "".join(random.choice(string.ascii_uppercase) for _ in range(n))


def free_chunks(token, count, dimension="minecraft:overworld", taken=None):
    """Findet `count` nebeneinanderliegende (in x-Richtung) entdeckte und freie Chunks."""
    _, disc = call("api/discovered?dimension=" + dimension, token=token)
    flat = disc["chunks"]
    discovered = {(flat[i], flat[i + 1]) for i in range(0, len(flat), 2)}
    _, lands = call("api/lands", token=token)
    occupied = set(taken or ())
    for land in lands["lands"]:
        if land["dimension"] != dimension:
            continue
        c = land["chunks"]
        occupied |= {(c[i], c[i + 1]) for i in range(0, len(c), 2)}
    for (x, z) in sorted(discovered):
        row = [(x + i, z) for i in range(count)]
        if all(p in discovered and p not in occupied for p in row):
            return row
    raise SystemExit(f"Keine {count} freien entdeckten Chunks nebeneinander gefunden.")


def flat(chunks):
    out = []
    for x, z in chunks:
        out += [x, z]
    return out


class Checker:
    def __init__(self, label):
        self.label = label
        self.results = []

    def __call__(self, name, cond, info=""):
        self.results.append(bool(cond))
        print(("OK   " if cond else "FAIL ") + name + ("" if cond else "  -> " + str(info)))

    def done(self):
        ok = sum(self.results)
        failed = len(self.results) - ok
        print(f"\n{self.label}: {ok} bestanden, {failed} fehlgeschlagen, 0 übersprungen")
        return 1 if failed else 0
