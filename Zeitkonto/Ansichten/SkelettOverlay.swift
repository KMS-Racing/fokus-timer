import SwiftUI

/// Zeichnet die erkannten Körperpunkte über das Kamerabild.
///
/// Das ist dein wichtigstes Werkzeug zum Debuggen: Wenn hier kein Skelett
/// erscheint, ist nicht dein Zähler kaputt, sondern die Erkennung sieht dich nicht.
struct SkelettOverlay: View {

    let erkennung: Erkennung
    let uebung: Uebung

    var body: some View {
        Canvas { kontext, groesse in
            guard erkennung.bildGroesse.width > 0 else { return }

            // Die drei Gelenke, aus denen gerade der Winkel berechnet wird,
            // malen wir dicker und in Gelb.
            let wichtig = Set([uebung.gelenkeLinks.0, uebung.gelenkeLinks.1, uebung.gelenkeLinks.2,
                               uebung.gelenkeRechts.0, uebung.gelenkeRechts.1, uebung.gelenkeRechts.2])

            // 1. Knochen (Linien zwischen zwei Gelenken)
            for (von, nach) in Gelenk.knochen {
                guard let a = position(von, in: groesse),
                      let b = position(nach, in: groesse) else { continue }

                var linie = Path()
                linie.move(to: a)
                linie.addLine(to: b)
                kontext.stroke(linie,
                               with: .color(.green.opacity(0.75)),
                               lineWidth: 3)
            }

            // 2. Gelenkpunkte
            for gelenk in Gelenk.allCases {
                guard let p = position(gelenk, in: groesse) else { continue }
                let istWichtig = wichtig.contains(gelenk)
                let radius: CGFloat = istWichtig ? 9 : 5
                let kreis = Path(ellipseIn: CGRect(x: p.x - radius, y: p.y - radius,
                                                   width: radius * 2, height: radius * 2))
                kontext.fill(kreis, with: .color(istWichtig ? .yellow : .green))
            }
        }
        .allowsHitTesting(false)   // Das Overlay soll keine Tipps abfangen.
    }

    /// Rechnet einen Gelenkpunkt in Bildschirm-Koordinaten um.
    private func position(_ gelenk: Gelenk, in ansichtsGroesse: CGSize) -> CGPoint? {
        guard let vertrauen = erkennung.vertrauen[gelenk],
              vertrauen >= 0.2,
              let pixel = erkennung.pixel(gelenk) else { return nil }

        return Self.aufBildschirm(pixel: pixel,
                                  bild: erkennung.bildGroesse,
                                  ansicht: ansichtsGroesse)
    }

    /// Bildet einen Pixel des Kamerabildes auf einen Punkt der Ansicht ab.
    ///
    /// Das Kamerabild ist z. B. 720×1280, der Bildschirm aber vielleicht 393×759.
    /// Weil die Vorschau `resizeAspectFill` benutzt, wird das Bild so weit
    /// vergrößert, dass es die Ansicht KOMPLETT füllt – der Überstand wird
    /// links/rechts (oder oben/unten) abgeschnitten. Genau das rechnen wir hier nach.
    /// Würden wir das vergessen, läge das Skelett verschoben neben dem Körper.
    static func aufBildschirm(pixel: CGPoint, bild: CGSize, ansicht: CGSize) -> CGPoint {
        let skalierung = max(ansicht.width / bild.width, ansicht.height / bild.height)
        let breite = bild.width * skalierung
        let hoehe = bild.height * skalierung
        let versatzX = (ansicht.width - breite) / 2
        let versatzY = (ansicht.height - hoehe) / 2
        return CGPoint(x: pixel.x * skalierung + versatzX,
                       y: pixel.y * skalierung + versatzY)
    }
}
