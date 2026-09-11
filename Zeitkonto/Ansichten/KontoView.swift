import Combine
import SwiftData
import SwiftUI

/// Guthaben anzeigen und Minuten ausgeben (mit Countdown).
struct KontoView: View {

    @Environment(\.modelContext) private var kontext
    @Query(sort: \Eintrag.datum, order: .reverse) private var eintraege: [Eintrag]

    /// Das Ende des Countdowns als Zeitstempel.
    ///
    /// Warum speichern wir das ENDE und nicht die "restlichen Sekunden"?
    /// Weil ein Timer stehen bleibt, sobald die App in den Hintergrund geht.
    /// Ein Endzeitpunkt dagegen gilt einfach weiter – beim Zurückkommen
    /// rechnen wir neu aus, wie viel übrig ist. So kann man auch nicht
    /// schummeln, indem man die App schließt.
    @AppStorage("countdownEnde") private var countdownEnde: Double = 0

    @State private var jetzt = Date.now
    @State private var eigeneMinuten = 10

    private let takt = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var guthaben: Int { eintraege.guthaben }
    private var verbleibendeSekunden: Int {
        max(0, Int(countdownEnde - jetzt.timeIntervalSince1970))
    }
    private var countdownLaeuft: Bool { verbleibendeSekunden > 0 }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    guthabenKarte

                    if countdownLaeuft {
                        countdownKarte
                    } else {
                        ausgebenBereich
                    }
                }
                .padding()
            }
            .navigationTitle("Zeitkonto")
            .onChange(of: guthaben) { _, neu in
                // Der Stepper-Bereich darf nie kleiner als der eingestellte Wert sein.
                eigeneMinuten = min(eigeneMinuten, max(1, neu))
            }
            .onReceive(takt) { neueZeit in
                jetzt = neueZeit
                // Countdown ist natürlich abgelaufen → aufräumen.
                if countdownEnde > 0 && verbleibendeSekunden == 0 {
                    countdownEnde = 0
                }
            }
        }
    }

    // MARK: - Bausteine

    private var guthabenKarte: some View {
        VStack(spacing: 6) {
            Text("\(guthaben)")
                .font(.system(size: 90, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(guthaben > 0 ? .green : .secondary)

            Text(guthaben == 1 ? "Minute Guthaben" : "Minuten Guthaben")
                .font(.headline)
                .foregroundStyle(.secondary)

            if eintraege.heuteVerdient > 0 {
                Text("Heute verdient: \(eintraege.heuteVerdient) min")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private var countdownKarte: some View {
        VStack(spacing: 16) {
            Text("Handyzeit läuft")
                .font(.headline)

            Text(zeitText(verbleibendeSekunden))
                .font(.system(size: 64, weight: .bold, design: .rounded))
                .monospacedDigit()

            Button("Beenden und Rest zurückbuchen", role: .destructive) {
                countdownAbbrechen()
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private var ausgebenBereich: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Minuten ausgeben")
                .font(.headline)

            HStack {
                ForEach(Regeln.ausgabeVorschlaege, id: \.self) { minuten in
                    Button {
                        ausgeben(minuten)
                    } label: {
                        Text("\(minuten)")
                            .font(.title3.bold())
                            .frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(minuten > guthaben)
                }
            }

            Stepper("Eigene Anzahl: \(eigeneMinuten) min",
                    value: $eigeneMinuten,
                    in: 1...max(1, guthaben))

            Button {
                ausgeben(eigeneMinuten)
            } label: {
                Text("\(eigeneMinuten) Minuten starten")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .disabled(eigeneMinuten > guthaben)

            if guthaben == 0 {
                Text("Kein Guthaben – erst trainieren.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    // MARK: - Aktionen

    private func ausgeben(_ minuten: Int) {
        guard minuten > 0, minuten <= guthaben else { return }

        // Sofort abbuchen. Wer vorzeitig aufhört, bekommt den Rest zurück.
        kontext.insert(Eintrag(typ: .ausgegeben, minuten: minuten))
        countdownEnde = Date.now.addingTimeInterval(Double(minuten) * 60).timeIntervalSince1970
        jetzt = .now
    }

    private func countdownAbbrechen() {
        // Angefangene Minuten verfallen – nur volle Minuten gibt es zurück.
        let restMinuten = verbleibendeSekunden / 60
        if restMinuten > 0 {
            kontext.insert(Eintrag(typ: .erstattet, minuten: restMinuten))
        }
        countdownEnde = 0
    }

    private func zeitText(_ sekunden: Int) -> String {
        String(format: "%02d:%02d", sekunden / 60, sekunden % 60)
    }
}
