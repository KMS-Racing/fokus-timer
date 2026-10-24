package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Basis für alle Datenspeicher. Änderungen setzen "dirty"; der Mod speichert regelmäßig
 * im Hintergrund. Alle Methoden sind synchronized, weil Webseite (HTTP-Threads) und
 * Spiel (Server-Thread) gleichzeitig zugreifen.
 */
abstract class JsonStore {
    final Path path;
    private boolean dirty;
    private long version;

    protected JsonStore(Path path) {
        this.path = path;
    }

    /** Liest die Datei. Ist sie kaputt, wird sie als Sicherung umbenannt statt still überschrieben. */
    protected final JsonElement readOrBackup(Logger logger) {
        try {
            return JsonFiles.read(path);
        } catch (Exception e) {
            Path backup = path.resolveSibling(path.getFileName() + ".kaputt-" + System.currentTimeMillis());
            logger.error("[World Planner] {} konnte nicht gelesen werden ({}). Sicherung: {}", path, e.getMessage(), backup);
            try {
                Files.move(path, backup);
            } catch (Exception moveError) {
                logger.error("[World Planner] Sicherung fehlgeschlagen: {}", moveError.getMessage());
            }
            return null;
        }
    }

    /**
     * Hat die Datei eine ältere Schema-Version, wird sie einmalig als "datei.json.v1.bak" kopiert,
     * bevor sie im neuen Format überschrieben wird. So kann man bei Problemen zurück.
     */
    protected final void backupBeforeMigration(Logger logger, JsonObject json, int currentSchema) {
        int version = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 1;
        if (version >= currentSchema || !Files.exists(path)) return;
        Path backup = path.resolveSibling(path.getFileName() + ".v" + version + ".bak");
        try {
            if (!Files.exists(backup)) {
                Files.copy(path, backup);
                logger.info("[World Planner] {} wird auf Version {} umgestellt. Sicherung der alten Datei: {}", path.getFileName(), currentSchema, backup.getFileName());
            }
        } catch (Exception e) {
            logger.warn("[World Planner] Sicherung vor der Umstellung fehlgeschlagen: {}", e.getMessage());
        }
    }

    protected final synchronized void changed() {
        dirty = true;
        version++;
    }

    /** Zählt bei jeder Änderung hoch. So merkt z. B. BlueMap, dass neu gezeichnet werden muss. */
    final synchronized long version() {
        return version;
    }

    /** Gibt die zu speichernden Daten zurück, oder null wenn sich nichts geändert hat. */
    final synchronized JsonElement takeDirtySnapshot() {
        if (!dirty) return null;
        dirty = false;
        return toJson();
    }

    final synchronized void markDirty() {
        dirty = true;
    }

    /** Wird nur mit gehaltener Sperre aufgerufen. */
    protected abstract JsonElement toJson();
}
