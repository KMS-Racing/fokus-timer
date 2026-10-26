"""Helfer für Tests, die den Testserver über die Konsole steuern und sein Log mitlesen (Carpet-Fake-Spieler).

Der Server muss mit LOG_CONFIG=tests/integration/log4j2-debug.xml laufen (siehe tests/README.md) und TESTSERVER_DIR gesetzt sein.
"""
import os
import time

TS = os.environ.get("TESTSERVER_DIR")
LOG = os.path.join(TS or ".", "server.log")
CMDS = os.path.join(TS or ".", "cmds.txt")


def lines():
    with open(LOG, encoding="utf-8", errors="replace") as f:
        return f.readlines()


def mark():
    return len(lines())


def since(m):
    return "".join(lines()[m:])


def wait_for(m, needle, timeout=8.0):
    end = time.time() + timeout
    while time.time() < end:
        if needle in since(m):
            return True
        time.sleep(0.3)
    return False


def console(command):
    with open(CMDS, "a", encoding="utf-8") as f:
        f.write(command + "\n")


def safe(name):
    """Sanftfall: Wer hoch oben ankommt, schwebt herunter, statt an Fallschaden zu sterben (Überlebensmodus)."""
    console(f"effect give {name} minecraft:slow_falling infinite 0 true")


def respawn(name):
    """Carpet entfernt Fake-Spieler, wenn sie sterben – also wieder holen. Neu gespawnte Spieler sind 3 Sekunden unverwundbar."""
    m = mark()
    console(f"player {name} spawn in survival")
    ok = wait_for(m, f"{name}[local] logged in", 10)
    safe(name)
    time.sleep(4)
    return ok


def teleport(name, x, y, z):
    """Teleport auf ganze Blockkoordinaten. Minecraft setzt sie auf die Blockmitte (-72 → -71.5). Gibt zurück, ob er passiert ist."""
    m = mark()
    console(f"tp {name} {x} {y} {z}")
    return wait_for(m, f"Teleported {name} to {x + 0.5:.6f}, {y:.6f}, {z + 0.5:.6f}", 5)


def platform(name, x, z, y=200, size=3):
    """Leere Steinplattform hoch in der Luft (dort ist nichts im Weg) – erst bauen, dann den Spieler daraufstellen,
    sonst fällt er schon, bevor die Plattform da ist. /fill geht nur in geladenen Chunks, deshalb kurz "forceload"."""
    m = mark()
    console(f"forceload add {x - size} {z - size} {x + size} {z + size}")
    wait_for(m, "force", 5)
    built = False
    for _ in range(10):  # das Laden kann einen Moment dauern
        m = mark()
        console(f"fill {x - size} {y + 1} {z - size} {x + size} {y + 3} {z + size} minecraft:air")  # Reste früherer Läufe
        console(f"fill {x - size} {y} {z - size} {x + size} {y} {z + size} minecraft:stone")
        # "Successfully filled …" oder "No blocks were filled" (stand schon da) – beides heißt: Plattform ist da
        if wait_for(m, "filled", 3) and wait_for(m, "filled", 0.5) and since(m).count("filled") >= 2:
            built = True
            break
        time.sleep(0.5)
    safe(name)
    moved = teleport(name, x, y + 1, z)
    console(f"forceload remove {x - size} {z - size} {x + size} {z + size}")
    return built and moved


def remove_platform(x, z, y=200, size=3):
    console(f"fill {x - size} {y} {z - size} {x + size} {y + 3} {z + size} minecraft:air")
