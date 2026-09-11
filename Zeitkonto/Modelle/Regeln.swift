import Foundation

/// Alle "Spielregeln" der App an einer Stelle – damit du sie später leicht ändern kannst.
enum Regeln {

    /// Wie viele Minuten Handyzeit eine Wiederholung wert ist.
    ///
    /// EHRLICHE ANMERKUNG (du wolltest, dass ich sowas sage):
    /// 1 Wiederholung = 1 Minute ist sehr großzügig. 40 Kniebeugen dauern
    /// vielleicht 2 Minuten und geben dir 40 Minuten Handyzeit – der Kurs ist
    /// also ungefähr 1:20. Außerdem sind Kniebeugen deutlich leichter als
    /// Liegestütze, geben hier aber gleich viel.
    /// Für Version 1 lassen wir es trotzdem so, wie du es geplant hast.
    /// Wenn es dir zu leicht wird: hier einfach pro Übung einen anderen Wert
    /// zurückgeben, z. B. Liegestütz 1.0 und Kniebeuge 0.5.
    static func minuten(fuer wiederholungen: Int, uebung: Uebung) -> Int {
        switch uebung {
        case .liegestuetz: return wiederholungen * 1
        case .kniebeuge:   return wiederholungen * 1
        }
    }

    /// Die Vorschläge im "Minuten ausgeben"-Bildschirm.
    static let ausgabeVorschlaege = [5, 10, 15, 30]

    /// Kürzester Abstand zwischen zwei gezählten Wiederholungen (in Sekunden).
    /// Schützt gegen Doppelzählungen durch zappelnde Erkennung.
    static let mindestAbstandSekunden: TimeInterval = 0.5
}
