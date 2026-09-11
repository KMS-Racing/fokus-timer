import CoreVideo
import Vision

/// Wandelt ein Kamerabild in Körperpunkte um.
///
/// Alles hier ist `static`, es gibt also kein Objekt mit Zustand.
/// Das ist Absicht: diese Funktion läuft auf einem Hintergrund-Thread,
/// und gemeinsam genutzter Zustand zwischen Threads ist die häufigste
/// Fehlerquelle überhaupt.
enum KoerperErkennung {

    /// Analysiert genau ein Kamerabild.
    static func analysiere(_ pixelPuffer: CVPixelBuffer) -> Erkennung {
        var ergebnis = Erkennung()
        ergebnis.bildGroesse = CGSize(width: CVPixelBufferGetWidth(pixelPuffer),
                                      height: CVPixelBufferGetHeight(pixelPuffer))

        // Die Anfrage an Vision: "Finde mir die Körperhaltung eines Menschen."
        let anfrage = VNDetectHumanBodyPoseRequest()

        // Der Handler bekommt das Bild. `orientation: .up` stimmt hier, weil wir
        // die Kamera-Verbindung schon auf Hochformat gedreht haben (siehe KameraManager).
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelPuffer,
                                            orientation: .up,
                                            options: [:])

        do {
            try handler.perform([anfrage])
        } catch {
            // Ein einzelnes Bild darf ruhig mal danebengehen – nächstes Bild kommt in 1/30 Sekunde.
            return ergebnis
        }

        // Wir nehmen die erste erkannte Person.
        guard let beobachtung = anfrage.results?.first,
              let alleVisionPunkte = try? beobachtung.recognizedPoints(.all) else {
            return ergebnis
        }

        for gelenk in Gelenk.allCases {
            guard let punkt = alleVisionPunkte[gelenk.visionName] else { continue }
            // Vision markiert Punkte, die es gar nicht gefunden hat, mit confidence 0.
            guard punkt.confidence > 0 else { continue }

            ergebnis.punkte[gelenk] = punkt.location
            ergebnis.vertrauen[gelenk] = Double(punkt.confidence)
        }

        return ergebnis
    }
}
