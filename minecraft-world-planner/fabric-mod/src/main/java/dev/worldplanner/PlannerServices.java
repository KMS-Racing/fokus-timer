package dev.worldplanner;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Alles, was Befehle und Webseite brauchen, an einem Ort. Wird beim Serverstart erzeugt. */
record PlannerServices(
        PlannerConfig config,
        PlayerDirectory players,
        SessionStore sessions,
        DiscoveryTracker discovery,
        LandStore lands,
        MarkerStore markers,
        AtomicReference<JsonObject> worldSnapshot,
        AtomicReference<JsonObject> playersSnapshot,
        AtomicReference<Map<String, String>> detectedBlueMapIds,
        AtomicReference<JsonObject> blueMapStatus
) {
    /** Steht hier und nicht in BlueMapBridge, damit ohne installiertes BlueMap keine BlueMap-Klasse geladen wird. */
    static JsonObject blueMapUnavailable() {
        JsonObject o = new JsonObject();
        o.addProperty("available", false);
        return o;
    }

    JsonStore[] stores() {
        return new JsonStore[]{players, sessions, discovery, lands, markers};
    }
}
