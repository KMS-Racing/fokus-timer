import SwiftData
import SwiftUI

/// Zeigt alle Buchungen, nach Tagen gruppiert.
struct VerlaufView: View {

    @Environment(\.modelContext) private var kontext
    @Query(sort: \Eintrag.datum, order: .reverse) private var eintraege: [Eintrag]

    /// Einträge nach Tag gruppiert, neuester Tag zuerst.
    private var tage: [(tag: Date, eintraege: [Eintrag])] {
        let gruppen = Dictionary(grouping: eintraege) { eintrag in
            Calendar.current.startOfDay(for: eintrag.datum)
        }
        return gruppen
            .map { (tag: $0.key, eintraege: $0.value) }
            .sorted { $0.tag > $1.tag }
    }

    var body: some View {
        NavigationStack {
            Group {
                if eintraege.isEmpty {
                    ContentUnavailableView("Noch nichts passiert",
                                           systemImage: "list.bullet",
                                           description: Text("Trainiere, um Minuten zu verdienen."))
                } else {
                    List {
                        ForEach(tage, id: \.tag) { gruppe in
                            Section(tagText(gruppe.tag)) {
                                ForEach(gruppe.eintraege) { eintrag in
                                    zeile(eintrag)
                                }
                                .onDelete { indexe in
                                    loeschen(indexe, in: gruppe.eintraege)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Verlauf")
        }
    }

    private func zeile(_ eintrag: Eintrag) -> some View {
        HStack {
            Image(systemName: eintrag.typ.symbol)
                .foregroundStyle(eintrag.typ == .ausgegeben ? .red : .green)

            VStack(alignment: .leading, spacing: 2) {
                Text(eintrag.typ.beschriftung)
                if let uebung = eintrag.uebung, eintrag.wiederholungen > 0 {
                    Text("\(eintrag.wiederholungen)× \(uebung.name)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(eintrag.typ.vorzeichen > 0 ? "+" : "−")\(eintrag.minuten) min")
                    .monospacedDigit()
                    .foregroundStyle(eintrag.typ == .ausgegeben ? .red : .green)
                Text(eintrag.datum, format: .dateTime.hour().minute())
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func tagText(_ tag: Date) -> String {
        if Calendar.current.isDateInToday(tag) { return "Heute" }
        if Calendar.current.isDateInYesterday(tag) { return "Gestern" }
        return tag.formatted(.dateTime.day().month(.wide).year())
    }

    private func loeschen(_ indexe: IndexSet, in liste: [Eintrag]) {
        for index in indexe {
            kontext.delete(liste[index])
        }
    }
}
