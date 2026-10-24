#!/bin/bash
# Steuert einen lokalen Minecraft-Testserver für die Integrationstests.
#   TESTSERVER_DIR=/pfad/zum/server JAVA=/pfad/zu/java ./testserver.sh start|stop|cmd <befehl>|code <spieler>|status
#
# Der Server braucht: Fabric 26.3, Fabric API, diese Mod, optional BlueMap und für Fake-Spieler die Carpet-Mod.
# Befehle werden über die Datei cmds.txt an die Server-Konsole geschickt.
TS=${TESTSERVER_DIR:?TESTSERVER_DIR setzen}
JAVA=${JAVA:-java}

# Java-Prozess des Testservers über Prozessname + Arbeitsordner finden
# (pgrep -f würde auch die Shell finden, deren Befehlszeile diesen Text enthält).
javapid() {
  for p in $(pgrep -x java); do
    [ "$(readlink /proc/$p/cwd)" = "$(cd "$TS" && pwd -P)" ] && echo "$p"
  done
}

case "$1" in
  start)
    if [ -n "$(javapid)" ]; then echo "läuft schon"; exit 0; fi
    pkill -f "tail -n +1 -f cmds.txt"
    cd "$TS" || exit 1
    mkdir -p old-logs
    : > cmds.txt
    [ -f server.log ] && mv server.log "old-logs/server-$(date +%s).log"
    (tail -n +1 -f cmds.txt | "$JAVA" -Xmx2G -jar fabric-server.jar nogui > server.log 2>&1 &)
    for _ in $(seq 1 240); do
      grep -qE "Done \(|Exception in server|crash-reports" server.log 2>/dev/null && break
      sleep 1
    done
    grep -E "Done \(|World Planner|ERROR" server.log | cut -c1-170
    ;;
  stop)
    P=$(javapid)
    if [ -z "$P" ]; then echo "läuft nicht"; exit 0; fi
    echo stop >> "$TS/cmds.txt"
    for _ in $(seq 1 120); do kill -0 "$P" 2>/dev/null || break; sleep 0.5; done
    pkill -f "tail -n +1 -f cmds.txt"
    if kill -0 "$P" 2>/dev/null; then echo "HÄNGT NOCH (PID $P)"; exit 1; else echo "gestoppt"; fi
    ;;
  cmd)
    shift
    echo "$*" >> "$TS/cmds.txt"
    ;;
  code)
    # Holt einen Login-Code für einen (Fake-)Spieler. "execute as" schickt die Antwort an die Konsole.
    n=$(wc -l < "$TS/server.log")
    echo "execute as $2 run planer login" >> "$TS/cmds.txt"
    for _ in $(seq 1 30); do
      sleep 0.5
      c=$(tail -n +$((n + 1)) "$TS/server.log" | grep -oE "Login-Code: [A-Z0-9-]+" | tail -1 | awk '{print $2}')
      if [ -n "$c" ]; then echo "$c"; exit 0; fi
    done
    echo "KEIN-CODE" >&2
    exit 1
    ;;
  status)
    if [ -n "$(javapid)" ]; then echo "läuft"; else echo "läuft nicht"; fi
    ;;
  *)
    echo "Aufruf: $0 start|stop|cmd <befehl>|code <spieler>|status"
    exit 2
    ;;
esac
