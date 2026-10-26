package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Events mit Kalender: Regeln, Rechte, Zusagen, Erinnerungen im Spiel, Speichern und API. */
class EventsTest {
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Montag, 26.10.2026, 18:00 in Berlin. */
    static final long NOW = ZonedDateTime.of(2026, 10, 26, 18, 0, 0, 0, BERLIN).toInstant().toEpochMilli();
    static final long HOUR = 3_600_000L, MIN = 60_000L;

    @TempDir
    Path dir;
    TestSupport.World w;
    EventStore ev;
    UUID alice, bob, carl, dana, op;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("maxEventsPerPlayer", 2));
        ev = w.services.events();
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        dana = w.player(4, "Dana");
        op = w.admin(9, "Chef");
        w.discover(-10, -10, 10, 10);
    }

    // ------------------------------------------------------------------ Anlegen

    @Test
    void createChecksEverythingAndTheOrganizerComes() {
        assertEquals(400, status(() -> ev.create(alice, false, event("Bauabend", NOW - MIN), NOW)), "Beginn in der Vergangenheit");
        assertEquals(400, status(() -> ev.create(alice, false, event("Bauabend", NOW + 400L * 24 * HOUR), NOW)), "mehr als ein Jahr im Voraus");
        assertEquals(400, status(() -> ev.create(alice, false, with(event("Bauabend", NOW + HOUR), "minutes", 10), NOW)), "kürzer als 15 Minuten");
        assertEquals(400, status(() -> ev.create(alice, false, with(event("Bauabend", NOW + HOUR), "minutes", 25 * 60), NOW)), "länger als 24 Stunden");
        assertEquals(400, status(() -> ev.create(alice, false, event("x", NOW + HOUR), NOW)), "Titel zu kurz");
        assertEquals(400, status(() -> ev.create(alice, false, event("<script>", NOW + HOUR), NOW)), "kein HTML im Titel");
        assertEquals(400, status(() -> ev.create(alice, false, with(event("Bauabend", NOW + HOUR), "kind", "rennen"), NOW)), "unbekannte Art");
        assertEquals(400, status(() -> ev.create(alice, false, with(event("Bauabend", NOW + HOUR), "visibility", "team"), NOW)), "Nur Team ohne Team");

        String id = ev.create(alice, false, with(event("Bauabend", NOW + HOUR + 30_123), "minutes", 90), NOW);
        JsonObject e = ev.eventJson(id, bob, false, NOW);
        assertEquals(NOW + HOUR, e.get("start").getAsLong(), "auf die Minute gerundet");
        assertEquals(NOW + HOUR + 90 * MIN, e.get("end").getAsLong());
        assertEquals("upcoming", e.get("state").getAsString());
        assertEquals("Alice", e.getAsJsonObject("organizer").get("name").getAsString());
        assertEquals(1, e.getAsJsonObject("counts").get("yes").getAsInt(), "wer einlädt, kommt selbst");
        assertFalse(e.get("canEdit").getAsBoolean(), "Bob darf nichts ändern");
        assertTrue(ev.eventJson(id, alice, false, NOW).get("canEdit").getAsBoolean());

        ev.create(alice, false, event("Zweites", NOW + 2 * HOUR), NOW);
        assertEquals(400, status(() -> ev.create(alice, false, event("Drittes", NOW + 3 * HOUR), NOW)), "Grenze: 2 anstehende");
        ev.cancel(alice, false, id, "", NOW);
        assertNotNull(ev.create(alice, false, event("Drittes", NOW + 3 * HOUR), NOW), "abgesagte zählen nicht mit");
        assertNotNull(ev.create(op, true, event("Admin-Event", NOW + 3 * HOUR), NOW));
    }

    @Test
    void teamEventsOnlyForTheTeam() {
        String team = w.teams.create(alice, "Baumeister", "BAU", null, null, null, null, null);
        w.teams.invite(alice, team, bob);
        w.teams.acceptInvite(bob, team);
        assertEquals(403, status(() -> ev.create(carl, false, with(with(event("Fremd", NOW + HOUR), "teamId", team), "visibility", "team"), NOW)),
                "nur Mitglieder legen Team-Events an");
        String id = ev.create(alice, false, with(with(event("Teamabend", NOW + HOUR), "teamId", team), "visibility", "team"), NOW);
        assertEquals("Teamabend", ev.eventJson(id, bob, false, NOW).get("title").getAsString());
        assertEquals(404, status(() -> ev.eventJson(id, carl, false, NOW)), "Carl sieht es nicht");
        assertEquals(0, ev.listJson(carl, false, NOW).size());
        assertEquals(404, status(() -> ev.answer(carl, id, "yes", NOW)), "…und kann nicht zusagen");
        assertEquals(404, status(() -> ev.eventJson(id, op, true, NOW)), "auch Admins sehen fremde Team-Events nicht");
        assertEquals("team:" + team, ev.audienceOf(id));

        TeamStore.Dissolved d = w.teams.dissolve(alice, false, team, "Baumeister");
        w.services.onTeamDissolved(d);
        JsonObject after = ev.eventJson(id, alice, false, NOW);
        assertEquals("cancelled", after.get("state").getAsString(), "Team aufgelöst = abgesagt");
        assertEquals("Das Team wurde aufgelöst.", after.get("cancelReason").getAsString());
        assertEquals(404, status(() -> ev.eventJson(id, bob, false, NOW)), "danach sieht es nur noch Alice");
    }

    // ------------------------------------------------------------------ Zusagen, Ändern, Absagen

    @Test
    void answersOnlyForYourselfAndOnlyWhileOpen() {
        String id = ev.create(alice, false, event("Party", NOW + HOUR), NOW);
        ev.answer(bob, id, "yes", NOW);
        ev.answer(carl, id, "maybe", NOW);
        ev.answer(dana, id, "no", NOW);
        assertEquals(400, status(() -> ev.answer(dana, id, "vielleicht-doch", NOW)));
        JsonObject e = ev.eventJson(id, alice, false, NOW);
        assertEquals(2, e.getAsJsonObject("counts").get("yes").getAsInt());
        assertEquals(1, e.getAsJsonObject("counts").get("maybe").getAsInt());
        assertEquals(1, e.getAsJsonObject("counts").get("no").getAsInt());
        assertEquals("maybe", ev.eventJson(id, carl, false, NOW).get("myAnswer").getAsString());
        ev.answer(carl, id, null, NOW);
        assertFalse(ev.eventJson(id, carl, false, NOW).has("myAnswer"), "Antwort zurückgenommen");

        assertEquals(403, status(() -> ev.update(bob, false, id, with(new JsonObject(), "title", "Bobs Party"), NOW)));
        assertEquals(403, status(() -> ev.cancel(bob, false, id, "", NOW)));
        assertEquals(403, status(() -> ev.delete(bob, false, id)));

        long during = NOW + HOUR + 5 * MIN, after = NOW + 2 * HOUR + MIN;
        ev.answer(dana, id, "yes", during);
        assertEquals("running", ev.eventJson(id, dana, false, during).get("state").getAsString());
        assertEquals(409, status(() -> ev.answer(dana, id, "no", after)), "vorbei");
        assertEquals("past", ev.eventJson(id, dana, false, after).get("state").getAsString());
        assertEquals(409, status(() -> ev.update(alice, false, id, with(new JsonObject(), "title", "Neu"), after)));

        // Während es läuft, darf Alice es noch bearbeiten – die Webseite schickt dabei den alten Beginn mit
        String live = ev.create(alice, false, event("Bauen", NOW + HOUR), NOW);
        ev.update(alice, false, live, with(with(new JsonObject(), "start", NOW + HOUR), "description", "Treffen am Turm"), during);
        assertEquals("Treffen am Turm", ev.eventJson(live, alice, false, during).get("description").getAsString());
        assertEquals(400, status(() -> ev.update(alice, false, live, with(new JsonObject(), "start", NOW + HOUR - 30 * MIN), during)),
                "einen neuen Beginn in der Vergangenheit gibt es trotzdem nicht");
        ev.delete(alice, false, live);

        String other = ev.create(alice, false, event("Markt", NOW + HOUR), NOW);
        ev.cancel(op, true, other, "Server-Wartung", NOW);
        JsonObject c = ev.eventJson(other, bob, false, NOW);
        assertEquals("cancelled", c.get("state").getAsString(), "Admins dürfen absagen");
        assertEquals("Server-Wartung", c.get("cancelReason").getAsString());
        assertEquals(409, status(() -> ev.answer(bob, other, "yes", NOW)), "abgesagt");
        assertEquals(409, status(() -> ev.cancel(alice, false, other, "", NOW)), "schon abgesagt");
        assertEquals("Markt", ev.delete(alice, false, other));
        assertEquals(404, status(() -> ev.eventJson(other, alice, false, NOW)));
    }

    @Test
    void movingAnEventIsReportedToEveryoneWhoAnswered() {
        String id = ev.create(alice, false, event("Bauabend", NOW + 2 * HOUR), NOW);
        ev.update(alice, false, id, with(new JsonObject(), "title", "Großer Bauabend"), NOW);
        assertFalse(ev.eventJson(id, alice, false, NOW).has("movedAt"), "nur der Titel geändert");
        ev.update(alice, false, id, with(new JsonObject(), "start", NOW + 3 * HOUR), NOW);
        assertFalse(ev.eventJson(id, alice, false, NOW).has("movedAt"), "noch hat niemand außer Alice geantwortet");
        ev.answer(bob, id, "yes", NOW);
        ev.update(alice, false, id, with(new JsonObject(), "start", NOW + 4 * HOUR), NOW + MIN);
        JsonObject e = ev.eventJson(id, bob, false, NOW + MIN);
        assertEquals(NOW + MIN, e.get("movedAt").getAsLong());
        assertEquals(NOW + 3 * HOUR, e.get("previousStart").getAsLong());

        Notices.Notice moved = find(bob, "event-moved:", NOW + 2 * MIN);
        assertNotNull(moved);
        assertFalse(moved.todo(), "nur als Nachricht, nicht als Aufgabe");
        assertEquals("Verschoben: »Großer Bauabend« jetzt am Mo 26.10. 22:00 (vorher Mo 26.10. 21:00)", moved.text());
        assertNull(find(carl, "event-moved:", NOW + 2 * MIN), "Carl hat nicht geantwortet");
        assertNull(find(bob, "event-moved:", NOW + 25 * HOUR), "nach einem Tag nicht mehr");
    }

    @Test
    void theListKeepsSixtyDaysOfPastEvents() {
        long longAgo = NOW - 70L * 24 * HOUR, recently = NOW - 20L * 24 * HOUR;
        String old = ev.create(alice, false, event("Altes Fest", longAgo + HOUR), longAgo);
        String recent = ev.create(alice, false, event("Neulich", recently + HOUR), recently);
        List<String> listed = new ArrayList<>();
        ev.listJson(bob, false, NOW).forEach(x -> listed.add(x.getAsJsonObject().get("title").getAsString()));
        assertEquals(List.of("Neulich"), listed, "älter als 60 Tage: nicht mehr in der Liste");
        assertEquals("Altes Fest", ev.eventJson(old, bob, false, NOW).get("title").getAsString(), "…aber noch gespeichert und per Link erreichbar");
        assertNotNull(recent);
    }

    // ------------------------------------------------------------------ Hinweise und Erinnerungen

    @Test
    void remindersOnlyForYesAndMaybe() {
        JsonObject data = event("Bauabend", NOW + HOUR);
        data.add("place", place(100, null, 200));
        String id = ev.create(alice, false, data, NOW);
        ev.answer(bob, id, "yes", NOW);
        ev.answer(carl, id, "maybe", NOW);
        ev.answer(dana, id, "no", NOW);

        assertNull(find(bob, "event-soon:", NOW), "eine Stunde vorher noch nicht");
        long soon = NOW + 50 * MIN;
        Notices.Notice n = find(bob, "event-soon:", soon);
        assertNotNull(n);
        assertTrue(n.todo() && n.live(), "Nachricht im Spiel und auf dem Dashboard");
        assertEquals("In 10 Minuten: »Bauabend« – Treffpunkt: 100 ~ 200", n.text());
        assertNull(n.command(), "kein Land = kein Wegweiser-Befehl");
        assertNotNull(find(carl, "event-soon:", soon), "Vielleicht bekommt sie auch");
        assertNotNull(find(alice, "event-soon:", soon), "der Veranstalter auch");
        assertNull(find(dana, "event-soon:", soon), "Komme nicht = keine Erinnerung");
        assertEquals("In 1 Minute: »Bauabend« – Treffpunkt: 100 ~ 200", find(bob, "event-soon:", NOW + 59 * MIN + 30_000).text());

        long started = NOW + HOUR + 2 * MIN;
        assertNull(find(bob, "event-soon:", started));
        assertEquals("Jetzt: »Bauabend« beginnt – Treffpunkt: 100 ~ 200", find(bob, "event-now:", started).text());
        assertNull(find(dana, "event-now:", started));
        assertNull(find(bob, "event-now:", NOW + HOUR + 11 * MIN), "zehn Minuten nach dem Start ist Schluss");
    }

    @Test
    void newEventsAskEveryoneWhoCanSeeThem() {
        String id = ev.create(alice, false, event("Party", NOW + 2 * 24 * HOUR), NOW);
        Notices.Notice ask = find(bob, "event-ask:", NOW);
        assertNotNull(ask);
        assertTrue(ask.todo() && ask.live());
        assertEquals("Event: »Party« am Mi 28.10. 18:00 – kommst du?", ask.text());
        assertEquals("/planer event " + id + " ja", ask.command());
        assertEquals("[Komme]", ask.commandLabel());
        assertEquals("#/events/" + id, ask.href());
        assertNull(find(alice, "event-ask:", NOW), "der Veranstalter wird nicht gefragt");
        ev.answer(bob, id, "no", NOW);
        assertNull(find(bob, "event-ask:", NOW), "beantwortet = erledigt");

        String later = ev.create(carl, false, event("Später", NOW + 9 * 24 * HOUR), NOW);
        assertNull(find(dana, "event-ask:" + later, NOW), "mehr als 7 Tage vorher noch nicht");

        ev.answer(dana, id, "yes", NOW);
        ev.cancel(alice, false, id, "Regen", NOW);
        Notices.Notice c = find(dana, "event-cancelled:", NOW);
        assertNotNull(c);
        assertTrue(c.todo(), "steht bis zum alten Beginn bei Zu erledigen");
        assertEquals("Abgesagt: »Party« (Mi 28.10. 18:00) – Regen", c.text());
        assertNull(find(bob, "event-cancelled:", NOW), "Bob hatte abgesagt – ihn interessiert es nicht");
        assertNull(find(dana, "event-cancelled:", NOW + 3 * 24 * HOUR), "nach dem alten Beginn weg");
    }

    @Test
    void aLandAsMeetingPointKeepsItsSpotWhenTheLandIsDeleted() {
        String land = w.lands.create(alice, false, "Marktplatz", TestSupport.OVERWORLD, TestSupport.chunks(2, 3));
        String id = ev.create(alice, false, with(event("Markt", NOW + HOUR), "landId", land), NOW);
        JsonObject e = ev.eventJson(id, bob, false, NOW);
        assertEquals("Marktplatz", e.get("landName").getAsString());
        assertEquals(2 * 16 + 8, e.getAsJsonObject("place").get("x").getAsInt(), "Mitte des Landes");
        assertEquals(3 * 16 + 8, e.getAsJsonObject("place").get("z").getAsInt());
        ev.answer(bob, id, "yes", NOW);
        Notices.Notice soon = find(bob, "event-soon:", NOW + 55 * MIN);
        assertEquals("In 5 Minuten: »Markt« – Treffpunkt: Land »Marktplatz«", soon.text());
        assertEquals("/planer weg Marktplatz", soon.command(), "mit Wegweiser zum Land");
        assertEquals("[Hinführen]", soon.commandLabel());

        w.lands.delete(alice, false, land);
        w.services.onLandDeleted(land);
        JsonObject after = ev.eventJson(id, bob, false, NOW);
        assertFalse(after.has("landName"));
        assertEquals(2 * 16 + 8, after.getAsJsonObject("place").get("x").getAsInt(), "der Treffpunkt bleibt als Koordinate");
        assertEquals(404, status(() -> ev.create(alice, false, with(event("Weg", NOW + HOUR), "landId", land), NOW)));
    }

    @Test
    void everythingSurvivesARestart() throws Exception {
        JsonObject data = with(event("Bauabend", NOW + HOUR), "description", "Wir bauen die Brücke fertig.");
        data.add("place", place(-50, 70, 120));
        String id = ev.create(alice, false, data, NOW);
        ev.answer(bob, id, "maybe", NOW);
        String cancelled = ev.create(alice, false, event("Markt", NOW + 2 * HOUR), NOW);
        ev.cancel(alice, false, cancelled, "Zu wenige", NOW);
        JsonArray before = ev.listJson(bob, false, NOW);
        TestSupport.World again = w.restart();
        assertEquals(before, again.services.events().listJson(bob, false, NOW));
        assertEquals(2, before.size());
    }

    @Test
    void chatTimesUseTheServerTimeZone() {
        assertEquals("Mo 26.10. 18:00", EventStore.when(NOW, BERLIN));
        assertEquals("Mo 26.10. 17:00", EventStore.when(NOW, ZoneId.of("UTC")));
        assertEquals("in 10 Minuten", EventStore.inMinutes(10 * MIN));
        assertEquals("in 1 Minute", EventStore.inMinutes(1));
        assertEquals("gleich", EventStore.inMinutes(0));
    }

    // ------------------------------------------------------------------ API

    @Test
    void apiCreatesListsAnswersAndLogs() {
        long start = System.currentTimeMillis() + 2 * HOUR;
        JsonObject created = routes().handle(post(alice, "{\"title\":\"Bauwettbewerb\",\"kind\":\"build\",\"start\":" + start + ",\"minutes\":120}",
                "events")).getAsJsonObject();
        String id = created.get("id").getAsString();
        JsonObject list = routes().handle(get(bob, "events")).getAsJsonObject();
        assertEquals(1, list.getAsJsonArray("events").size());
        assertTrue(list.has("serverTime"));
        routes().handle(post(bob, "{\"answer\":\"yes\"}", "events", id, "answer"));
        assertEquals("yes", routes().handle(get(bob, "events", id)).getAsJsonObject().get("myAnswer").getAsString());
        assertEquals(400, status(() -> routes().handle(post(bob, "{}", "events", id, "navigate"))), "ohne Treffpunkt kein Wegweiser");
        String withPlace = routes().handle(post(alice, "{\"title\":\"Treffen\",\"start\":" + start + ",\"place\":{\"x\":5,\"z\":7}}", "events"))
                .getAsJsonObject().get("id").getAsString();
        assertEquals(409, status(() -> routes().handle(post(bob, "{}", "events", withPlace, "navigate"))), "nicht im Spiel");
        assertEquals(403, status(() -> routes().handle(post(bob, "{}", "events", id, "delete"))));
        assertEquals(404, status(() -> routes().handle(post(bob, "{}", "events", id, "explode"))));
        routes().handle(post(alice, "{\"reason\":\"Krank\"}", "events", id, "cancel"));
        List<String> types = new ArrayList<>();
        w.activity.visibleTo(t -> false, 10).forEach(e -> types.add(e.getAsJsonObject().get("type").getAsString()));
        assertEquals(List.of("event.cancelled", "event.created", "event.created"), types, "neueste zuerst");

        TestSupport.World off = new TestSupport.World(dir.resolve("aus"), TestSupport.config("events", false));
        assertEquals(404, status(() -> new EventRoutes(off.services).handle(get(alice, "events"))));
    }

    // ------------------------------------------------------------------ Hilfen

    private EventRoutes routes() {
        return new EventRoutes(w.services);
    }

    private Notices.Notice find(UUID player, String idPrefix, long now) {
        return Notices.collect(w.services, player, w.players.isAdmin(player), "2026-10-26", now).stream()
                .filter(n -> n.id().startsWith(idPrefix)).findFirst().orElse(null);
    }

    private static JsonObject event(String title, long start) {
        JsonObject o = new JsonObject();
        o.addProperty("title", title);
        o.addProperty("start", start);
        return o;
    }

    private static JsonObject with(JsonObject o, String key, Object value) {
        if (value instanceof Number n) o.addProperty(key, n);
        else o.addProperty(key, value == null ? null : value.toString());
        return o;
    }

    private static JsonObject place(int x, Integer y, int z) {
        JsonObject p = new JsonObject();
        p.addProperty("dimension", TestSupport.OVERWORLD);
        p.addProperty("x", x);
        if (y != null) p.addProperty("y", y);
        p.addProperty("z", z);
        return p;
    }

    private static int status(Runnable r) {
        return assertThrows(PlannerException.class, r::run).status;
    }

    private static ApiRequest get(UUID user, String... path) {
        return new ApiRequest("GET", List.of(path), Map.of(), user, false, "127.0.0.1", "t", new ByteArrayInputStream(new byte[0]), 1024);
    }

    private static ApiRequest post(UUID user, String body, String... path) {
        return new ApiRequest("POST", List.of(path), Map.of(), user, false, "127.0.0.1", "t",
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), 1024);
    }
}
