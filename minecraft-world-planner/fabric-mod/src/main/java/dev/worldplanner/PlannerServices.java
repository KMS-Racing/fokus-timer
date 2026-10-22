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
        AtomicReference<Map<String, String>> detectedBlueMapIds
) {
    JsonStore[] stores() {
        return new JsonStore[]{players, sessions, discovery, lands, markers};
    }
}
