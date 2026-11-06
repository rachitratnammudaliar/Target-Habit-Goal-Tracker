import SwiftUI
import Charts

struct ProgressTab: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @State private var range = 7
    @State private var path: [Int64] = []

    var body: some View {
        let from = today.plusDays(-(range - 1))
        let days = (0..<range).map { from.plusDays($0) }
        let scores = days.map { Goals.dayScore(store.goals, $0, today) }
        let rates = store.active.map { ($0, Goals.hitRate($0.goal, store.logsByDay($0.id), from, today, today)) }
        let done = rates.reduce(0) { $0 + $1.1.done }
        let counted = rates.reduce(0) { $0 + $1.1.counted }

        NavigationStack(path: $path) {
            List {
                if store.active.isEmpty {
                    Text("Add a target on Today and your progress shows up here.").foregroundColor(.secondary)
                } else {
                    Picker("Range", selection: $range) {
                        Text("7 days").tag(7)
                        Text("30 days").tag(30)
                        Text("90 days").tag(90)
                    }
                    .pickerStyle(.segmented)

                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Targets hit, last \(range) days").font(.subheadline).foregroundColor(.secondary)
                            Text(counted == 0 ? "–" : "\(Int((Double(done) * 100 / Double(counted)).rounded()))%")
                                .font(.system(size: 34, weight: .semibold, design: .rounded))
                            Text("\(done) of \(counted)").font(.subheadline).foregroundColor(.secondary)
                            if range <= 30 {
                                Chart(scores, id: \.day.epochDay) { s in
                                    BarMark(x: .value("Day", s.day.date, unit: .day), y: .value("Hit", s.scheduled == 0 ? 0 : s.fraction))
                                        .foregroundStyle(s.scheduled > 0 && s.done == s.scheduled ? resultColor(.done) : Color.accentColor)
                                        .cornerRadius(3)
                                }
                                .chartYScale(domain: 0...1)
                                .chartYAxis {
                                    AxisMarks(values: [0, 0.5, 1]) { v in
                                        AxisGridLine()
                                        AxisValueLabel { if let d = v.as(Double.self) { Text("\(Int(d * 100))%") } }
                                    }
                                }
                                .frame(height: 160)
                            } else {
                                let weeks = stride(from: 0, to: scores.count, by: 7).map { Array(scores[$0..<min($0 + 7, scores.count)]) }
                                Chart(Array(weeks.enumerated()), id: \.offset) { _, w in
                                    let total = max(1, w.reduce(0) { $0 + $1.scheduled })
                                    BarMark(x: .value("Week", w[0].day.date, unit: .weekOfYear), y: .value("Hit", Double(w.reduce(0) { $0 + $1.done }) / Double(total)))
                                        .foregroundStyle(Color.accentColor).cornerRadius(3)
                                }
                                .chartYScale(domain: 0...1)
                                .frame(height: 160)
                            }
                            Text("Each bar is the share of the day's targets you hit. Green: all of them.").font(.caption).foregroundColor(.secondary)
                        }
                        .padding(.vertical, 6)
                    }

                    SummarySection(today: today)

                    Section("Each target") {
                        ForEach(rates, id: \.0.id) { t, rate in
                            let streak = Goals.currentStreak(t.goal, store.logsByDay(t.id), today)
                            VStack(alignment: .leading, spacing: 10) {
                                HStack(spacing: 10) {
                                    EmojiBadge(emoji: t.emoji, tint: .accentColor, size: 38)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(t.name).font(.headline)
                                        Text(Goals.goalText(t.goal, t.unit) + (t.goal.weekly ? "" : " · " + Days.label(t.days))).font(.caption).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 2) {
                                        Text(rate.percent.map { "\($0)%" } ?? "–").font(.title3.weight(.bold))
                                        Text(streak > 0 ? "🔥 \(streak)" : "\(rate.done)/\(rate.counted)").font(.caption).foregroundColor(.secondary)
                                    }
                                }
                                ScrollView(.horizontal, showsIndicators: false) { Heatmap(weeks: heatWeeks(t, store, today, 12), cell: 12) }
                            }
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                            .onTapGesture { path.append(t.id) }
                        }
                    }
                    HeatmapKey()
                }
            }
            .navigationTitle("Progress")
            .navigationDestination(for: Int64.self) { id in DetailView(id: id, today: today) }
        }
    }
}

