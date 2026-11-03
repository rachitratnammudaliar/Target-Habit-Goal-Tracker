import SwiftUI

struct TodayView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @State private var offset = 0
    @State private var adding = false
    @State private var settings = false
    @State private var undo: UndoState?
    @State private var path: [Int64] = []

    var body: some View {
        let day = today.plusDays(-offset)
        let due = store.active.filter { Goals.isScheduled($0.goal, day) }
        let rest = store.active.filter { !Goals.isScheduled($0.goal, day) }
        let score = Goals.dayScore(store.goals, day, today)
        let best = store.active.map { ($0, Goals.currentStreak($0.goal, store.logsByDay($0.id), today)) }.max { $0.1 < $1.1 }

        NavigationStack(path: $path) {
            List {
                if store.active.isEmpty {
                    VStack(spacing: 10) {
                        Text("🎯").font(.system(size: 48))
                        Text("Set your first target").font(.headline)
                        Text("Things you want to hit every day: wake up by 7:00, at the library by 9:00, run 5 km, 10,000 steps. Log them with a tap and watch your streaks grow.")
                            .font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center)
                        Button("Add a target") { adding = true }.buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                } else {
                    Section {
                        HStack {
                            Button { offset += 1 } label: { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                            Spacer()
                            Text(dayLabel(day, today)).font(.headline)
                            Spacer()
                            Button { offset -= 1 } label: { Image(systemName: "chevron.right") }.buttonStyle(.borderless).disabled(offset == 0)
                        }
                        HStack(spacing: 16) {
                            ProgressRing(fraction: score.fraction, label: "\(score.done)/\(score.scheduled)")
                            VStack(alignment: .leading, spacing: 4) {
                                Text(score.scheduled == 0 ? "Rest day" : score.done == score.scheduled ? "All targets hit 🎉" : day == today ? "\(score.scheduled - score.done) to go today" : "\(score.done) of \(score.scheduled) hit")
                                    .font(.title3.weight(.semibold))
                                if let b = best, b.1 >= 2 { Text("🔥 \(b.1)-day streak: \(b.0.name)").font(.subheadline).foregroundColor(.secondary) }
                            }
                        }
                        .padding(.vertical, 6)
                    }
                    .listRowBackground(Color.accentColor.opacity(0.1))

                    Section {
                        ForEach(due) { t in
                            TargetRow(target: t, day: day, today: today, undo: $undo) { path.append(t.id) }
                        }
                    }
                    if !rest.isEmpty {
                        Text("Not today: " + rest.map { "\($0.emoji) \($0.name)" }.joined(separator: ", ")).font(.caption).foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Target")
            .navigationDestination(for: Int64.self) { id in DetailView(id: id, today: today) }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { settings = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }.accessibilityLabel("Add a target")
                }
            }
            .sheet(isPresented: $adding) { EditView(existing: nil) }
            .sheet(isPresented: $settings) { SettingsView() }
            .undoOverlay($undo)
        }
    }
}

struct TargetRow: View {
    @EnvironmentObject private var store: Store
    let target: Habit
    let day: Day
    let today: Day
    @Binding var undo: UndoState?
    let onOpen: () -> Void
    @State private var pickingTime = false
    @State private var addingAmount = false
    @State private var time = Date()
    @State private var amountText = ""

    var body: some View {
        let t = target
        let goal = t.goal
        let logs = store.logs(t.id, day)
        let result = Goals.result(goal, logs, day, today)
        let streak = Goals.currentStreak(goal, store.logsByDay(t.id), today)
        let tint = result == .pending ? Color.accentColor : resultColor(result)
        let logged = Goals.time(logs)
        let status: String = {
            if result == .skipped { return "Skipped" }
            switch t.kindValue {
            case .BY_TIME:
                if let m = logged { return (result == .done ? "Done at " : "Late: ") + Times.format(m) + " · target \(Times.format(t.timeMinutes))" }
                return Goals.goalText(goal, t.unit) + (result == .missed ? " · missed" : "")
            case .AMOUNT: return Goals.progressText(goal, logs, t.unit)
            case .CHECK: return result == .done ? "Done" : result == .missed ? "Missed" : "Not done yet"
            }
        }()

        HStack(spacing: 12) {
            HStack(spacing: 12) {
                EmojiBadge(emoji: t.emoji, tint: tint)
                VStack(alignment: .leading, spacing: 4) {
                    Text(t.name).font(.headline).lineLimit(1)
                    Text(status + (streak >= 2 ? "  ·  🔥 \(streak)" : ""))
                        .font(.caption).foregroundColor(result == .pending || result == .skipped ? .secondary : resultColor(result)).lineLimit(1)
                    if t.kindValue == .AMOUNT && result != .skipped {
                        ProgressView(value: Goals.progress(goal, logs)).tint(tint)
                    }
                }
                Spacer(minLength: 4)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)

            if result != .skipped {
                switch t.kindValue {
                case .CHECK:
                    Button {
                        if store.toggleDone(t, day) { showUndo($undo, "\(t.name): done ✓") }
                    } label: {
                        Image(systemName: result == .done ? "checkmark.circle.fill" : "circle").font(.title)
                            .foregroundColor(result == .done ? resultColor(.done) : .secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(result == .done ? "Done. Tap to undo" : "Mark done")
                case .BY_TIME:
                    if logged == nil {
                        Button(day == today ? "✓ Now" : "Time…") {
                            if day == today {
                                let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
                                let id = store.logTime(t, day, (c.hour ?? 0) * 60 + (c.minute ?? 0))
                                showUndo($undo, "\(t.name) logged") { store.deleteEntry(id) }
                            } else {
                                time = Calendar.current.date(bySettingHour: t.timeMinutes / 60, minute: t.timeMinutes % 60, second: 0, of: Date()) ?? Date()
                                pickingTime = true
                            }
                        }
                        .buttonStyle(.bordered)
                    }
                case .AMOUNT:
                    Button {
                        if let id = store.addAmount(t, day, t.step) {
                            showUndo($undo, "Added \(Amounts.withUnit(t.step, t.unit)) to \(t.name)") { store.deleteEntry(id) }
                        }
                    } label: { Image(systemName: "plus").frame(width: 30, height: 30) }
                        .buttonStyle(.borderedProminent)
                        .accessibilityLabel("Add \(Amounts.withUnit(t.step, t.unit))")
                }
            }
            Menu {
                if t.kindValue == .BY_TIME {
                    Button(logged == nil ? "Set the time…" : "Change the time…") {
                        let m = logged ?? t.timeMinutes
                        time = Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
                        pickingTime = true
                    }
                }
                if t.kindValue == .AMOUNT { Button("Add an amount…") { amountText = ""; addingAmount = true } }
                Button(result == .skipped ? "Don't skip" : "Skip this day") { store.toggleSkip(t, day) }
                if logs.contains(where: { $0.type == .DONE || $0.type == .TIME || $0.type == .AMOUNT }) {
                    Button("Clear what I logged") { store.clearDay(t, day) }
                }
                Button("Move up") { store.move(t.id, by: -1) }
                Button("Move down") { store.move(t.id, by: 1) }
            } label: { Image(systemName: "ellipsis").frame(width: 28, height: 32) }
                .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $pickingTime) {
            NavigationStack {
                DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel).labelsHidden().padding()
                    .navigationTitle("\(t.emoji) \(t.name)").navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { pickingTime = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Save") {
                                let c = Calendar.current.dateComponents([.hour, .minute], from: time)
                                store.logTime(t, day, (c.hour ?? 0) * 60 + (c.minute ?? 0))
                                pickingTime = false
                            }
                        }
                    }
            }
            .presentationDetents([.medium])
        }
        .alert("\(t.emoji) \(t.name)", isPresented: $addingAmount) {
            TextField(t.unit.isEmpty ? "How much" : "How much (\(t.unit))", text: $amountText).keyboardType(.decimalPad)
            Button("Add") {
                if let v = Amounts.parse(amountText), let id = store.addAmount(t, day, v) {
                    showUndo($undo, "Added \(Amounts.withUnit(v, t.unit)) to \(t.name)") { store.deleteEntry(id) }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Added to what's already logged for the day.")
        }
    }
}
