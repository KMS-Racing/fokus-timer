import Foundation

/// Die Übungen, die die App zählen kann.
enum Uebung: String, CaseIterable, Identifiable, Sendable {
    case liegestuetz
    case kniebeuge

    var id: String { rawValue }

    var name: String {
        switch self {
        case .liegestuetz: return "Liegestütze"
        case .kniebeuge:   return "Kniebeugen"
        }
    }

    var symbol: String {
        switch self {
        case .liegestuetz: return "figure.strengthtraining.functional"
        case .kniebeuge:   return "figure.cross.training"
        }
    }

    /// Die drei Gelenke der LINKEN Körperseite, aus denen der Winkel berechnet wird.
    /// Die Reihenfolge ist wichtig: der MITTLERE Punkt ist der Dreh-/Scheitelpunkt.
    var gelenkeLinks: (Gelenk, Gelenk, Gelenk) {
        switch self {
        case .liegestuetz: return (.schulterLinks, .ellbogenLinks, .handgelenkLinks)
        case .kniebeuge:   return (.huefteLinks, .knieLinks, .knoechelLinks)
        }
    }

    /// Dasselbe für die rechte Seite.
    var gelenkeRechts: (Gelenk, Gelenk, Gelenk) {
        switch self {
        case .liegestuetz: return (.schulterRechts, .ellbogenRechts, .handgelenkRechts)
        case .kniebeuge:   return (.huefteRechts, .knieRechts, .knoechelRechts)
        }
    }

    /// Unter diesem Winkel gilt: du bist UNTEN (gebeugt).
    var untenSchwelle: Double {
        switch self {
        case .liegestuetz: return 95
        case .kniebeuge:   return 100
        }
    }

    /// Über diesem Winkel gilt: du bist OBEN (gestreckt).
    ///
    /// Der Abstand zwischen unten- und oben-Schwelle heißt "Hysterese".
    /// Sie verhindert, dass der Zähler wild hin- und herspringt, wenn du
    /// genau an einer einzelnen Grenze zitterst.
    var obenSchwelle: Double {
        switch self {
        case .liegestuetz: return 150
        case .kniebeuge:   return 160
        }
    }

    var aufbauTipp: String {
        switch self {
        case .liegestuetz:
            return "Handy seitlich neben dich auf den Boden stellen, ca. 1,5 m Abstand, Kamera quer zu dir. Vision muss Schulter, Ellbogen und Handgelenk sehen."
        case .kniebeuge:
            return "Handy ca. 2–3 m entfernt auf Hüfthöhe aufstellen, seitlich zu dir. Vision muss Hüfte, Knie und Knöchel sehen."
        }
    }

    /// Ein Punkt zählt erst ab diesem Vertrauenswert als brauchbar.
    static let mindestVertrauen: Double = 0.3

    /// Berechnet aus einer Erkennung den aktuellen Gelenkwinkel.
    ///
    /// Wir probieren beide Körperseiten und nehmen die, bei der sich Vision
    /// sicherer ist. Denn wenn du seitlich zur Kamera liegst, ist eine Seite
    /// vom eigenen Körper verdeckt und wird nur geraten.
    func winkel(aus erkennung: Erkennung) -> Double? {
        var bester: (winkel: Double, vertrauen: Double)?

        for seite in [gelenkeLinks, gelenkeRechts] {
            guard let a = erkennung.pixel(seite.0),
                  let b = erkennung.pixel(seite.1),
                  let c = erkennung.pixel(seite.2) else { continue }

            // Der schwächste der drei Punkte bestimmt, wie gut die Messung ist.
            let sicherheit = min(erkennung.vertrauen[seite.0] ?? 0,
                                 erkennung.vertrauen[seite.1] ?? 0,
                                 erkennung.vertrauen[seite.2] ?? 0)
            guard sicherheit >= Self.mindestVertrauen else { continue }

            let grad = Winkel.zwischen(a, b, c)
            if bester == nil || sicherheit > bester!.vertrauen {
                bester = (grad, sicherheit)
            }
        }

        return bester?.winkel
    }
}
