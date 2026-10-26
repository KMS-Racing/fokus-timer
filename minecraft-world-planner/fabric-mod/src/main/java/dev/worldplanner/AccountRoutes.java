package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** /api/me, /api/meta, /api/people, /api/prefs, /api/todo, /api/sessions, /api/activity, /api/export, /api/import */
final class AccountRoutes extends Routes {
    private final ImportExport importExport;

    AccountRoutes(PlannerServices services) {
        super(services);
        this.importExport = new ImportExport(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        if (r.is("GET", "me")) return me(r);
        if (r.is("GET", "meta")) return meta();
        if (r.is("GET", "people")) return people(r);
        if (r.is("GET", "prefs")) return s.prefs().get(r.user);
        if (r.is("POST", "prefs", "favorite")) {
            s.prefs().setFavorite(r.user, r.str("kind"), r.str("id"), r.bool("on", true));
            return s.prefs().get(r.user);
        }
        if (r.is("POST", "prefs", "game")) {
            s.prefs().setGame(r.user, r.str("key"), r.bool("on", true));
            return s.prefs().get(r.user);
        }
        if (r.is("GET", "todo")) {
            String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
            return Notices.toApiJson(Notices.collect(s, r.user, r.admin, today));
        }
        if (r.is("GET", "sessions")) return sessions(r);
        if (r.is("POST", "sessions", "logout-others")) {
            JsonObject o = ok();
            o.addProperty("removed", s.sessions().logoutOthers(r.user, r.token));
            return o;
        }
        if (r.is("POST", "sessions", "*", "logout")) {
            if (!s.sessions().logoutSession(r.user, r.seg(1))) throw PlannerException.notFound("Dieses Gerät ist nicht (mehr) angemeldet.");
            return ok();
        }
        if (r.is("GET", "activity")) {
            int limit = 50;
            try {
                limit = Math.max(1, Math.min(200, Integer.parseInt(r.query.getOrDefault("limit", "50"))));
            } catch (NumberFormatException ignored) {
                // Standard
            }
            JsonObject o = new JsonObject();
            o.add("events", s.activity().visibleTo(teamId -> s.access().isTeamMember(r.user, teamId), limit));
            return o;
        }
        if (r.is("GET", "export")) return importExport.export(r.user);
        if (r.is("POST", "import")) return importExport.importData(r.user, r.admin, r.raw("data"), r.bool("apply", false));
        throw unknown();
    }

    private JsonObject me(ApiRequest r) {
        PlannerConfig c = s.config();
        JsonObject o = new JsonObject();
        o.addProperty("uuid", r.user.toString());
        o.addProperty("name", name(r.user));
        o.addProperty("admin", r.admin);
        o.addProperty("usedChunks", s.lands().ownedChunkCount(r.user));
        o.addProperty("maxChunks", c.maxChunksPerPlayer());
        o.addProperty("ownedLands", s.lands().ownedLandCount(r.user));
        o.addProperty("ownMarkers", s.markers().countOwnedBy(r.user));
        o.addProperty("maxMarkers", c.maxMarkersPerPlayer());
        o.addProperty("claimOnlyDiscovered", c.claimOnlyDiscovered());
        JsonArray teams = new JsonArray();
        for (String teamId : s.teams().teamIdsOf(r.user)) {
            JsonObject t = new JsonObject();
            t.addProperty("id", teamId);
            t.addProperty("tag", s.teams().tagOf(teamId));
            t.addProperty("name", s.teams().nameOf(teamId));
            TeamStore.Role role = s.teams().role(r.user, teamId);
            t.addProperty("role", role.id());
            t.addProperty("roleLabel", role.label);
            teams.add(t);
        }
        o.add("teams", teams);
        o.addProperty("pendingTeamInvites", s.teams().pendingInvitesFor(r.user).size());
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
        o.addProperty("tradeUnit", c.tradeUnit());
        return o;
    }

    /** Eigene angemeldete Geräte (Geräte-Etikett, Zeiten) – nie die Tokens selbst. */
    private JsonObject sessions(ApiRequest r) {
        JsonArray arr = new JsonArray();
        for (SessionStore.SessionInfo i : s.sessions().sessionsOf(r.user, r.token)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", i.id());
            o.addProperty("device", i.device());
            o.addProperty("created", i.created());
            o.addProperty("lastUsed", i.lastUsed());
            o.addProperty("current", i.current());
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("sessions", arr);
        return o;
    }

    /** Auswahl-Listen und Grenzen für die Webseite – so steht jede Kategorie nur an einer Stelle (auf dem Server). */
    private JsonObject meta() {
        PlannerConfig c = s.config();
        JsonObject o = new JsonObject();
        o.add("markerTypes", map(s.markers().types()));
        o.add("markerVisibility", map(MarkerStore.VISIBILITY));
        o.add("markerSymbols", list(MarkerStore.SYMBOLS));
        o.add("projectStatus", map(ProjectStore.STATUS));
        o.add("projectPriority", map(ProjectStore.PRIORITY));
        o.add("projectVisibility", map(ProjectStore.VISIBILITY));
        JsonObject roles = new JsonObject();
        for (TeamStore.Role role : TeamStore.Role.values()) roles.addProperty(role.id(), role.label);
        o.add("teamRoles", roles);
        o.add("teamSymbols", list(TeamStore.SYMBOLS));
        o.add("tradeKinds", map(TradeStore.KINDS));
        o.add("tradeSides", map(TradeStore.SIDES));
        o.add("tradeCategories", map(s.trades().categories()));
        o.add("tradeStatus", map(TradeStore.STATUS));
        o.add("handoverStatus", map(TradeStore.HANDOVER));
        o.addProperty("tradeUnit", c.tradeUnit());
        o.addProperty("tradeMinIncrement", c.tradeMinIncrement());
        o.addProperty("maxListingDays", c.maxListingDays());
        o.addProperty("maxTeamsPerPlayer", c.maxTeamsPerPlayer());
        o.addProperty("teamInviteDays", c.teamInviteDays());
        o.addProperty("chatTeamTags", c.chatTeamTags());
        JsonObject game = new JsonObject(); // was der Server im Spiel anbietet (abschalten kann es jeder für sich)
        game.addProperty("notify", c.gameNotifications());
        game.addProperty("landEnter", c.landEnterMessages());
        game.addProperty("deathMarker", c.deathMarkers());
        game.addProperty("teamChat", c.teamChat());
        game.addProperty("statistics", c.statistics());
        game.addProperty("events", c.events());
        o.add("gameFeatures", game);
        o.add("eventKinds", map(EventStore.KINDS));
        o.add("eventVisibility", map(EventStore.VISIBILITY));
        o.add("eventAnswers", map(EventStore.ANSWERS));
        o.addProperty("maxEventsPerPlayer", c.maxEventsPerPlayer());
        JsonArray cats = new JsonArray();
        for (StatValues.Category cat : StatValues.CATEGORIES) cats.add(StatsStore.category(cat));
        o.add("statCategories", cats);
        o.add("statLabels", map(StatValues.LABELS));
        JsonObject units = new JsonObject();
        StatValues.LABELS.keySet().forEach(k -> units.addProperty(k, StatValues.unitOf(k).name().toLowerCase(java.util.Locale.ROOT)));
        o.add("statUnits", units);
        o.addProperty("timeZone", c.timeZone());
        o.addProperty("afkMinutes", c.afkMinutes());
        o.addProperty("serverTime", System.currentTimeMillis());
        return o;
    }

    /**
     * Spielerübersicht mit echten Daten: Name, Rolle, Teams (nur sichtbare), Länder, online (aus dem Server),
     * zuletzt gesehen (wird höchstens alle 10 Minuten aktualisiert – daher ungefähr).
     */
    private JsonObject people(ApiRequest r) {
        Set<String> online = new java.util.HashSet<>();
        for (JsonElement e : s.playersSnapshot().get().getAsJsonArray("players")) online.add(e.getAsJsonObject().get("uuid").getAsString());
        JsonArray arr = new JsonArray();
        for (PlayerDirectory.Entry e : s.players().entries()) {
            JsonObject p = new JsonObject();
            p.addProperty("uuid", e.uuid().toString());
            p.addProperty("name", e.name());
            p.addProperty("role", e.admin() ? "admin" : "player");
            boolean isOnline = s.config().showOnlinePlayers() && online.contains(e.uuid().toString());
            p.addProperty("online", isOnline);
            if (!isOnline) p.addProperty("lastSeen", e.lastSeen());
            p.addProperty("lands", s.lands().ownedLandCount(e.uuid()));
            p.addProperty("chunks", s.lands().ownedChunkCount(e.uuid()));
            JsonArray teams = new JsonArray();
            for (String teamId : s.teams().teamIdsOf(e.uuid())) {
                if (!s.teams().canSee(r.user, r.admin, teamId)) continue;
                JsonObject t = new JsonObject();
                t.addProperty("id", teamId);
                t.addProperty("tag", s.teams().tagOf(teamId));
                t.addProperty("name", s.teams().nameOf(teamId));
                teams.add(t);
            }
            p.add("teams", teams);
            arr.add(p);
        }
        JsonObject o = new JsonObject();
        o.add("players", arr);
        o.addProperty("onlineHidden", !s.config().showOnlinePlayers());
        return o;
    }

    private static JsonObject map(Map<String, String> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private static JsonArray list(Set<String> set) {
        JsonArray arr = new JsonArray();
        List.copyOf(new java.util.TreeSet<>(set)).forEach(arr::add);
        return arr;
    }
}
