import SwiftUI

/// Die drei Tabs der App.
struct ContentView: View {
    var body: some View {
        TabView {
            TrainingView()
                .tabItem { Label("Training", systemImage: "figure.run") }

            KontoView()
                .tabItem { Label("Konto", systemImage: "clock.badge.checkmark") }

            VerlaufView()
                .tabItem { Label("Verlauf", systemImage: "list.bullet.rectangle") }
        }
    }
}
