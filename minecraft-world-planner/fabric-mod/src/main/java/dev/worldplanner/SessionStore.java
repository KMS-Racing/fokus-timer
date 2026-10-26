package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Login ohne Passwort: Der Spieler tippt im Spiel /planer login und bekommt einen
 * Einmal-Code. Wer den Code auf der Webseite eingibt, beweist damit, dass er gerade
 * als dieser Spieler auf dem Server ist. Danach bekommt der Browser ein Sitzungs-Token.
 *
 * Gespeichert wird nur ein SHA-256-Hash des Tokens. Selbst wer die Datei liest,
 * kann sich damit nicht einloggen.
 */
final class SessionStore extends JsonStore {
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // ohne 0/O und 1/I
    private static final long CODE_VALID_MS = 5 * 60 * 1000;
    /** Falsche Codes pro Absender und Minute. Pro Absender, damit ein Störenfried nicht alle aussperren kann. */
    private static final int MAX_FAILURES_PER_CLIENT = 30;
    /** Sicherheitsnetz für alle zusammen (Codes haben 40 Bit – Raten ist ohnehin aussichtslos). */
    private static final int MAX_FAILURES_TOTAL = 300;
    private static final long LAST_USED_SAVE_INTERVAL_MS = 60 * 60 * 1000;

    /** device: grobe Beschreibung wie "Firefox · Windows" (aus dem Browser-Kennzeichen, keine IP-Adresse). */
    private record Session(UUID uuid, long created, long expires, long lastUsed, String device) {}

    /** Für die Geräteliste auf der Webseite. id = Anfang des Token-Hashes (nicht das Token selbst). */
    record SessionInfo(String id, long created, long lastUsed, String device, boolean current) {}

    private record PendingCode(UUID uuid, long expires) {}

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessionsByHash = new HashMap<>();
    private final Map<String, PendingCode> codes = new HashMap<>(); // nur im Speicher
    private final RateLimiter clientFailures = new RateLimiter(MAX_FAILURES_PER_CLIENT, 60_000);
    private final RateLimiter totalFailures = new RateLimiter(MAX_FAILURES_TOTAL, 60_000);

