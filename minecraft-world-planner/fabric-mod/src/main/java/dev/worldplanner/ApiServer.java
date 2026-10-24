package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Kleiner HTTP-Server: liefert die Webseite aus und beantwortet die API unter /api/...
 * Läuft in eigenen Threads und fasst keine Minecraft-Objekte direkt an – nur die
 * thread-sicheren Speicher und die Momentaufnahmen, die der Server-Thread erstellt.
 *
 * Ablauf einer Anfrage: Login prüfen (Bearer-Token) -> Bereich wählen (Routes) -> der Bereich prüft die Rechte.
 * Fehler kommen immer als {"error": "deutsche Meldung"} mit passendem HTTP-Code.
 */
final class ApiServer {
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;
    /** Webdateien: nur Kleinbuchstaben, Ziffern, _ und - in Pfaden – so sind "../"-Tricks unmöglich. */
    private static final Pattern STATIC_PATH = Pattern.compile("[a-z0-9_-]+(/[a-z0-9_-]+)*\\.(html|js|css|svg)");
    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml"
    );
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}]");
    private static final int BLUEMAP_DEFAULT_PORT = 8100;

    private final Logger logger;
    private final PlannerServices s;
    private final Map<String, Routes> routes = new HashMap<>();
    private final Map<String, Optional<byte[]>> staticContent = new ConcurrentHashMap<>();
    private HttpServer http;
    private ExecutorService executor;

    ApiServer(Logger logger, PlannerServices services) {
        this.logger = logger;
        this.s = services;
        AccountRoutes account = new AccountRoutes(services);
        for (String key : new String[]{"me", "meta", "people", "prefs", "activity", "export", "import"}) routes.put(key, account);
        routes.put("lands", new LandRoutes(services));
        routes.put("markers", new MarkerRoutes(services));
        routes.put("teams", new TeamRoutes(services));
        routes.put("projects", new ProjectRoutes(services));
        routes.put("trades", new TradeRoutes(services));
    }

    void start() throws IOException {
        PlannerConfig c = s.config();
        http = HttpServer.create(new InetSocketAddress(c.bind(), c.port()), 0);
        // Daemon-Threads: Sie halten den Server beim Herunterfahren nicht am Leben.
        executor = Executors.newFixedThreadPool(8, r -> {
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
            } catch (ClassCastException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
                // So reagiert Gson auf falsche Datentypen im JSON (z. B. eine Liste statt eines Textes)
                logger.info("[World Planner] Ungültige Anfrage an {}: {}", ex.getRequestURI().getPath(), e.toString());
                send(ex, 400, error("Ungültige Daten in der Anfrage."));
            } catch (Exception e) {
                // Keine Details nach außen – nur ins Server-Log
                logger.warn("[World Planner] Anfrage fehlgeschlagen: {}", e.toString());
                send(ex, 500, error("Interner Fehler auf dem Server."));
            }
        } catch (IOException ignored) {
            // Browser hat die Verbindung geschlossen
        }
    }

    private JsonElement route(HttpExchange ex, String method, List<String> p) {
        String first = p.isEmpty() ? "" : p.get(0);
        boolean get = method.equals("GET"), post = method.equals("POST");
        String client = clientAddress(ex);

        // --- ohne Login
        if (get && first.equals("info") && p.size() == 1) return info();
        if (post && first.equals("login") && p.size() == 1) {
            ApiRequest r = new ApiRequest(method, p, Map.of(), null, false, client, null, ex.getRequestBody(), MAX_BODY_BYTES);
            return login(r);
        }

        // --- ab hier nur mit Login
        String token = bearer(ex);
        UUID me = s.sessions().authenticate(token).orElseThrow(() ->
                new PlannerException(401, "Bitte einloggen: Tippe im Spiel /planer login."));
        boolean admin = s.players().isAdmin(me);
        int maxBody = first.equals("import") ? MAX_IMPORT_BYTES : MAX_BODY_BYTES;
        ApiRequest r = new ApiRequest(method, p, query(ex), me, admin, client, token, ex.getRequestBody(), maxBody);

        if (r.is("POST", "logout")) {
            s.sessions().logout(token);
            return Routes.ok();
        }
        if (get && p.size() == 1) {
            switch (first) {
                case "world": return world();
                case "players": return players();
                case "discovered": return discovered(r.query.getOrDefault("dimension", "minecraft:overworld"), r.query.get("since"));
                case "known-players": return knownPlayers();
                case "bluemap-status": return s.blueMapStatus().get();
                default: break;
            }
        }
        Routes area = routes.get(first);
        if (area == null) throw PlannerException.notFound("Unbekannte Adresse.");
        return area.handle(r);
    }

    // ------------------------------------------------------------------ Einfache Endpunkte

    private JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("app", "Minecraft World Planner");
        o.addProperty("loginHint", "Tippe im Spiel /planer login und gib den Code hier ein.");
        return o;
    }

    private JsonObject login(ApiRequest r) {
        SessionStore.LoginResult result = s.sessions().redeem(r.str("code"), s.config().sessionDays(), r.client);
        JsonObject o = new JsonObject();
        o.addProperty("token", result.token());
        o.addProperty("uuid", result.uuid().toString());
        o.addProperty("name", s.players().name(result.uuid()));
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

    /** Entdeckte Chunks. Mit since=<version> kommt nur {unchanged:true}, wenn sich nichts geändert hat (spart Daten). */
    private JsonObject discovered(String dimension, String since) {
        long version = s.discovery().version();
        JsonObject o = new JsonObject();
        o.addProperty("dimension", dimension);
        o.addProperty("version", version);
        if (since != null && since.equals(String.valueOf(version))) {
            o.addProperty("unchanged", true);
            return o;
        }
        int[] flat = s.discovery().export(dimension);
        JsonArray arr = new JsonArray(flat.length);
        for (int v : flat) arr.add(v);
        o.add("chunks", arr);
        return o;
    }

    private JsonObject knownPlayers() {
        JsonArray arr = new JsonArray();
        s.players().names().forEach(arr::add);
        JsonObject o = new JsonObject();
        o.add("players", arr);
        return o;
    }

    // ------------------------------------------------------------------ Webseite und Sicherheit

    private void serveStatic(HttpExchange ex, String method, List<String> seg) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        if (!method.equals("GET")) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String name = seg.isEmpty() ? "index.html" : String.join("/", seg);
        byte[] content = STATIC_PATH.matcher(name).matches() ? staticFile(name) : null;
        if (content == null) {
            byte[] msg = "Nicht gefunden".getBytes(StandardCharsets.UTF_8);
            h.set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            ex.getResponseBody().write(msg);
            return;
        }
        h.set("Content-Type", MIME.get(name.substring(name.lastIndexOf('.') + 1)));
        h.set("Cache-Control", "no-cache");
        h.set("Content-Security-Policy", contentSecurityPolicy(ex));
        h.set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(200, content.length);
        ex.getResponseBody().write(content);
    }

    /**
     * Lädt eine Webdatei aus der Mod-Jar (und merkt sie sich, die Jar ändert sich ja nicht).
     * Ist in der Config "webOverrideDir" gesetzt, kommt sie stattdessen frisch aus diesem Ordner
     * (zum Entwickeln oder Anpassen der Seite ohne neu zu bauen).
     */
    private byte[] staticFile(String name) {
        String override = s.config().webOverrideDir();
        if (!override.isBlank()) {
            Path base = Path.of(override).toAbsolutePath().normalize();
            Path file = base.resolve(name).normalize();
            if (!file.startsWith(base)) return null;
            try {
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            } catch (IOException e) {
                return null;
            }
        }
        return staticContent.computeIfAbsent(name, n -> {
            try (InputStream in = ApiServer.class.getResourceAsStream("/world_planner_web/" + n)) {
                return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
            } catch (IOException e) {
                logger.warn("[World Planner] Webdatei {} nicht lesbar: {}", n, e.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * Strenge Sicherheitsregel für die Webseite. Nur die BlueMap-Adressen dürfen eingebettet und
     * (für die Erreichbarkeits-Prüfung) angefragt werden – nicht beliebige fremde Seiten.
     */
    private String contentSecurityPolicy(HttpExchange ex) {
        Set<String> maps = new LinkedHashSet<>();
        origin(s.config().liveMapUrl()).ifPresent(maps::add);
        origin(s.config().seedMapUrl()).ifPresent(maps::add);
        if (s.config().liveMapUrl().isBlank() && !s.detectedBlueMapIds().get().isEmpty()) {
            // Die Webseite probiert dann BlueMaps Standard-Port auf demselben Rechner (siehe maps3d.js mapUrl()).
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host != null) {
                String hostname = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.split(":")[0];
                if (HOST.matcher(hostname).matches()) maps.add("http://" + hostname + ":" + BLUEMAP_DEFAULT_PORT);
            }
        }
        String mapList = String.join(" ", maps);
        return "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                + "connect-src 'self'" + (mapList.isEmpty() ? "" : " " + mapList) + "; "
                + "frame-src " + (mapList.isEmpty() ? "'none'" : mapList) + "; "
                + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    }

    /** "https://karte.example.de:8443/pfad" → "https://karte.example.de:8443" – oder leer, wenn ungültig. */
    static Optional<String> origin(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !(scheme.equals("http") || scheme.equals("https"))) return Optional.empty();
            if (!HOST.matcher(host).matches()) return Optional.empty();
            return Optional.of(scheme + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
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

    /**
     * Wer fragt? Hinter einem Reverse-Proxy (nginx auf demselben Rechner) kommt jede Anfrage von 127.0.0.1 –
     * dann zählt die Adresse aus X-Forwarded-For. Von außen wird der Kopf ignoriert, sonst könnte man ihn fälschen.
     */
    static String clientAddress(HttpExchange ex) {
        InetAddress remote = ex.getRemoteAddress().getAddress();
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (remote.isLoopbackAddress() && forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 64 ? first.substring(0, 64) : first;
        }
        return remote.getHostAddress();
    }

    private static String bearer(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            try {
                q.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw PlannerException.badRequest("Ungültige Adresse.");
            }
        }
        return q;
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
