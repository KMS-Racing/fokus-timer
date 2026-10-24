package dev.worldplanner;

import com.flowpowered.math.vector.Vector2d;
import com.google.gson.JsonObject;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Zeichnet Länder und öffentliche Marker in BlueMap ein.
 * Diese Klasse wird nur geladen, wenn BlueMap installiert ist – sonst gäbe es einen Absturz,
 * weil die BlueMap-Klassen fehlen.
 */
final class BlueMapBridge {
    private static final String LAND_SET_ID = "world_planner_lands";
    private static final String MARKER_SET_ID = "world_planner_markers";
    private static final float SHAPE_HEIGHT = 64f;
    private static final double DEFAULT_MARKER_Y = 64;

    private final Logger logger;
    private final LandStore lands;
    private final MarkerStore markers;
    private final TeamStore teams;
    private final PlayerDirectory players;
    private final AtomicReference<Map<String, String>> detectedMapIds;
    private final AtomicReference<JsonObject> status;
    private final Consumer<BlueMapAPI> onEnable = api -> this.api = api;
    private final Consumer<BlueMapAPI> onDisable = api -> this.api = null;
    private volatile BlueMapAPI api;
    private BlueMapAPI drawnFor;
    private long drawnLandVersion = -1;
    private long drawnMarkerVersion = -1;
    private long drawnTeamVersion = -1;

    BlueMapBridge(Logger logger, PlannerServices s) {
        this.logger = logger;
        this.lands = s.lands();
        this.markers = s.markers();
        this.teams = s.teams();
        this.players = s.players();
        this.detectedMapIds = s.detectedBlueMapIds();
        this.status = s.blueMapStatus();
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    /** Wird einmal pro Sekunde auf dem Server-Thread aufgerufen. Zeichnet nur neu, wenn sich etwas geändert hat. */
    void tick(MinecraftServer server) {
        BlueMapAPI current = api;
        updateStatus(current);
        if (current == null) return;
        long landVersion = lands.version();
        long markerVersion = markers.version();
        long teamVersion = teams.version(); // Team-Kürzel stehen in den Land-Details
        if (current == drawnFor && landVersion == drawnLandVersion && markerVersion == drawnMarkerVersion
                && teamVersion == drawnTeamVersion) return;
        try {
            redraw(current, server);
            drawnFor = current;
            drawnLandVersion = landVersion;
            drawnMarkerVersion = markerVersion;
            drawnTeamVersion = teamVersion;
        } catch (Exception e) {
            logger.warn("[World Planner] BlueMap-Marker konnten nicht aktualisiert werden: {}", e.toString());
            drawnFor = null; // beim nächsten Mal erneut versuchen
        }
    }

    private void redraw(BlueMapAPI api, MinecraftServer server) {
        Map<String, MarkerSet> landSets = new HashMap<>();
        Map<String, MarkerSet> markerSets = new HashMap<>();

        for (LandStore.LandView land : lands.views()) {
            MarkerSet set = landSets.computeIfAbsent(land.dimension(), d -> newSet("Länder", false));
            Color line = color(land.color(), 1f);
            Color fill = color(land.color(), 0.25f);
            List<String> ownerNames = new ArrayList<>();
            land.owners().forEach(u -> ownerNames.add(players.name(u)));
            String detail = "<b>" + html(land.name()) + "</b><br>Besitzer: " + html(String.join(", ", ownerNames))
                    + teamLine(land.teamId())
                    + "<br>" + land.chunks().length + (land.chunks().length == 1 ? " Chunk" : " Chunks")
                    + (land.description().isEmpty() ? "" : "<br><i>" + html(land.description()) + "</i>")
                    + "<br><small>Geplantes Gebiet – kein Schutz im Spiel</small>";
            int part = 0;
            for (LandOutline.Polygon polygon : LandOutline.of(land.chunks())) {
                ShapeMarker.Builder builder = ShapeMarker.builder()
                        .label(plain(land.name()))
                        .shape(shape(polygon.outer()), SHAPE_HEIGHT)
                        .lineColor(line)
                        .fillColor(fill)
                        .lineWidth(3)
                        .depthTestEnabled(false)
                        .detail(detail);
                for (List<int[]> hole : polygon.holes()) builder.holes(shape(hole));
                ShapeMarker marker = builder.centerPosition().build();
                set.put("land-" + land.id() + "-" + part++, marker);
            }
        }

        // Nur öffentliche Marker: BlueMap ist oft ohne Anmeldung erreichbar.
        Map<String, String> types = markers.types();
        for (MarkerStore.Marker m : markers.publicMarkers()) {
            MarkerSet set = markerSets.computeIfAbsent(m.dimension(), d -> newSet("Bau-Planer", false));
            String detail = "<b>" + html(m.name()) + "</b><br>" + html(types.getOrDefault(m.type(), "Sonstiges"))
                    + " · von " + html(players.name(m.owner()))
                    + (m.y() == null ? "<br><small>Höhe nicht angegeben</small>" : "")
                    + (m.notes().isEmpty() ? "" : "<br>" + html(m.notes()));
            // Ohne Höhenangabe auf Meereshöhe zeichnen (die echte Oberfläche zu suchen hieße, Chunks zu laden).
            double y = m.y() == null ? DEFAULT_MARKER_Y : m.y();
            set.put("marker-" + m.id(), POIMarker.builder()
                    .label(plain((m.symbol() == null ? "" : m.symbol() + " ") + m.name()))
                    .position(m.x() + 0.5, y, m.z() + 0.5)
                    .detail(detail)
                    .defaultIcon()
                    .build());
        }

        Map<String, String> mapIds = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().identifier().toString();
            MarkerSet landSet = landSets.getOrDefault(dimension, newSet("Länder", false));
            MarkerSet markerSet = markerSets.getOrDefault(dimension, newSet("Bau-Planer", false));
            api.getWorld(level).ifPresent(world -> world.getMaps().forEach(map -> {
                mapIds.putIfAbsent(dimension, map.getId()); // für "In 3D zeigen" auf der Webseite
                map.getMarkerSets().put(LAND_SET_ID, landSet);
                map.getMarkerSets().put(MARKER_SET_ID, markerSet);
            }));
        }
        detectedMapIds.set(Map.copyOf(mapIds));
    }