    SessionStore(Path path, Logger logger) {
        super(path);
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                try {
                    JsonObject o = e.getValue().getAsJsonObject();
                    sessionsByHash.put(e.getKey(), new Session(UUID.fromString(o.get("uuid").getAsString()),
                            o.get("created").getAsLong(), o.get("expires").getAsLong(), o.get("lastUsed").getAsLong(),
                            o.has("device") ? o.get("device").getAsString() : "unbekanntes Gerät"));
                } catch (Exception ignored) {
                    // kaputte Sitzung überspringen
                }
            }
        }
    }

    /** Neuer Code für den Spieler. Ein alter, noch offener Code wird dabei ungültig. */
    synchronized String createCode(UUID uuid) {
        codes.values().removeIf(c -> c.uuid.equals(uuid));
        String code;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            code = sb.toString();
        } while (codes.containsKey(code));
        codes.put(code, new PendingCode(uuid, System.currentTimeMillis() + CODE_VALID_MS));
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    record LoginResult(String token, UUID uuid) {}

    synchronized LoginResult redeem(String rawCode, int sessionDays, String client) {
        return redeem(rawCode, sessionDays, client, null);
    }

    /**
     * @param client Absender (IP-Adresse), nur für die Bremse gegen Durchprobieren – wird nicht gespeichert
     * @param userAgent Browser-Kennzeichen; gespeichert wird davon nur eine grobe Beschreibung (deviceLabel)
     */
    synchronized LoginResult redeem(String rawCode, int sessionDays, String client, String userAgent) {
        long now = System.currentTimeMillis();
        if (clientFailures.isBlocked(client, now) || totalFailures.isBlocked("*", now)) {
            throw new PlannerException(429, "Zu viele falsche Codes. Warte eine Minute.");
        }
        String code = rawCode == null ? "" : rawCode.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        PendingCode pending = codes.remove(code);
        if (pending == null || pending.expires < now) {
            clientFailures.record(client, now);
            totalFailures.record("*", now);
            throw new PlannerException(401, "Code falsch oder abgelaufen. Tippe im Spiel /planer login für einen neuen Code.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessionsByHash.put(hash(token), new Session(pending.uuid, now, now + sessionDays * 86_400_000L, now, deviceLabel(userAgent)));
        changed();
        return new LoginResult(token, pending.uuid);
    }

    synchronized Optional<UUID> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String h = hash(token);
        Session s = sessionsByHash.get(h);
        long now = System.currentTimeMillis();
        if (s == null) return Optional.empty();
        if (s.expires < now) {
            sessionsByHash.remove(h);
            changed();
            return Optional.empty();
        }
        if (now - s.lastUsed > LAST_USED_SAVE_INTERVAL_MS) {
            sessionsByHash.put(h, new Session(s.uuid, s.created, s.expires, now, s.device));
            changed();
        }
        return Optional.of(s.uuid);
    }

    synchronized void logout(String token) {
        if (token != null && sessionsByHash.remove(hash(token)) != null) changed();
    }

    /** Meldet einen Spieler auf allen Geräten ab, z. B. wenn er sein Handy verloren hat. */
    synchronized int logoutAll(UUID uuid) {
        int before = sessionsByHash.size();
        sessionsByHash.values().removeIf(s -> s.uuid.equals(uuid));
        int removed = before - sessionsByHash.size();
        if (removed > 0) changed();
        return removed;
    }

    /** Alle Geräte des Spielers, neueste zuerst. */
    synchronized java.util.List<SessionInfo> sessionsOf(UUID uuid, String currentToken) {
        String current = currentToken == null ? null : hash(currentToken);
        java.util.List<SessionInfo> out = new java.util.ArrayList<>();
        sessionsByHash.forEach((h, s) -> {
            if (s.uuid.equals(uuid)) out.add(new SessionInfo(h.substring(0, 12), s.created, s.lastUsed, s.device, h.equals(current)));
        });
        out.sort((a, b) -> Long.compare(b.created(), a.created()));
        return out;
    }

    /** Logins seit "since" – für die Sicherheits-Nachricht im Spiel. */
    synchronized java.util.List<SessionInfo> recentLogins(UUID uuid, long since) {
        java.util.List<SessionInfo> out = new java.util.ArrayList<>();
        sessionsByHash.forEach((h, s) -> {
            if (s.uuid.equals(uuid) && s.created >= since) out.add(new SessionInfo(h.substring(0, 12), s.created, s.lastUsed, s.device, false));
        });
        return out;
    }

    /** Ein bestimmtes eigenes Gerät abmelden. @return false, wenn es das nicht (mehr) gibt */
    synchronized boolean logoutSession(UUID uuid, String id) {
        if (id == null || !id.matches("[0-9a-f]{12}")) return false;
        boolean removed = sessionsByHash.entrySet().removeIf(e -> e.getKey().startsWith(id) && e.getValue().uuid.equals(uuid));
        if (removed) changed();
        return removed;
    }

    /** Alle eigenen Geräte außer diesem abmelden. */
    synchronized int logoutOthers(UUID uuid, String currentToken) {
        String current = currentToken == null ? "" : hash(currentToken);
        int before = sessionsByHash.size();
        sessionsByHash.entrySet().removeIf(e -> e.getValue().uuid.equals(uuid) && !e.getKey().equals(current));
        int removed = before - sessionsByHash.size();
        if (removed > 0) changed();
        return removed;
    }

    /** "Firefox · Windows" – bewusst grob: Es soll nur reichen, die eigenen Geräte wiederzuerkennen. */
    static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "unbekanntes Gerät";
        String ua = userAgent;
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : null;
        String os = ua.contains("Windows") ? "Windows" : ua.contains("Android") ? "Android"
                : ua.contains("iPhone") || ua.contains("iPad") ? "iOS" : ua.contains("CrOS") ? "ChromeOS"
                : ua.contains("Mac OS X") ? "macOS" : ua.contains("Linux") ? "Linux" : null;
        if (browser == null && os == null) return "anderes Programm";
        if (browser == null) return os;
        return os == null ? browser : browser + " · " + os;
    }

    synchronized void cleanup() {
        long now = System.currentTimeMillis();
        codes.values().removeIf(c -> c.expires < now);
        if (sessionsByHash.values().removeIf(s -> s.expires < now)) changed();
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected JsonElement toJson() {
        JsonObject root = new JsonObject();
        sessionsByHash.forEach((h, s) -> {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", s.uuid.toString());
            o.addProperty("created", s.created);
            o.addProperty("expires", s.expires);
            o.addProperty("lastUsed", s.lastUsed);
            o.addProperty("device", s.device);
            root.add(h, o);
        });
        return root;
    }
}
