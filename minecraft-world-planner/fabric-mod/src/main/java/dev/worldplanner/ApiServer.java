package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kleiner HTTP-Server: liefert die Webseite aus und beantwortet die API unter /api/...
 * Läuft in eigenen Threads und fasst keine Minecraft-Objekte direkt an – nur die
 * thread-sicheren Speicher und die Momentaufnahmen, die der Server-Thread erstellt.
 */
final class ApiServer {
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int MAX_CHUNK_COORD = 1_875_000; // 30 Mio. Blöcke / 16
    private static final Map<String, String> STATIC_FILES = Map.of(
            "index.html", "text/html; charset=utf-8",
            "app.js", "text/javascript; charset=utf-8",
            "style.css", "text/css; charset=utf-8"
    );
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-src http: https:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final Logger logger;
    private final PlannerServices s;
    private final Map<String, byte[]> staticContent = new HashMap<>();
    private HttpServer http;
    private ExecutorService executor;

    ApiServer(Logger logger, PlannerServices services) {
        this.logger = logger;
        this.s = services;
        for (String name : STATIC_FILES.keySet()) {
            try (InputStream in = ApiServer.class.getResourceAsStream("/world_planner_web/" + name)) {
                if (in != null) staticContent.put(name, in.readAllBytes());
            } catch (IOException e) {
                logger.warn("[World Planner] Webdatei {} fehlt: {}", name, e.getMessage());
            }
        }
    }

    void start() throws IOException {
        PlannerConfig c = s.config();
        http = HttpServer.create(new InetSocketAddress(c.bind(), c.port()), 0);
        // Daemon-Threads: Sie halten den Server beim Herunterfahren nicht am Leben (Bug im Original).
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "world-planner-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(executor);
        http.createContext("/", this::handle);
        http.start();
        logger.info("[World Planner] Webseite und API laufen auf http://{}:{}/", c.bind(), c.port());
        if (!"127.0.0.1".equals(c.bind()) && !"localhost".equals(c.bind())) {
            logger.warn("[World Planner] Die API ist nicht nur lokal erreichbar ({}). Übers Internet bitte nur mit HTTPS (siehe README)!", c.bind());
        }
    }

    void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ------------------------------------------------------------------ Verteilen

    private void handle(HttpExchange ex) {
        try (ex) {
            try {
                String method = ex.getRequestMethod().toUpperCase();
                String path = ex.getRequestURI().getPath();
                List<String> seg = new ArrayList<>();
                for (String part : path.split("/")) if (!part.isEmpty()) seg.add(part);

                if (seg.isEmpty() || !seg.get(0).equals("api")) {
                    serveStatic(ex, method, seg);
                    return;
                }
                addApiHeaders(ex);
                if (method.equals("OPTIONS")) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                JsonElement result = route(ex, method, seg.subList(1, seg.size()));
                send(ex, 200, result);
            } catch (PlannerException pe) {
                send(ex, pe.status, error(pe.getMessage()));
            } catch (Exception e) {
                logger.warn("[World Planner] Anfrage fehlgeschlagen: {}", e.toString());
                send(ex, 500, error("Interner Fehler auf dem Server."));
            }
        } catch (IOException ignored) {
            // Browser hat die Verbindung geschlossen
        }
    }

    private JsonElement route(HttpExchange ex, String method, List<String> p) throws IOException {
        String first = p.isEmpty() ? "" : p.get(0);
        boolean get = method.equals("GET"), post = method.equals("POST");

        // --- ohne Login
        if (get && first.equals("info") && p.size() == 1) return info();
        if (post && first.equals("login") && p.size() == 1) return login(body(ex));

        // --- ab hier nur mit Login
        String token = bearer(ex);
        UUID me = s.sessions().authenticate(token).orElseThrow(() ->
                new PlannerException(401, "Bitte einloggen: Tippe im Spiel /planer login."));
        boolean admin = s.players().isAdmin(me);

        if (post && first.equals("logout") && p.size() == 1) {
            s.sessions().logout(token);
            return ok();
        }
        if (get && p.size() == 1) {
            switch (first) {
                case "me": return me(me, admin);
                case "world": return world();
                case "players": return players();
                case "discovered": return discovered(query(ex).getOrDefault("dimension", "minecraft:overworld"));
                case "lands": return lands(me, admin);
                case "markers": return markers(me, admin);
                case "known-players": return knownPlayers();
                default: break;
            }
        }
        if (post && first.equals("lands")) return landAction(me, admin, p, body(ex));
        if (post && first.equals("markers")) return markerAction(me, admin, p, body(ex));
        throw PlannerException.notFound("Unbekannte Adresse.");
    }

