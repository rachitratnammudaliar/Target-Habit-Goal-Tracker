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
    @State private var dayTimes: [Int: Int]
    @State private var weekly: Int
    @State private var weekTotal: Bool
    @State private var start: Date
    @State private var editingDay: Int?
    @State private var dayTime = Date()
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
        _dayTimes = State(initialValue: DayTimes.parse(existing?.dayTimes ?? ""))
        _weekly = State(initialValue: existing?.weeklyTimes ?? 0)
        _weekTotal = State(initialValue: existing?.weekTotal ?? false)
        _start = State(initialValue: Day(epochDay: existing?.startDay ?? Day.today().epochDay).localDate)
    }

    private var totalOn: Bool { weekTotal && kind == .AMOUNT }
    private var weeklyOn: Bool { weekly > 0 && kind != .BY_TIME && !totalOn }

    private var minutes: Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }
    private var goalValue: Double? { Amounts.parse(goalText) }
    private var defaultStep: Double { unit.trimmingCharacters(in: .whitespaces) == "h" ? 0.5 : 1 }
    private var stepValue: Double? { stepText.trimmingCharacters(in: .whitespaces).isEmpty ? defaultStep : Amounts.parse(stepText) }
    private var invalid: Bool {
        name.trimmingCharacters(in: .whitespaces).isEmpty || (days == 0 && !weeklyOn && !totalOn) || (kind == .AMOUNT && (goalValue == nil || stepValue == nil))
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
        weekly = t.weeklyTimes
        weekTotal = t.weekTotal
        dayTimes = [:]
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
        t.days = days == 0 ? Days.all : days
        t.dayTimes = kind == .BY_TIME ? DayTimes.format(dayTimes.filter { Days.has(t.days, $0.key - 1) }) : ""
        t.weeklyTimes = weeklyOn ? min(7, max(1, weekly)) : 0
        t.weekTotal = totalOn
        t.startDay = Day(localDate: start).epochDay
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
                        if !totalOn { Text("60% is partly achieved, 80% satisfied, 100% done and 130% overachieved.").font(.caption).foregroundColor(.secondary) }
                        if showErrors && (goalValue == nil || stepValue == nil) { Text("Use a number above 0").font(.caption).foregroundColor(.red) }
                    case .CHECK:
                        EmptyView()
                    }
                } footer: {
                    Text(kind.description + (kind == .AMOUNT ? ". On iPhone you add amounts yourself; Samsung Health filling is on Android." : "."))
                }
                if kind != .BY_TIME {
                    Section {
                        Picker("How often", selection: Binding(
                            get: { totalOn ? 2 : weeklyOn ? 1 : 0 },
                            set: { m in
                                weekTotal = m == 2
                                weekly = m == 1 ? (weekly > 0 ? weekly : 3) : 0
                            }
                        )) {
                            Text("On set days").tag(0)
                            Text("Times a week").tag(1)
                            if kind == .AMOUNT { Text("Total for the week").tag(2) }
                        }
                        .pickerStyle(.segmented)
                        if weeklyOn { Stepper("\(weekly)× a week", value: $weekly, in: 1...7) }
                    } footer: {
                        if totalOn { Text("The target amount is the total for the whole week, in any amounts on any days. Good for self-study: 15 h a week.") }
                        else if weeklyOn { Text("Any days you like, Monday to Sunday. Good for runs or the gym when your timetable changes.") }
                    }
                }
                if !weeklyOn && !totalOn {
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
                }
                if kind == .BY_TIME {
                    Section {
                        ForEach((0..<7).filter { Days.has(days, $0) }, id: \.self) { i in
                            HStack {
                                Text(Days.names[i])
                                Spacer()
                                Button(dayTimes[i + 1].map { "By \(Times.format($0))" } ?? "Usual (\(Times.format(minutes)))") {
                                    let m = dayTimes[i + 1] ?? minutes
                                    dayTime = Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
                                    editingDay = i
                                }
                                .buttonStyle(.borderless)
                                if dayTimes[i + 1] != nil {
                                    Button("Reset") { dayTimes[i + 1] = nil }.buttonStyle(.borderless).foregroundColor(.secondary)
                                }
                            }
                        }
                    } header: {
                        Text("Different time on some days")
                    } footer: {
                        Text("E.g. library by 10:00 on Tuesday and 13:00 on Thursday. To move just today, use ⋯ → Change today's target time.")
                    }
                }
                Section {
                    DatePicker("Starts on", selection: $start, displayedComponents: .date)
                } footer: {
                    Text("Days before this don't count against you.")
                }
                Section { TextField("Notes (optional)", text: $notes, axis: .vertical).lineLimit(2...5) }
            }
            .sheet(isPresented: Binding(get: { editingDay != nil }, set: { if !$0 { editingDay = nil } })) {
                if let i = editingDay {
                    TimeSheet(title: Days.names[i], hint: "The target time on \(Days.names[i]) only.", time: $dayTime) { m in
                        dayTimes[i + 1] = m
                        editingDay = nil
                    } onCancel: { editingDay = nil }
                }
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
    @State private var clearing: Int?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Save your targets and history to a file, and restore them on a new phone, Android or iPhone.")
                    if let url = exportURL { ShareLink(item: url) { Label("Export backup", systemImage: "square.and.arrow.up") } }
                    Button { importing = true } label: { Label("Import backup", systemImage: "square.and.arrow.down") }
                    if let message = message { Text(message).font(.footnote).foregroundColor(.secondary) }
                } header: { Text("Backup") }
                Section {
                    ForEach([3, 6, 12, 0], id: \.self) { months in
                        Button(months == 0 ? "Everything" : "Older than \(months) months", role: .destructive) { clearing = months }
                    }
                } header: {
                    Text("Clear old history")
                } footer: {
                    Text("Deletes what you logged before that date. Your targets stay. Export a backup first if you might want it back. To clear one target only, open it and use ⋯.")
                }
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
            .confirmationDialog(clearing == 0 ? "Clear all history?" : "Clear history older than \(clearing ?? 0) months?",
                                isPresented: Binding(get: { clearing != nil }, set: { if !$0 { clearing = nil } }), titleVisibility: .visible) {
                Button("Clear", role: .destructive) {
                    let today = Day.today()
                    let before = clearing == 0 ? today.plusDays(2) : today.plusMonths(-(clearing ?? 0))
                    let n = store.clearHistory(before: before)
                    message = n == 0 ? "Nothing that old to clear." : "Cleared \(n) entries."
                    exportURL = try? store.exportFile()
                    clearing = nil
                }
                Button("Cancel", role: .cancel) { clearing = nil }
            } message: {
                Text("This can't be undone. Your targets stay.")
            }
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
