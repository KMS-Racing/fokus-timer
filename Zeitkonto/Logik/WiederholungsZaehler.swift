import Foundation

/// Zählt Wiederholungen aus einer Folge von Gelenkwinkeln.
///
/// Das Prinzip heißt "Zustandsautomat" (state machine):
/// Die App merkt sich, ob du gerade OBEN oder UNTEN bist.
/// Gezählt wird erst beim Übergang UNTEN → OBEN, also wenn eine
/// Wiederholung komplett abgeschlossen ist.
///
///   Winkel groß (gestreckt)  ──► Zustand OBEN
///   Winkel klein (gebeugt)   ──► Zustand UNTEN
///
///   UNTEN ──► OBEN  =  +1 Wiederholung
///   OBEN  ──► UNTEN =  nichts (das ist erst die halbe Bewegung)
struct WiederholungsZaehler {

    enum Phase: String {
        case unbekannt   // noch keine klare Position gesehen
        case oben        // gestreckt
        case unten       // gebeugt
    }

    let untenSchwelle: Double
    let obenSchwelle: Double

    private(set) var phase: Phase = .unbekannt
    private(set) var anzahl: Int = 0
    private var letzteWiederholung: Date = .distantPast

    init(uebung: Uebung) {
        self.untenSchwelle = uebung.untenSchwelle
        self.obenSchwelle = uebung.obenSchwelle
    }

    /// Neuen Winkel hineingeben. Gibt `true` zurück, wenn gerade eine
    /// Wiederholung fertig geworden ist (dann vibriert die App).
    @discardableResult
    mutating func aktualisiere(winkel: Double, jetzt: Date = .now) -> Bool {
        if winkel <= untenSchwelle {
            phase = .unten
            return false
        }

        if winkel >= obenSchwelle {
            // Nur zählen, wenn wir vorher wirklich unten waren.
            // Beim App-Start ist die Phase "unbekannt" – dann zählt bloßes
            // Herumstehen also nicht als Wiederholung.
            let warUnten = (phase == .unten)
            let langGenugHer = jetzt.timeIntervalSince(letzteWiederholung) >= Regeln.mindestAbstandSekunden

            phase = .oben

            if warUnten && langGenugHer {
                anzahl += 1
                letzteWiederholung = jetzt
                return true
            }
        }

        // Winkel liegt zwischen den beiden Schwellen:
        // Du bist mitten in der Bewegung – Phase bleibt einfach, wie sie war.
        return false
    }

    mutating func zuruecksetzen() {
        phase = .unbekannt
        anzahl = 0
        letzteWiederholung = .distantPast
    }
}