    // ------------------------------------------------------------------ Endpunkte

    private JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("app", "Minecraft World Planner");
        o.addProperty("loginHint", "Tippe im Spiel /planer login und gib den Code hier ein.");
        return o;
    }

    private JsonObject login(JsonObject body) {
        SessionStore.LoginResult result = s.sessions().redeem(string(body, "code"), s.config().sessionDays());
        JsonObject o = new JsonObject();
        o.addProperty("token", result.token());
        o.addProperty("uuid", result.uuid().toString());
        o.addProperty("name", s.players().name(result.uuid()));
        return o;
    }

    private JsonObject me(UUID me, boolean admin) {
        PlannerConfig c = s.config();
        JsonObject o = new JsonObject();
        o.addProperty("uuid", me.toString());
        o.addProperty("name", s.players().name(me));
        o.addProperty("admin", admin);
        o.addProperty("usedChunks", s.lands().ownedChunkCount(me));
        o.addProperty("maxChunks", c.maxChunksPerPlayer());
        o.addProperty("maxMarkers", c.maxMarkersPerPlayer());
        o.addProperty("claimOnlyDiscovered", c.claimOnlyDiscovered());
        JsonObject maps = new JsonObject();
        maps.addProperty("liveUrl", c.liveMapUrl());
        maps.addProperty("seedUrl", c.seedMapUrl());
        // Eingetragene Karten-IDs aus der Config haben Vorrang, sonst die von BlueMap erkannten.
        JsonObject ids = new JsonObject();
        s.detectedBlueMapIds().get().forEach(ids::addProperty);
        c.blueMapIds().forEach(ids::addProperty);
        maps.add("mapIds", ids);
        maps.addProperty("blueMapDetected", !s.detectedBlueMapIds().get().isEmpty());
        o.add("maps", maps);
        return o;
    }

    private JsonObject world() {
        JsonObject snap = s.worldSnapshot().get().deepCopy();
        if (!s.config().showSeed()) snap.remove("seed");
        return snap;
    }

    private JsonObject players() {
        if (!s.config().showOnlinePlayers()) {
            JsonObject o = new JsonObject();
            o.add("players", new JsonArray());
            o.addProperty("hidden", true);
            return o;
        }
        return s.playersSnapshot().get();
    }

    private JsonObject discovered(String dimension) {
        int[] flat = s.discovery().export(dimension);
        JsonArray arr = new JsonArray(flat.length);
        for (int v : flat) arr.add(v);
        JsonObject o = new JsonObject();
        o.addProperty("dimension", dimension);
        o.add("chunks", arr);
        return o;
    }

    private JsonObject lands(UUID me, boolean admin) {
        JsonObject o = new JsonObject();
        o.addProperty("version", s.lands().version());
        o.add("lands", s.lands().toApiJson(me, admin));
        return o;
    }

    private JsonObject markers(UUID me, boolean admin) {
        JsonObject o = new JsonObject();
        o.add("markers", s.markers().toApiJson(me, admin));
        return o;
    }

    private JsonObject knownPlayers() {
        JsonArray arr = new JsonArray();
        s.players().names().forEach(arr::add);
        JsonObject o = new JsonObject();
        o.add("players", arr);
        return o;
    }

    /** POST /api/lands  oder  POST /api/lands/{id}/{aktion} */
    private JsonElement landAction(UUID me, boolean admin, List<String> p, JsonObject body) {
        LandStore lands = s.lands();
        if (p.size() == 1) {
            String id = lands.create(me, admin, string(body, "name"), string(body, "dimension"), chunks(body, "chunks"));
            JsonObject o = ok();
            o.addProperty("id", id);
            return o;
        }
        if (p.size() != 3) throw PlannerException.notFound("Unbekannte Adresse.");
        String id = p.get(1);
        JsonObject o = ok();
        switch (p.get(2)) {
            case "chunks" -> {
                if (body.has("add")) lands.addChunks(me, admin, id, chunks(body, "add"));
                if (body.has("remove")) o.addProperty("deleted", lands.removeChunks(me, admin, id, chunks(body, "remove")));
            }
            case "edit" -> lands.edit(me, admin, id, optString(body, "name"), optString(body, "color"), optString(body, "description"));
            case "invite" -> lands.invite(me, id, player(body));
            case "uninvite" -> lands.uninvite(me, id, player(body));
            case "accept" -> lands.accept(me, admin, id);
            case "decline" -> lands.decline(me, id);
            case "leave" -> o.addProperty("deleted", lands.leave(me, id));
            case "delete" -> {
                requireAdmin(admin);
                lands.adminDelete(id);
            }
            case "remove-owner" -> {
                requireAdmin(admin);
                o.addProperty("deleted", lands.adminRemoveOwner(id, player(body)));
            }
            default -> throw PlannerException.notFound("Unbekannte Aktion.");
        }
        return o;
    }

    /** POST /api/markers  oder  POST /api/markers/{id}/edit|delete */
    private JsonElement markerAction(UUID me, boolean admin, List<String> p, JsonObject body) {
        if (p.size() == 1) {
            JsonObject o = ok();
            o.addProperty("id", s.markers().create(me, admin, body));
            return o;
        }
        if (p.size() == 3 && p.get(2).equals("edit")) {
            s.markers().update(me, admin, p.get(1), body);
            return ok();
        }
        if (p.size() == 3 && p.get(2).equals("delete")) {
            s.markers().delete(me, admin, p.get(1));
            return ok();
        }
        throw PlannerException.notFound("Unbekannte Adresse.");
    }

    // ------------------------------------------------------------------ Hilfsmethoden

    private void serveStatic(HttpExchange ex, String method, List<String> seg) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        if (!method.equals("GET")) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String name = seg.isEmpty() ? "index.html" : String.join("/", seg);
        byte[] content = staticContent.get(name);
        if (content == null) {
            byte[] msg = "Nicht gefunden".getBytes(StandardCharsets.UTF_8);
            h.set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            ex.getResponseBody().write(msg);
            return;
        }
        h.set("Content-Type", STATIC_FILES.get(name));
        h.set("Cache-Control", "no-cache");
        h.set("Content-Security-Policy", CSP);
        h.set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(200, content.length);
        ex.getResponseBody().write(content);
    }

    private void addApiHeaders(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        // CORS nur, wenn die Webseite absichtlich auf einer anderen Adresse liegt.
        String allowed = s.config().allowedOrigin();
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (!allowed.isBlank() && allowed.equals(origin)) {
            h.set("Access-Control-Allow-Origin", allowed);
            h.set("Vary", "Origin");
            h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.set("Access-Control-Max-Age", "600");
        }
    }

    private static String bearer(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0, n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BODY_BYTES) throw new PlannerException(413, "Anfrage ist zu groß.");
                out.write(buf, 0, n);
            }
            String text = out.toString(StandardCharsets.UTF_8);
            if (text.isBlank()) return new JsonObject();
            try {
                return JsonParser.parseString(text).getAsJsonObject();
            } catch (Exception e) {
                throw PlannerException.badRequest("Ungültige Anfrage (kein JSON-Objekt).");
            }
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                q.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private UUID player(JsonObject body) {
        String name = string(body, "player");
        return s.players().findByName(name).orElseThrow(() ->
                PlannerException.notFound("Spieler »" + name + "« war noch nie auf dem Server."));
    }

    private static String string(JsonObject body, String key) {
        try {
            return body.get(key).getAsString();
        } catch (Exception e) {
            throw PlannerException.badRequest("Feld »" + key + "« fehlt.");
        }
    }

    private static String optString(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null;
    }

    /** Liest eine flache Liste [x0, z0, x1, z1, ...] von Chunk-Koordinaten. */
    private static long[] chunks(JsonObject body, String key) {
        try {
            JsonArray arr = body.getAsJsonArray(key);
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

    private static void requireAdmin(boolean admin) {
        if (!admin) throw PlannerException.forbidden("Nur Admins dürfen das.");
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    private static void send(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] bytes = JsonFiles.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
