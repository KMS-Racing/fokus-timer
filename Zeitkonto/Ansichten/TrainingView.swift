import SwiftData
import SwiftUI
import UIKit

/// Der Trainings-Bildschirm: Kamera, Skelett, Zähler.
struct TrainingView: View {

    @Environment(\.modelContext) private var kontext
    @State private var modell = TrainingModell()
    @State private var gutschrift: Int = 0
    @State private var zeigeGutschrift = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            switch modell.kameraErlaubt {
            case .some(true):
                KameraVorschau(session: modell.kamera.session)
                    .ignoresSafeArea()

                SkelettOverlay(erkennung: modell.erkennung, uebung: modell.uebung)
                    .ignoresSafeArea()

                bedienung

            case .some(false):
                keineErlaubnis

            case nil:
                ProgressView()
                    .tint(.white)
            }
        }
        .task {
            await modell.kameraStarten()
        }
        .onAppear {
            // Während des Trainings darf sich der Bildschirm nicht ausschalten.
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            modell.kameraStoppen()
        }
        .alert("Gutgeschrieben", isPresented: $zeigeGutschrift) {
            Button("Super", role: .cancel) { }
        } message: {
            Text("\(gutschrift) Minuten sind auf deinem Zeitkonto.")
        }
    }

    // MARK: - Bedienelemente über dem Kamerabild

    private var bedienung: some View {
        VStack(spacing: 0) {
            uebungsWahl
            statusZeile

            Spacer()

            zaehlerAnzeige

            Spacer()

            knoepfe
        }
        .padding()
    }

    private var uebungsWahl: some View {
        Picker("Übung", selection: Binding(
            get: { modell.uebung },
            set: { modell.uebung = $0 }
        )) {
            ForEach(Uebung.allCases) { uebung in
                Text(uebung.name).tag(uebung)
            }
        }
        .pickerStyle(.segmented)
        .disabled(modell.laeuft)   // mitten im Satz die Übung wechseln wäre Unsinn
    }

    /// Zeigt dir beim Debuggen, was die Erkennung gerade sieht.
    private var statusZeile: some View {
        HStack(spacing: 12) {
            Label(modell.personGefunden ? "Erkannt" : "Keine Person",
                  systemImage: modell.personGefunden ? "figure.stand" : "eye.slash")
                .foregroundStyle(modell.personGefunden ? .green : .orange)

            if let winkel = modell.geglaetteterWinkel {
                Text("\(Int(winkel))°")
                    .monospacedDigit()
                Text(modell.phase.rawValue)
                    .foregroundStyle(.secondary)
            } else {
                Text("kein Winkel")
                    .foregroundStyle(.secondary)
            }
        }
        .font(.footnote)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.black.opacity(0.55), in: Capsule())
        .foregroundStyle(.white)
        .padding(.top, 8)
    }

    private var zaehlerAnzeige: some View {
        VStack(spacing: 4) {
            Text("\(modell.wiederholungen)")
                .font(.system(size: 130, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white)
                .shadow(radius: 12)

            Text("= \(modell.verdienteMinutenText)")
                .font(.headline)
                .foregroundStyle(.white.opacity(0.85))

            if !modell.laeuft {
                Text(modell.uebung.aufbauTipp)
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white.opacity(0.7))
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
            }
        }
    }

    private var knoepfe: some View {
        HStack(spacing: 12) {
            Button {
                modell.kamera.kameraWechseln()
            } label: {
                Image(systemName: "arrow.triangle.2.circlepath.camera")
                    .font(.title2)
                    .frame(width: 56, height: 56)
                    .background(.black.opacity(0.55), in: Circle())
            }

            Button {
                if modell.laeuft {
                    modell.trainingPausieren()
                } else {
                    modell.trainingStarten()
                }
            } label: {
                Text(modell.laeuft ? "Pause" : "Start")
                    .font(.title3.bold())
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(modell.laeuft ? Color.orange : Color.green,
                                in: RoundedRectangle(cornerRadius: 16))
            }

            Button {
                gutschreiben()
            } label: {
                Text("Fertig")
                    .font(.title3.bold())
                    .frame(width: 96, minHeight: 56)
                    .background(.blue, in: RoundedRectangle(cornerRadius: 16))
            }
            .disabled(modell.wiederholungen == 0)
            .opacity(modell.wiederholungen == 0 ? 0.4 : 1)
        }
        .foregroundStyle(.white)
    }

    private var keineErlaubnis: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.fill").font(.largeTitle)
            Text("Die App braucht die Kamera, um Wiederholungen zu zählen.")
                .multilineTextAlignment(.center)
            Button("Einstellungen öffnen") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.borderedProminent)
        }
        .foregroundStyle(.white)
        .padding(32)
    }

    // MARK: - Speichern

    private func gutschreiben() {
        let minuten = modell.verdienteMinuten
        guard minuten > 0 else { return }

        let eintrag = Eintrag(typ: .verdient,
                              minuten: minuten,
                              wiederholungen: modell.wiederholungen,
                              uebung: modell.uebung)
        kontext.insert(eintrag)

        modell.trainingPausieren()
        modell.zuruecksetzen()
        gutschrift = minuten
        zeigeGutschrift = true
    }
}

private extension TrainingModell {
    var verdienteMinutenText: String {
        verdienteMinuten == 1 ? "1 Minute" : "\(verdienteMinuten) Minuten"
    }
}
