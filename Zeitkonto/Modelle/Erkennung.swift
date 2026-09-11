import CoreGraphics

/// Das Ergebnis EINES Kamerabildes: wo ist welches Gelenk, und wie sicher ist sich Vision.
///
/// Wichtig zu den Koordinaten:
/// Vision liefert Punkte "normalisiert", also immer zwischen 0 und 1 –
/// unabhängig davon, wie groß das Kamerabild wirklich ist.
/// ACHTUNG: Bei Vision liegt (0,0) UNTEN LINKS und y zeigt nach OBEN.
/// Bei SwiftUI liegt (0,0) OBEN LINKS und y zeigt nach UNTEN.
/// Deshalb muss y einmal umgedreht werden – das macht `pixel(_:)`.
struct Erkennung: Sendable {
    /// Normalisierte Vision-Punkte (0…1, y zeigt nach oben).
    var punkte: [Gelenk: CGPoint] = [:]
    /// Wie sicher sich Vision bei jedem Punkt ist (0…1).
    var vertrauen: [Gelenk: Double] = [:]
    /// Größe des Kamerabildes in Pixeln, z. B. 720 × 1280.
    var bildGroesse: CGSize = .zero

    /// Wurde überhaupt eine Person gefunden?
    var personGefunden: Bool { !punkte.isEmpty }

    /// Der Punkt in Bild-Pixeln, mit y nach UNTEN (wie in SwiftUI).
    ///
    /// Das ist nicht nur Kosmetik: Winkel darf man NICHT in normalisierten
    /// Koordinaten rechnen. Bei einem 720×1280-Bild wäre sonst ein Schritt von
    /// 0.1 nach rechts viel kürzer als 0.1 nach oben – der Winkel käme falsch raus.
    func pixel(_ gelenk: Gelenk) -> CGPoint? {
        guard let p = punkte[gelenk], bildGroesse.width > 0, bildGroesse.height > 0 else { return nil }
        return CGPoint(x: p.x * bildGroesse.width,
                       y: (1 - p.y) * bildGroesse.height)
    }
}