    /**
     * Wie weit ist BlueMap mit dem Rendern? Die Webseite zeigt das an, damit schwarze Flächen
     * nicht wie ein Fehler aussehen. (BlueMap verrät nur die Länge der Warteschlange, nicht welche Gebiete fertig sind.)
     */
    private void updateStatus(BlueMapAPI current) {
        if (current == null) {
            status.set(PlannerServices.blueMapUnavailable());
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("available", true);
        try {
            int queue = current.getRenderManager().renderQueueSize();
            o.addProperty("renderQueue", queue);
            o.addProperty("renderThreads", current.getRenderManager().renderThreadCount());
            o.addProperty("rendering", current.getRenderManager().isRunning() && queue > 0);
            o.addProperty("paused", !current.getRenderManager().isRunning());
        } catch (Exception e) {
            o.addProperty("renderQueue", -1);
        }
        status.set(o);
    }

    void close() {
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
    }

    private static MarkerSet newSet(String label, boolean hidden) {
        return MarkerSet.builder().label(label).toggleable(true).defaultHidden(hidden).build();
    }

    private static Shape shape(List<int[]> ring) {
        List<Vector2d> points = new ArrayList<>(ring.size());
        for (int[] p : ring) points.add(new Vector2d(p[0] * 16.0, p[1] * 16.0));
        return new Shape(points);
    }

    private static Color color(String hex, float alpha) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
    }

    /** Private Teams bleiben in BlueMap unsichtbar – dort kann jeder mitlesen. */
    private String teamLine(String teamId) {
        if (teamId == null || !teams.exists(teamId) || !teams.isPublic(teamId)) return "";
        return "<br>Team: [" + html(teams.tagOf(teamId)) + "] " + html(teams.nameOf(teamId));
    }

    /** Für Beschriftungen: HTML-Sonderzeichen ganz entfernen. */
    static String plain(String s) {
        return s.replaceAll("[<>&\"']", "");
    }

    /** Wichtig gegen eingeschleusten Code: Spielertexte niemals ungefiltert als HTML ausgeben. */
    static String html(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
