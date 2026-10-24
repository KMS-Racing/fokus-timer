package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Eine eingeloggte API-Anfrage. Liest und prüft Felder einheitlich, damit nicht jeder Bereich
 * seine eigene (womöglich lückenhafte) Prüfung schreibt.
 */
final class ApiRequest {
    private static final int MAX_CHUNK_COORD = 1_875_000; // 30 Mio. Blöcke / 16

    final String method;
    final List<String> path; // ohne das führende "api"
    final Map<String, String> query;
    final UUID user;
    final boolean admin;
    final String client;
    final String token;
    private final InputStream bodyStream;
    private final int maxBody;
    private JsonObject body;

    ApiRequest(String method, List<String> path, Map<String, String> query, UUID user, boolean admin, String client,
               String token, InputStream bodyStream, int maxBody) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.user = user;
        this.admin = admin;
        this.client = client;
        this.token = token;
        this.bodyStream = bodyStream;
        this.maxBody = maxBody;
    }

    boolean get() {
        return method.equals("GET");
    }

    boolean post() {
        return method.equals("POST");
    }

    int size() {
        return path.size();
    }

    String seg(int i) {
        return i < path.size() ? path.get(i) : null;
    }

    /** Pfad passt genau? z. B. is("POST", "teams", "*", "invite") – "*" passt auf jedes Segment. */
    boolean is(String m, String... parts) {
        if (!method.equals(m) || path.size() != parts.length) return false;
        for (int i = 0; i < parts.length; i++) if (!parts[i].equals("*") && !parts[i].equals(path.get(i))) return false;
        return true;
    }

    JsonObject body() {
        if (body != null) return body;
        try (InputStream in = bodyStream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0, n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBody) throw new PlannerException(413, "Anfrage ist zu groß.");
                out.write(buf, 0, n);
            }
            String text = out.toString(StandardCharsets.UTF_8);
            if (text.isBlank()) return body = new JsonObject();
            try {
                return body = JsonParser.parseString(text).getAsJsonObject();
            } catch (Exception e) {
                throw PlannerException.badRequest("Ungültige Anfrage (kein JSON-Objekt).");
            }
        } catch (IOException e) {
            throw PlannerException.badRequest("Anfrage konnte nicht gelesen werden.");
        }
    }

    String str(String key) {
        String v = optStr(key);
        if (v == null) throw PlannerException.badRequest("Feld »" + key + "« fehlt.");
        return v;
    }

    String optStr(String key) {
        JsonObject b = body();
        if (!b.has(key) || b.get(key).isJsonNull()) return null;
        try {
            return b.get(key).getAsString();
        } catch (Exception e) {
            throw PlannerException.badRequest("Feld »" + key + "« ist ungültig.");
        }
    }

    long lng(String key) {
        JsonObject b = body();
        try {
            double v = b.get(key).getAsDouble();
            if (!Double.isFinite(v) || v != Math.floor(v)) throw new IllegalArgumentException();
            return (long) v;
        } catch (Exception e) {
            throw PlannerException.badRequest("»" + key + "« muss eine ganze Zahl sein.");
        }
    }

    boolean bool(String key, boolean fallback) {
        JsonObject b = body();
        if (!b.has(key) || b.get(key).isJsonNull()) return fallback;
        try {
            return b.get(key).getAsBoolean();
        } catch (Exception e) {
            throw PlannerException.badRequest("Feld »" + key + "« muss true oder false sein.");
        }
    }

    JsonElement raw(String key) {
        return body().get(key);
    }

    /** Liest eine flache Liste [x0, z0, x1, z1, ...] von Chunk-Koordinaten. */
    long[] chunks(String key) {
        try {
            JsonArray arr = body().getAsJsonArray(key);
            if (arr.size() % 2 != 0) throw new IllegalArgumentException();
            long[] out = new long[arr.size() / 2];
            for (int i = 0; i < out.length; i++) {
                int x = arr.get(i * 2).getAsInt(), z = arr.get(i * 2 + 1).getAsInt();
                if (Math.abs(x) > MAX_CHUNK_COORD || Math.abs(z) > MAX_CHUNK_COORD) throw new IllegalArgumentException();
                out[i] = Chunks.key(x, z);
            }
            return out;
        } catch (PlannerException pe) {
            throw pe;
        } catch (Exception e) {
            throw PlannerException.badRequest("Chunk-Liste »" + key + "« ist ungültig.");
        }
    }

    /** Spieler über den Namen im Feld (muss schon mal auf dem Server gewesen sein). */
    UUID player(String key, PlayerDirectory players) {
        String name = str(key);
        return players.findByName(name).orElseThrow(() -> PlannerException.notFound("Spieler »" + name + "« war noch nie auf dem Server."));
    }

    String requestId() {
        return optStr("requestId");
    }
}
