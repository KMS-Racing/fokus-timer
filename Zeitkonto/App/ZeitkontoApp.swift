import SwiftData
import SwiftUI

@main
struct ZeitkontoApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        // Richtet die SwiftData-Datenbank ein und gibt sie an alle Views weiter.
        // Genau wie beim iRacing-Logbuch – nur mit einem anderen Modell.
        .modelContainer(for: Eintrag.self)
    }
}
