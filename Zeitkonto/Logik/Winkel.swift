import CoreGraphics
import Foundation

/// Winkel-Mathematik für die Wiederholungs-Zählung.
enum Winkel {

    /// Der Winkel im Punkt `b`, zwischen den Strecken b→a und b→c, in Grad (0…180).
    ///
    /// Beispiel Liegestütz:
    ///   a = Schulter, b = Ellbogen, c = Handgelenk
    ///   Arm gestreckt  → ca. 170°
    ///   unten am Boden → ca. 70–90°
    ///
    /// Wie es funktioniert:
    /// 1. Wir bauen zwei Vektoren: v1 = a - b und v2 = c - b.
    ///    (Ein Vektor ist einfach "wie weit nach rechts, wie weit nach unten".)
    /// 2. atan2(y, x) gibt für jeden Vektor seine Richtung als Winkel zurück.
    /// 3. Die Differenz der beiden Richtungen ist der gesuchte Winkel.
    /// 4. Wir normieren das Ergebnis auf 0…180, weil uns egal ist,
    ///    ob der Arm nach links oder rechts gebeugt ist.
    static func zwischen(_ a: CGPoint, _ b: CGPoint, _ c: CGPoint) -> Double {
        let winkel1 = atan2(Double(a.y - b.y), Double(a.x - b.x))
        let winkel2 = atan2(Double(c.y - b.y), Double(c.x - b.x))

        var grad = (winkel1 - winkel2) * 180 / .pi

        // auf 0…360 bringen
        if grad < 0 { grad += 360 }
        // und dann auf 0…180 spiegeln
        if grad > 180 { grad = 360 - grad }

        return grad
    }

    /// Glättet Messwerte, damit der Winkel nicht zappelt.
    ///
    /// Vision ist nie perfekt: von Bild zu Bild springt ein Punkt um ein paar Pixel.
    /// Ohne Glättung würde der Zähler dadurch manchmal doppelt zählen.
    /// `staerke` = 0 heißt "gar nicht glätten", 0.8 heißt "stark glätten, aber träge".
    static func geglaettet(alt: Double?, neu: Double, staerke: Double = 0.6) -> Double {
        guard let alt else { return neu }
        return alt * staerke + neu * (1 - staerke)
    }
}
