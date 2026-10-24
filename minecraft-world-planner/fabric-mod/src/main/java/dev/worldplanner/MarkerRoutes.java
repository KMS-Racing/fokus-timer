package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** /api/markers – Orte und Bau-Marker. */
final class MarkerRoutes extends Routes {
    MarkerRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        MarkerStore markers = s.markers();
        if (r.is("GET", "markers")) {
            JsonObject o = new JsonObject();
            o.add("markers", markers.toApiJson(r.user, r.admin));
            return o;
        }
        if (r.is("POST", "markers")) {
            String id = markers.create(r.user, r.admin, r.body());
            if ("public".equals(r.optStr("visibility")) || (r.optStr("visibility") == null && r.bool("public", true))) {
                log(r.user, "marker.created", name(r.user) + " hat den Ort »" + r.optStr("name") + "« eingetragen.", "marker", id, "all");
            }
            return ok("id", id);
        }
        if (r.is("POST", "markers", "*", "edit")) {
            markers.update(r.user, r.admin, r.seg(1), r.body());
            return ok();
        }
        if (r.is("POST", "markers", "*", "delete")) {
            markers.delete(r.user, r.admin, r.seg(1));
            return ok();
        }
        throw unknown();
    }
}
