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

    private record Session(UUID uuid, long created, long expires, long lastUsed) {}

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
                            o.get("created").getAsLong(), o.get("expires").getAsLong(), o.get("lastUsed").getAsLong()));
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

    /** @param client Absender (IP-Adresse), für die Bremse gegen Durchprobieren */
    synchronized LoginResult redeem(String rawCode, int sessionDays, String client) {
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
        sessionsByHash.put(hash(token), new Session(pending.uuid, now, now + sessionDays * 86_400_000L, now));
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
            sessionsByHash.put(h, new Session(s.uuid, s.created, s.expires, now));
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
            root.add(h, o);
        });
        return root;
    }
}
