import SwiftUI
import UniformTypeIdentifiers

struct EditView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let existing: Habit?

    @State private var name: String
    @State private var emoji: String
    @State private var kind: Kind
    @State private var time: Date
    @State private var goalText: String
    @State private var unit: String
    @State private var stepText: String
    @State private var days: Int
    @State private var notes: String
    @State private var showErrors = false

    init(existing: Habit?) {
        self.existing = existing
        _name = State(initialValue: existing?.name ?? "")
        _emoji = State(initialValue: existing?.emoji ?? "🎯")
        _kind = State(initialValue: existing?.kindValue ?? .CHECK)
        let m = existing?.timeMinutes ?? 420
        _time = State(initialValue: Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date())
        _goalText = State(initialValue: existing.map { Amounts.format($0.goalValue) } ?? "")
        _unit = State(initialValue: existing?.unit ?? "")
        _stepText = State(initialValue: existing.map { Amounts.format($0.step) } ?? "")
        _days = State(initialValue: existing?.days ?? Days.all)
        _notes = State(initialValue: existing?.notes ?? "")
    }

    private var minutes: Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }
    private var goalValue: Double? { Amounts.parse(goalText) }
    private var stepValue: Double? { stepText.trimmingCharacters(in: .whitespaces).isEmpty ? 1 : Amounts.parse(stepText) }
    private var invalid: Bool {
        name.trimmingCharacters(in: .whitespaces).isEmpty || days == 0 || (kind == .AMOUNT && (goalValue == nil || stepValue == nil))
    }

    private func use(_ t: Template) {
        name = t.name
        emoji = t.emoji
        kind = t.kind
        if t.kind == .BY_TIME { time = Calendar.current.date(bySettingHour: t.timeMinutes / 60, minute: t.timeMinutes % 60, second: 0, of: Date()) ?? time }
        goalText = t.kind == .AMOUNT ? Amounts.format(t.goalValue) : ""
        unit = t.unit
        stepText = ""
        days = t.days
    }

    private func save() {
        if invalid {
            showErrors = true
            return
        }
        var t = existing ?? Habit(name: "")
        t.name = String(name.trimmingCharacters(in: .whitespaces).prefix(60))
        t.emoji = emoji
        t.kind = kind.rawValue
        t.timeMinutes = minutes
        if kind == .AMOUNT { t.goalValue = goalValue ?? 1 }
        t.unit = String(unit.trimmingCharacters(in: .whitespaces).prefix(20))
        t.step = stepValue ?? 1
        t.days = days
        t.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        store.save(t)
        dismiss()
    }

    var body: some View {
        NavigationStack {
            Form {
                if existing == nil {
                    Section("Start from") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(Templates.all, id: \.name) { t in
                                    Button("\(t.emoji) \(t.name)") { use(t) }.buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                }
                Section {
                    TextField("Target, e.g. Wake up", text: $name)
                    if showErrors && name.trimmingCharacters(in: .whitespaces).isEmpty { Text("Give it a name").font(.caption).foregroundColor(.red) }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 8), spacing: 8) {
                        ForEach(Templates.emojis, id: \.self) { e in
                            Text(e).font(.title3).frame(width: 36, height: 36)
                                .background(Circle().fill(e == emoji ? Color.accentColor.opacity(0.25) : Color(.secondarySystemBackground)))
                                .onTapGesture { emoji = e }
                        }
                    }
                }
                Section {
                    Picker("How it's met", selection: $kind) {
                        ForEach(Kind.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    switch kind {
                    case .BY_TIME:
                        DatePicker("By", selection: $time, displayedComponents: .hourAndMinute)
                    case .AMOUNT:
                        HStack {
                            TextField("Target amount", text: $goalText).keyboardType(.decimalPad)
                            TextField("Unit, e.g. km", text: $unit)
                        }
                        HStack {
                            Text("Each + adds")
                            Spacer()
                            TextField("1", text: $stepText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                        }
                        if showErrors && (goalValue == nil || stepValue == nil) { Text("Use a number above 0").font(.caption).foregroundColor(.red) }
                    case .CHECK:
                        EmptyView()
                    }
                } footer: {
                    Text(kind.description + (kind == .AMOUNT ? ". On iPhone you add amounts yourself; Samsung Health filling is on Android." : "."))
                }
                Section {
                    Picker("Days", selection: $days) {
                        Text("Every day").tag(Days.all)
                        Text("Weekdays").tag(Days.weekdays)
                        Text("Weekends").tag(Days.weekend)
                        if ![Days.all, Days.weekdays, Days.weekend].contains(days) { Text("Custom").tag(days) }
                    }
                    HStack {
                        ForEach(0..<7, id: \.self) { i in
                            let on = Days.has(days, i)
                            Text(String(Days.names[i].prefix(2))).font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, minHeight: 36)
                                .background(Circle().fill(on ? Color.accentColor : Color(.secondarySystemBackground)))
                                .foregroundColor(on ? .white : .secondary)
                                .onTapGesture { days ^= Days.bit(i) }
                        }
                    }
                    if showErrors && days == 0 { Text("Choose at least one day").font(.caption).foregroundColor(.red) }
                } header: {
                    Text("Which days")
                } footer: {
                    Text("Other days are rest days: they don't count and don't break a streak.")
                }
                Section { TextField("Notes (optional)", text: $notes, axis: .vertical).lineLimit(2...5) }
            }
            .navigationTitle(existing == nil ? "New target" : "Edit target")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save) }
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var importing = false
    @State private var exportURL: URL?
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Save your targets and history to a file, and restore them on a new phone, Android or iPhone.")
                    if let url = exportURL { ShareLink(item: url) { Label("Export backup", systemImage: "square.and.arrow.up") } }
                    Button { importing = true } label: { Label("Import backup", systemImage: "square.and.arrow.down") }
                    if let message = message { Text(message).font(.footnote).foregroundColor(.secondary) }
                } header: { Text("Backup") }
                Section("Samsung Health") {
                    Text("On Android, Target reads steps, workouts, distance and calories from Samsung Health so targets tick themselves. On iPhone you log them with a tap.")
                }
                Section("Privacy") {
                    Text("Target works completely offline. No account, no ads and no tracking. Your targets and history stay on this iPhone.")
                }
                Section {
                    Text("Target \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")")
                    Link("github.com/rachitratnammudaliar/Target-Habit-Goal-Tracker", destination: URL(string: "https://github.com/rachitratnammudaliar/Target-Habit-Goal-Tracker")!)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { exportURL = try? store.exportFile() }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
                switch result {
                case .success(let url):
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    do {
                        message = "Added \(try store.importData(try Data(contentsOf: url))) targets from the backup."
                        exportURL = try? store.exportFile()
                    } catch {
                        message = error.localizedDescription
                    }
                case .failure(let error):
                    message = error.localizedDescription
                }
            }
        }
    }
}
