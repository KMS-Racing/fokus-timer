import Foundation
import SwiftData

/// Ob bei einem Eintrag Minuten dazukommen oder weggehen.
enum Eintragstyp: String, Codable, CaseIterable {
    case verdient     // durch Training bekommen
    case ausgegeben   // für Handyzeit ausgegeben
    case erstattet    // Countdown vorzeitig beendet → Rest zurück

    /// +1 oder -1, damit man das Guthaben einfach aufsummieren kann.
    var vorzeichen: Int {
        switch self {
        case .verdient, .erstattet: return 1
        case .ausgegeben:           return -1
        }
    }

    var beschriftung: String {
        switch self {
        case .verdient:   return "Verdient"
        case .ausgegeben: return "Ausgegeben"
        case .erstattet:  return "Zurück"
        }
    }

    var symbol: String {
        switch self {
        case .verdient:   return "plus.circle.fill"
        case .ausgegeben: return "minus.circle.fill"
        case .erstattet:  return "arrow.uturn.backward.circle.fill"
        }
    }
}

/// Ein einzelner Vorgang auf dem Zeitkonto – so wie eine Zeile im Kontoauszug.
///
/// Wir speichern KEIN Guthaben-Feld. Das Guthaben wird immer aus allen
/// Einträgen zusammengerechnet. Vorteil: es kann nie "auseinanderlaufen",
/// wenn mal ein Eintrag gelöscht wird.
@Model
final class Eintrag {
    var datum: Date = Date.now
    /// Der Typ als Text gespeichert – SwiftData mag einfache Typen am liebsten.
    var typRaw: String = Eintragstyp.verdient.rawValue
    /// Immer eine positive Zahl. Das Vorzeichen steckt im Typ.
    var minuten: Int = 0
    var wiederholungen: Int = 0
    var uebungRaw: String?

    init(typ: Eintragstyp,
         minuten: Int,
         wiederholungen: Int = 0,
         uebung: Uebung? = nil,
         datum: Date = .now) {
        self.datum = datum
        self.typRaw = typ.rawValue
        self.minuten = max(0, minuten)
        self.wiederholungen = wiederholungen
        self.uebungRaw = uebung?.rawValue
    }

    var typ: Eintragstyp { Eintragstyp(rawValue: typRaw) ?? .verdient }
    var uebung: Uebung? { uebungRaw.flatMap { Uebung(rawValue: $0) } }
}

extension Array where Element == Eintrag {
    /// Aktuelles Guthaben in Minuten.
    var guthaben: Int {
        reduce(0) { summe, eintrag in
            summe + eintrag.typ.vorzeichen * eintrag.minuten
        }
    }

    /// Wie viele Minuten heute verdient wurden.
    var heuteVerdient: Int {
        filter { Calendar.current.isDateInToday($0.datum) && $0.typ == .verdient }
            .reduce(0) { $0 + $1.minuten }
    }
}
