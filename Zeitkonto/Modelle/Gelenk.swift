import Foundation
import Vision

/// Die Körperpunkte ("Gelenke"), die wir aus dem Vision-Framework benutzen.
///
/// Warum ein eigenes enum, wenn Vision schon `VNHumanBodyPoseObservation.JointName` hat?
/// 1. Der Rest der App muss dann nichts mehr über Vision wissen.
/// 2. Deutsche Namen lesen sich für dich einfacher.
/// 3. Ein `enum` mit `String` ist automatisch `Sendable` – das braucht Swift,
///    weil die Erkennung auf einem Hintergrund-Thread läuft und das Ergebnis
///    auf den Haupt-Thread wandert.
enum Gelenk: String, CaseIterable, Sendable {
    case kopf
    case hals
    case schulterLinks, schulterRechts
    case ellbogenLinks, ellbogenRechts
    case handgelenkLinks, handgelenkRechts
    case huefteLinks, huefteRechts
    case knieLinks, knieRechts
    case knoechelLinks, knoechelRechts

    /// Der passende Name im Vision-Framework.
    var visionName: VNHumanBodyPoseObservation.JointName {
        switch self {
        case .kopf:              return .nose
        case .hals:              return .neck
        case .schulterLinks:     return .leftShoulder
        case .schulterRechts:    return .rightShoulder
        case .ellbogenLinks:     return .leftElbow
        case .ellbogenRechts:    return .rightElbow
        case .handgelenkLinks:   return .leftWrist
        case .handgelenkRechts:  return .rightWrist
        case .huefteLinks:       return .leftHip
        case .huefteRechts:      return .rightHip
        case .knieLinks:         return .leftKnee
        case .knieRechts:        return .rightKnee
        case .knoechelLinks:     return .leftAnkle
        case .knoechelRechts:    return .rightAnkle
        }
    }

    /// Welche Punkte im Overlay mit einer Linie verbunden werden ("Knochen").
    static let knochen: [(Gelenk, Gelenk)] = [
        (.hals, .kopf),
        (.schulterLinks, .schulterRechts),
        (.hals, .schulterLinks), (.hals, .schulterRechts),
        (.schulterLinks, .ellbogenLinks), (.ellbogenLinks, .handgelenkLinks),
        (.schulterRechts, .ellbogenRechts), (.ellbogenRechts, .handgelenkRechts),
        (.schulterLinks, .huefteLinks), (.schulterRechts, .huefteRechts),
        (.huefteLinks, .huefteRechts),
        (.huefteLinks, .knieLinks), (.knieLinks, .knoechelLinks),
        (.huefteRechts, .knieRechts), (.knieRechts, .knoechelRechts)
    ]
}
