import Observation
import SwiftUI
import UIKit

/// Das "Gehirn" des Trainings-Bildschirms.
///
/// Es verbindet drei Dinge:
///   Kamera  →  Vision (Körperpunkte)  →  Winkel  →  Zähler
///
/// `@MainActor` heißt: alles hier läuft garantiert auf dem Haupt-Thread.
/// `@Observable` heißt: SwiftUI zeichnet automatisch neu, wenn sich etwas ändert.
@MainActor
@Observable
final class TrainingModell {

    // MARK: - Was die Oberfläche anzeigt

    var uebung: Uebung = .liegestuetz {
        didSet {
            guard oldValue != uebung else { return }
            zaehler = WiederholungsZaehler(uebung: uebung)
            wiederholungen = 0
            geglaetteterWinkel = nil
        }
    }

    private(set) var erkennung = Erkennung()
    private(set) var geglaetteterWinkel: Double?
    private(set) var wiederholungen: Int = 0
    private(set) var laeuft: Bool = false
    /// nil = noch nicht gefragt, true/false = Antwort des Nutzers
    private(set) var kameraErlaubt: Bool?

    var personGefunden: Bool { erkennung.personGefunden }
    var phase: WiederholungsZaehler.Phase { zaehler.phase }

    /// Minuten, die dieses Training gerade wert ist.
    var verdienteMinuten: Int { Regeln.minuten(fuer: wiederholungen, uebung: uebung) }

    let kamera = KameraManager()

    // MARK: - Interner Zustand

    private var zaehler = WiederholungsZaehler(uebung: .liegestuetz)
    private let vibration = UIImpactFeedbackGenerator(style: .heavy)

    init() {
        // Die Kamera meldet jedes analysierte Bild hierher zurück.
        kamera.beiErkennung = { [weak self] erkennung in
            self?.neuesBild(erkennung)
        }
    }

    // MARK: - Kamera an/aus

    func kameraStarten() async {
        let erlaubt = await kamera.erlaubnisAnfragen()
        kameraErlaubt = erlaubt
        guard erlaubt else { return }
        vibration.prepare()
        kamera.starten()
    }

    func kameraStoppen() {
        kamera.stoppen()
        laeuft = false
    }

    // MARK: - Training steuern

    func trainingStarten() {
        zaehler.zuruecksetzen()
        wiederholungen = 0
        laeuft = true
        vibration.prepare()
    }

    func trainingPausieren() {
        laeuft = false
    }

    func zuruecksetzen() {
        zaehler.zuruecksetzen()
        wiederholungen = 0
        geglaetteterWinkel = nil
    }

    // MARK: - Herzstück: ein neues Bild ist da

    private func neuesBild(_ neueErkennung: Erkennung) {
        erkennung = neueErkennung

        // 1. Winkel aus den Gelenkpunkten berechnen
        guard let roherWinkel = uebung.winkel(aus: neueErkennung) else {
            // Keine brauchbaren Punkte – z. B. du bist aus dem Bild gelaufen.
            geglaetteterWinkel = nil
            return
        }

        // 2. Glätten, damit einzelne Ausreißer nicht sofort zählen
        let winkel = Winkel.geglaettet(alt: geglaetteterWinkel, neu: roherWinkel)
        geglaetteterWinkel = winkel

        // 3. Nur zählen, wenn das Training wirklich läuft
        guard laeuft else { return }

        if zaehler.aktualisiere(winkel: winkel) {
            wiederholungen = zaehler.anzahl
            vibration.impactOccurred()
            vibration.prepare()   // für die nächste Wiederholung vorwärmen
        }
    }
}
