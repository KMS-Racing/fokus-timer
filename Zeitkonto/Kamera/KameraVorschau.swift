import AVFoundation
import SwiftUI

/// Zeigt das Live-Bild der Kamera an.
///
/// SwiftUI kann das nicht von allein – das Kamerabild kommt aus einem
/// `AVCaptureVideoPreviewLayer`, und der gehört zur alten UIKit-Welt.
/// `UIViewRepresentable` ist die Brücke zwischen UIKit und SwiftUI.
struct KameraVorschau: UIViewRepresentable {

    let session: AVCaptureSession

    func makeUIView(context: Context) -> VorschauView {
        let ansicht = VorschauView()
        ansicht.vorschauEbene.session = session
        // resizeAspectFill = Bild füllt den Bildschirm, notfalls wird seitlich abgeschnitten.
        // Wichtig: genau diese Regel muss das Skelett-Overlay nachrechnen!
        ansicht.vorschauEbene.videoGravity = .resizeAspectFill
        return ansicht
    }

    func updateUIView(_ ansicht: VorschauView, context: Context) {
        ansicht.verbindungEinstellen()
    }

    /// Eine UIView, deren Hintergrund-Ebene direkt die Kamera-Vorschau ist.
    final class VorschauView: UIView {

        // Dieser Trick sorgt dafür, dass die View-Ebene selbst die Vorschau ist.
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        var vorschauEbene: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            verbindungEinstellen()
        }

        /// Dreht die Vorschau ins Hochformat – genau wie beim Kamera-Ausgang.
        func verbindungEinstellen() {
            guard let verbindung = vorschauEbene.connection else { return }

            if #available(iOS 17.0, *) {
                if verbindung.isVideoRotationAngleSupported(90) {
                    verbindung.videoRotationAngle = 90
                }
            } else if verbindung.isVideoOrientationSupported {
                verbindung.videoOrientation = .portrait
            }
            // Spiegeln übernimmt iOS automatisch passend zur Kamera –
            // dieselbe Regel wie beim Kamera-Ausgang, damit Bild und Skelett zusammenpassen.
        }
    }
}
