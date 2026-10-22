package dev.worldplanner;

/**
 * Ein erwarteter Fehler mit einer Meldung, die der Spieler sehen darf
 * (z. B. "Dieser Chunk gehört schon jemandem."). Der Status ist der HTTP-Code für die Webseite.
 */
final class PlannerException extends RuntimeException {
    final int status;

    PlannerException(int status, String message) {
        super(message);
        this.status = status;
    }

    static PlannerException badRequest(String message) {
        return new PlannerException(400, message);
    }

    static PlannerException forbidden(String message) {
        return new PlannerException(403, message);
    }

    static PlannerException notFound(String message) {
        return new PlannerException(404, message);
    }

    static PlannerException conflict(String message) {
        return new PlannerException(409, message);
    }
}
