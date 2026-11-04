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
        // Weekly targets show every day; "Not today" lists only set-day targets.
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
                                if let b = best, b.1 >= 2 { Text("🔥 \(b.1)-\(b.0.goal.weekly ? "week" : "day") streak: \(b.0.name)").font(.subheadline).foregroundColor(.secondary) }
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
    @State private var movingTarget = false
    @State private var addingAmount = false
    @State private var editingNote = false
    @State private var pausing = false
    @State private var time = Date()
    @State private var amountText = ""
    @State private var noteText = ""

    private static func nowMinutes() -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    private func date(_ minutes: Int) -> Date {
        let m = minutes % 1440
        return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
    }

    var body: some View {
        let t = target
        let goal = t.goal
        let logs = store.logs(t.id, day)
        let byDay = store.logsByDay(t.id)
        let result = Goals.result(goal, logs, day, today)
        let streak = Goals.currentStreak(goal, byDay, today)
        let logged = Goals.time(logs)
        let dayTarget = Goals.targetTime(goal, logs, day)
        let overdue = Goals.isOverdue(goal, logs, day, today, Self.nowMinutes())
        let paused = Goals.isPaused(goal, day)
        let tint = overdue ? Color.red : result == .pending ? Color.accentColor : resultColor(result)
        let note = store.note(t.id, day)
        let movedToday = logs.contains { $0.type == .TARGET } && logged == nil
        let status: String = {
            if paused, let end = goal.pauseEnd { return "Paused until \(end.format(.short))" }
            if result == .skipped { return "Skipped" }
            if goal.weekly {
                let n = Goals.timesInWeek(goal, byDay, Goals.weekStart(day), today)
                let base = "\(n) of \(goal.weeklyTimes) this week"
                return t.kindValue == .AMOUNT ? base + " · " + Goals.progressText(goal, logs, t.unit) : base + (result == .done ? " · done today" : "")
            }
            switch t.kindValue {
            case .BY_TIME:
                if let m = logged { return (result == .done ? "Done at " : "Late: ") + Times.format(m) + " · target \(Times.format(dayTarget))" }
                if overdue { return "Overdue · target \(Times.format(dayTarget))" }
                if movedToday || dayTarget != t.timeMinutes { return "By \(Times.format(dayTarget))" + (day == today ? " today" : "") }
                return Goals.goalText(goal, t.unit) + (result == .missed ? " · missed" : "")
            case .AMOUNT: return Goals.progressText(goal, logs, t.unit)
            case .CHECK: return result == .done ? "Done" : result == .missed ? "Missed" : "Any time today"
            }
        }()
        let streakText = streak >= 2 ? (goal.weekly ? "  ·  🔥 \(streak) wk" : "  ·  🔥 \(streak)") : ""

        HStack(spacing: 12) {
            HStack(spacing: 12) {
                EmojiBadge(emoji: t.emoji, tint: tint)
                VStack(alignment: .leading, spacing: 4) {
                    Text(t.name).font(.headline).lineLimit(1)
                    Text(status + streakText)
                        .font(.caption)
                        .foregroundColor(overdue ? .red : result == .pending || result == .skipped ? .secondary : resultColor(result))
                        .lineLimit(1)
                    if !note.isEmpty { Text("📝 " + note).font(.caption).foregroundColor(.secondary).lineLimit(1) }
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
                        let r = store.toggleDone(t, day)
                        if r.done {
                            showUndo($undo, "\(t.name): done ✓")
                        } else {
                            showUndo($undo, "\(t.name): not done") { store.restore(r.removed) }
                        }
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
                                let (d, m) = Goals.nightAdjust(goal, today, Self.nowMinutes(), store.logs(t.id, today.plusDays(-1)))
                                let id = store.logTime(t, d, m)
                                showUndo($undo, d == today ? "\(t.name) logged" : "\(t.name) logged for last night") { store.deleteEntry(id) }
                            } else {
                                time = date(dayTarget)
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
                    Button(logged == nil ? "I did it at…" : "Change the time…") {
                        time = date(logged ?? dayTarget)
                        pickingTime = true
                    }
                    if logged == nil {
                        Button(day == today ? "Change today's target time…" : "Change this day's target time…") {
                            time = date(dayTarget)
                            movingTarget = true
                        }
                        if logs.contains(where: { $0.type == .TARGET }) { Button("Back to the usual time") { store.setDayTarget(t, day, nil) } }
                    }
                }
                if t.kindValue == .AMOUNT { Button("Add an amount…") { amountText = ""; addingAmount = true } }
                Button(note.isEmpty ? "Add a note…" : "Edit the note…") { noteText = note; editingNote = true }
                if !paused { Button(result == .skipped ? "Don't skip" : "Skip this day") { store.toggleSkip(t, day) } }
                if t.paused(on: today) || t.pauseStart > today.epochDay {
                    Button("Resume") { store.resume(t, today: today) }
                } else {
                    Button("Pause…") { pausing = true }
                }
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
            TimeSheet(title: "\(t.emoji) \(t.name)", hint: "When did you do it?", time: $time) { m in
                store.logTime(t, day, m)
                pickingTime = false
            } onCancel: { pickingTime = false }
        }
        .sheet(isPresented: $movingTarget) {
            TimeSheet(title: "Target for \(day == today ? "today" : day.format(.short))", hint: "Just this day. Your usual time stays \(Times.format(t.timeMinutes)).", time: $time) { m in
                store.setDayTarget(t, day, m)
                movingTarget = false
            } onCancel: { movingTarget = false }
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
        .alert("Note for \(day == today ? "today" : day.format(.short))", isPresented: $editingNote) {
            TextField("Exam week, felt ill, new route…", text: $noteText)
            Button("Save") { store.setNote(t, day, noteText) }
            if !note.isEmpty { Button("Delete", role: .destructive) { store.setNote(t, day, "") } }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Pause \(t.name)", isPresented: $pausing, titleVisibility: .visible) {
            ForEach([(3, "3 days"), (7, "1 week"), (14, "2 weeks"), (30, "1 month")], id: \.0) { n, label in
                Button(label) { store.pause(t, from: today, to: today.plusDays(n - 1)) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Paused days don't count and don't break your streak. Good for holidays, exams or being ill.")
        }
    }
}

/// A wheel time picker in a half-height sheet.
struct TimeSheet: View {
    let title: String
    let hint: String
    @Binding var time: Date
    let onSave: (Int) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            VStack {
                Text(hint).font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center).padding(.horizontal)
                DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel).labelsHidden()
            }
            .padding(.vertical)
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
                        onSave((c.hour ?? 0) * 60 + (c.minute ?? 0))
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
