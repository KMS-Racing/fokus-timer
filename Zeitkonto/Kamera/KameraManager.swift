import AVFoundation
import Foundation

/// Kümmert sich um alles rund um die Kamera:
/// Erlaubnis holen, Kamera starten/stoppen, und jedes Bild an Vision weitergeben.
///
/// `@unchecked Sendable`: Diese Klasse wird von zwei Threads benutzt
/// (Haupt-Thread startet/stoppt, Kamera-Thread liefert Bilder).
/// Wir versprechen Swift damit, selbst aufzupassen – deshalb wird
/// `beiErkennung` auch NUR vom Haupt-Thread gesetzt, und zwar einmal beim Start.
final class KameraManager: NSObject, @unchecked Sendable {

    /// Die Session ist das "Rohr", durch das Kamerabilder fließen.
    /// Die Vorschau-Ansicht hängt sich an dieselbe Session.
    let session = AVCaptureSession()

    /// Wird für jedes analysierte Bild aufgerufen – garantiert auf dem Haupt-Thread.
    var beiErkennung: (@MainActor (Erkennung) -> Void)?

    private let videoAusgabe = AVCaptureVideoDataOutput()
    /// Eigene Warteschlange, damit die Kamera-Arbeit nicht die Oberfläche blockiert.
    private let kameraQueue = DispatchQueue(label: "de.zeitkonto.kamera", qos: .userInitiated)
    private var position: AVCaptureDevice.Position = .front
    private var eingerichtet = false

    // MARK: - Erlaubnis

    /// Fragt den Nutzer nach Kamera-Erlaubnis (beim ersten Mal erscheint der Systemdialog).
    func erlaubnisAnfragen() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .video)
        default:
            // abgelehnt oder gesperrt – da hilft nur die Einstellungen-App
            return false
        }
    }

    // MARK: - Start / Stopp

    func starten() {
        kameraQueue.async { [self] in
            if !eingerichtet {
                einrichten()
                eingerichtet = true
            }
            if !session.isRunning {
                session.startRunning()
            }
        }
    }

    func stoppen() {
        kameraQueue.async { [self] in
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    /// Vorder- und Rückkamera tauschen.
    func kameraWechseln() {
        kameraQueue.async { [self] in
            position = (position == .front) ? .back : .front
            session.beginConfiguration()
            for eingang in session.inputs { session.removeInput(eingang) }
            eingangHinzufuegen()
            session.commitConfiguration()
            verbindungEinstellen()
        }
    }

    // MARK: - Aufbau

    private func einrichten() {
        session.beginConfiguration()

        // 1280×720 reicht für Vision völlig und ist deutlich schneller als 4K.
        if session.canSetSessionPreset(.hd1280x720) {
            session.sessionPreset = .hd1280x720
        }

        eingangHinzufuegen()

        // Ausgang: wir wollen jedes einzelne Bild als Daten bekommen.
        videoAusgabe.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        // Wenn die Analyse mal länger dauert, lieber Bilder wegwerfen als hinterherhinken.
        videoAusgabe.alwaysDiscardsLateVideoFrames = true
        videoAusgabe.setSampleBufferDelegate(self, queue: kameraQueue)

        if session.canAddOutput(videoAusgabe) {
            session.addOutput(videoAusgabe)
        }

        session.commitConfiguration()
        verbindungEinstellen()
    }

    private func eingangHinzufuegen() {
        guard let geraet = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                   for: .video,
                                                   position: position),
              let eingang = try? AVCaptureDeviceInput(device: geraet),
              session.canAddInput(eingang) else { return }
        session.addInput(eingang)
    }

    /// Dreht und spiegelt die Bilder schon in der Kamera – dann kommt bei Vision
    /// ein aufrechtes Hochformat-Bild an und wir müssen später nichts umrechnen.
    private func verbindungEinstellen() {
        guard let verbindung = videoAusgabe.connection(with: .video) else { return }

        if #available(iOS 17.0, *) {
            // 90° = Hochformat
            if verbindung.isVideoRotationAngleSupported(90) {
                verbindung.videoRotationAngle = 90
            }
        } else if verbindung.isVideoOrientationSupported {
            verbindung.videoOrientation = .portrait
        }

        // Frontkamera spiegeln, damit es sich anfühlt wie ein Spiegel.
        if verbindung.isVideoMirroringSupported {
            verbindung.automaticallyAdjustsVideoMirroring = false
            verbindung.isVideoMirrored = (position == .front)
        }
    }
}

// MARK: - Hier kommen die Kamerabilder an

extension KameraManager: AVCaptureVideoDataOutputSampleBufferDelegate {

    /// Wird ca. 30× pro Sekunde aufgerufen – auf `kameraQueue`, NICHT auf dem Haupt-Thread.
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {

        guard let pixelPuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        // Die eigentliche Arbeit: Körperpunkte suchen. Das dauert ein paar
        // Millisekunden und darf deshalb nicht auf dem Haupt-Thread passieren.
        let erkennung = KoerperErkennung.analysiere(pixelPuffer)

        // Alles, was die Oberfläche verändert, MUSS auf den Haupt-Thread zurück.
        Task { @MainActor [weak self] in
            self?.beiErkennung?(erkennung)
        }
    }
}