struct DetailView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let id: Int64
    let today: Day
    @State private var editing = false
    @State private var confirmDelete = false
    @State private var confirmClear = false
    @State private var pausing = false

    var body: some View {
        if let t = store.target(id) {
            content(t)
        } else {
            Text("This target was deleted.").foregroundColor(.secondary)
        }
    }

    private func content(_ t: Habit) -> some View {
        let goal = t.goal
        let byDay = store.logsByDay(t.id)
        let month = Goals.hitRate(goal, byDay, today.plusDays(-29), today, today)
        let all = Goals.hitRate(goal, byDay, goal.startDay, today, today)
        let days = (0..<30).map { today.plusDays($0 - 29) }
        let history = Array(store.entries(t.id).filter { $0.typeValue != .TARGET && ($0.typeValue != .HEALTH || $0.value > 0) }.prefix(40))

        return List {
            Section {
                HStack(spacing: 12) {
                    EmojiBadge(emoji: t.emoji, tint: .accentColor, size: 54)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.name).font(.title3.weight(.semibold))
                        Text(Goals.goalText(goal, t.unit) + (goal.weekly ? "" : " · " + Days.label(t.days))).foregroundColor(.secondary)
                        if t.pauseEnd > 0 && t.pauseEnd >= today.epochDay {
                            Text("Paused \(Day(epochDay: t.pauseStart).format(.short)) – \(Day(epochDay: t.pauseEnd).format(.short))").font(.caption).foregroundColor(.secondary)
                        }
                        if t.archived { Text("Not tracking any more.").font(.caption).foregroundColor(.secondary) }
                    }
                }
                HStack(spacing: 8) {
                    StatTile(value: "🔥 \(Goals.currentStreak(goal, byDay, today))", label: goal.weekly ? "weeks now" : "streak now")
                    StatTile(value: "\(Goals.bestStreak(goal, byDay, today))", label: goal.weekly ? "best weeks" : "best streak")
                    StatTile(value: month.percent.map { "\($0)%" } ?? "–", label: "30 days")
                    StatTile(value: "\(all.done)", label: "times hit")
                }
            }
            Section("Last 17 weeks") {
                ScrollView(.horizontal, showsIndicators: false) { Heatmap(weeks: heatWeeks(t, store, today, 17)) }
                HeatmapKey()
            }
            Section {
                switch t.kindValue {
                case .BY_TIME:
                    let points = days.compactMap { d -> (Day, Int, Int)? in
                        let logs = byDay[d] ?? []
                        guard Goals.isScheduled(goal, d), let m = Goals.time(logs) else { return nil }
                        return (d, m, Goals.targetTime(goal, logs, d))
                    }
                    Chart {
                        ForEach(days, id: \.epochDay) { d in
                            LineMark(x: .value("Day", d.date, unit: .day), y: .value("Target", Goals.targetTime(goal, byDay[d] ?? [], d)), series: .value("Line", "target"))
                                .interpolationMethod(.stepCenter)
                                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 5])).foregroundStyle(.secondary)
                        }
                        ForEach(points, id: \.0.epochDay) { d, m, target in
                            LineMark(x: .value("Day", d.date, unit: .day), y: .value("Time", m), series: .value("Line", "logged")).foregroundStyle(Color.accentColor.opacity(0.5))
                            PointMark(x: .value("Day", d.date, unit: .day), y: .value("Time", m)).foregroundStyle(m <= target ? resultColor(.done) : resultColor(.late))
                        }
                    }
                    .chartYScale(domain: .automatic(includesZero: false, reversed: true))
                    .chartYAxis { AxisMarks { v in AxisGridLine(); AxisValueLabel { if let m = v.as(Int.self) { Text(Times.format(m)) } } } }
                    .frame(height: 180)
                    Text("Dashed line: each day's target time. Earlier is higher; green dots were on time.").font(.caption).foregroundColor(.secondary)
                case .AMOUNT:
                    if goal.weekTotal {
                        let weeks = (0..<12).map { Goals.weekStart(today).plusDays(-7 * (11 - $0)) }
                        Chart {
                            ForEach(weeks, id: \.epochDay) { w in
                                let v = Goals.weekAmount(goal, byDay, w, today)
                                BarMark(x: .value("Week", w.date, unit: .weekOfYear), y: .value("Amount", v))
                                    .foregroundStyle(Goals.tier(v, goal.goalValue).map { resultColor($0) } ?? Color.accentColor).cornerRadius(3)
                            }
                            RuleMark(y: .value("Target", goal.goalValue)).lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 5])).foregroundStyle(.secondary)
                        }
                        .frame(height: 180)
                        Text("Dashed line: \(Amounts.withUnit(goal.goalValue, t.unit)) a week. Blue-green: 130% or more. Green: reached.").font(.caption).foregroundColor(.secondary)
                    } else {
                    Chart {
                        ForEach(days, id: \.epochDay) { d in
                            let v = Goals.amount(byDay[d] ?? [])
                            BarMark(x: .value("Day", d.date, unit: .day), y: .value("Amount", v))
                                .foregroundStyle(Goals.tier(v, t.goalValue).map { resultColor($0) } ?? Color.accentColor).cornerRadius(2)
                        }
                        RuleMark(y: .value("Target", t.goalValue)).lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 5])).foregroundStyle(.secondary)
                    }
                    .frame(height: 180)
                    Text("Dashed line: your target of \(Amounts.withUnit(t.goalValue, t.unit)). Teal: 130% or more. Green: reached. Lime: 80%. Amber: 60%.").font(.caption).foregroundColor(.secondary)
                    }
                case .CHECK:
                    let weeks = (0..<12).map { Goals.weekStart(today).plusDays(-7 * (11 - $0)) }
                    if goal.weekly {
                        Chart {
                            ForEach(weeks, id: \.epochDay) { w in
                                let n = Goals.timesInWeek(goal, byDay, w, today)
                                BarMark(x: .value("Week", w.date, unit: .weekOfYear), y: .value("Done", n))
                                    .foregroundStyle(n >= goal.weeklyTimes ? resultColor(.done) : Color.accentColor).cornerRadius(3)
                            }
                            RuleMark(y: .value("Goal", goal.weeklyTimes)).lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 5])).foregroundStyle(.secondary)
                        }
                        .chartYScale(domain: 0...7)
                        .frame(height: 160)
                        Text("Dashed line: \(goal.weeklyTimes) times a week. Green: reached.").font(.caption).foregroundColor(.secondary)
                    } else {
                        Chart(weeks, id: \.epochDay) { w in
                            let r = Goals.hitRate(goal, byDay, w, w.plusDays(6), today)
                            BarMark(x: .value("Week", w.date, unit: .weekOfYear), y: .value("Done", r.done))
                                .foregroundStyle(r.counted > 0 && r.done == r.counted ? resultColor(.done) : Color.accentColor).cornerRadius(3)
                        }
                        .frame(height: 160)
                        Text("Times done each week. Green: every planned day.").font(.caption).foregroundColor(.secondary)
                    }
                }
            } header: {
                Text(t.kindValue == .BY_TIME ? "Time you logged, last 30 days" : t.kindValue == .AMOUNT ? "\(t.unit.isEmpty ? "Amount" : t.unit.capitalized), last 30 days" : "Last 12 weeks")
            }
            Section("History") {
                if history.isEmpty { Text("Nothing logged yet.").foregroundColor(.secondary) }
                ForEach(history) { e in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(historyTitle(e, t, byDay)).fontWeight(.medium)
                            Text(dayLabel(Day(epochDay: e.epochDay), today)).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        if e.typeValue != .HEALTH && e.typeValue != .NOTE {
                            Button("Remove") { store.deleteEntry(e.id) }.buttonStyle(.borderless).font(.subheadline)
                        }
                    }
                }
            }
            Section {
                if t.pauseEnd > 0 && t.pauseEnd >= today.epochDay {
                    Button("Resume") { store.resume(t, today: today) }
                } else {
                    Button("Pause…") { pausing = true }
                }
                Button(t.archived ? "Bring back" : "Stop tracking (keep history)") { store.setArchived(t.id, !t.archived) }
                Button("Clear this target's history…", role: .destructive) { confirmClear = true }
                Button("Delete", role: .destructive) { confirmDelete = true }
            }
        }
        .navigationTitle(t.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Edit") { editing = true } } }
        .sheet(isPresented: $editing) { EditView(existing: store.target(t.id)) }
        .alert("Clear \(t.name)'s history?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) { store.clearHistory(t.id) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Everything logged for this target is deleted, and its streak starts again. The target stays.")
        }
        .confirmationDialog("Pause \(t.name)", isPresented: $pausing, titleVisibility: .visible) {
            ForEach([(3, "3 days"), (7, "1 week"), (14, "2 weeks"), (30, "1 month")], id: \.0) { n, label in
                Button(label) { store.pause(t, from: today, to: today.plusDays(n - 1)) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Paused days don't count and don't break your streak.")
        }
        .alert("Delete \(t.name)?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                store.delete(t.id)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes the target and its whole history. To stop tracking but keep the history, choose Stop tracking instead.")
        }
    }

    private func historyTitle(_ e: Entry, _ t: Habit, _ byDay: [Day: [Log]]) -> String {
        let day = Day(epochDay: e.epochDay)
        switch e.typeValue {
        case .DONE: return "Done"
        case .TIME:
            let target = Goals.targetTime(t.goal, byDay[day] ?? [], day)
            return "At \(Times.format(Int(e.value)))" + (Int(e.value) <= target ? " ✓" : " (late)") + " · target \(Times.format(target))"
        case .NOTE: return "📝 " + e.note
        case .TARGET: return "Target \(Times.format(Int(e.value)))"
        case .AMOUNT: return "+ \(Amounts.withUnit(e.value, t.unit))"
        case .HEALTH: return "Samsung Health: \(Amounts.withUnit(e.value, t.unit))"
        case .SKIP: return "Skipped"
        }
    }
}


/// Weekly and monthly summary, with a Share button that sends it as text.
struct SummarySection: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @State private var monthly = false
    @State private var back = 0

    private static func monthTitle(_ d: Day) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.dateFormat = "MMMM yyyy"
        return f.string(from: d.date)
    }

    private func period() -> (from: Day, to: Day, prevFrom: Day, prevTo: Day) {
        if monthly {
            let from = today.firstOfMonth.plusMonths(-back)
            return (from, from.plusMonths(1).plusDays(-1), from.plusMonths(-1), from.plusDays(-1))
        }
        let from = Goals.weekStart(today).plusDays(-7 * back)
        return (from, from.plusDays(6), from.plusDays(-7), from.plusDays(-1))
    }

    var body: some View {
        let p = period()
        let from = p.from, to = p.to, prevFrom = p.prevFrom, prevTo = p.prevTo
        let title = monthly ? Self.monthTitle(from) : back == 0 ? "This week" : back == 1 ? "Last week" : "\(from.format(.short)) – \(to.format(.short))"
        let summary = Goals.summary(store.goals, units: store.active.map(\.unit), from: from, to: to, prevFrom: prevFrom, prevTo: prevTo, today: today)
        let lines = Self.lines(summary, store.active)

        Section {
            Picker("Period", selection: Binding(get: { monthly }, set: { monthly = $0; back = 0 })) {
                Text("Week").tag(false)
                Text("Month").tag(true)
            }
            .pickerStyle(.segmented)
            HStack {
                Button { back += 1 } label: { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                Text(title).font(.headline).frame(maxWidth: .infinity)
                Button { back -= 1 } label: { Image(systemName: "chevron.right") }.buttonStyle(.borderless).disabled(back == 0)
            }
            ForEach(lines, id: \.self) { Text($0) }
            ShareLink(item: title + "\n" + lines.joined(separator: "\n")) { Label("Share", systemImage: "square.and.arrow.up") }
        }
    }

    static func lines(_ s: Goals.Summary, _ list: [Habit]) -> [String] {
        if s.counted == 0 && s.totals.isEmpty { return ["Nothing counted yet."] }
        var out: [String] = []
        if let p = s.percent {
            var vs = ""
            if let prev = s.previousPercent {
                let d = p - prev
                vs = d == 0 ? " · same as before" : d > 0 ? " · up \(d) points" : " · down \(-d) points"
            }
            out.append("\(s.done) of \(s.counted) targets hit · \(p)%\(vs)")
        }
        if !s.totals.isEmpty { out.append("📊 " + s.totals.map { Amounts.withUnit($0.1, $0.0) }.joined(separator: " · ")) }
        if s.overachieved > 0 { out.append("⭐ Overachieved \(s.overachieved) \(s.overachieved == 1 ? "time" : "times")") }
        if let d = s.bestDay { out.append("💪 Best day: \(Days.names[d])") }
        if let d = s.toughestDay { out.append("🪫 Toughest day: \(Days.names[d])") }
        if let t = s.top, list.indices.contains(t.0) { out.append("🏆 Most consistent: \(list[t.0].emoji) \(list[t.0].name), \(t.1)%") }
        if let w = s.weakest, list.indices.contains(w.0), s.top?.0 != w.0 { out.append("🎯 Needs attention: \(list[w.0].emoji) \(list[w.0].name), \(w.1)%") }
        return out
    }
}
