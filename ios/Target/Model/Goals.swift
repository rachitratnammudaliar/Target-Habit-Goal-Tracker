import Foundation

// A Swift port of the Android app's domain/Goals.kt. Keep the two in step: TargetTests mirrors GoalsTest.kt.

enum Kind: String, CaseIterable, Identifiable {
    case CHECK, BY_TIME, AMOUNT
    var id: String { rawValue }
    var label: String {
        switch self {
        case .CHECK: return "Done or not"
        case .BY_TIME: return "By a time"
        case .AMOUNT: return "An amount"
        }
    }
    var description: String {
        switch self {
        case .CHECK: return "Any time of day: tick it when you've done it"
        case .BY_TIME: return "Log the time you did it; on time counts"
        case .AMOUNT: return "Add up to a number, like km, steps or pages"
        }
    }
    static func from(_ name: String) -> Kind { Kind(rawValue: name) ?? .CHECK }
}

/// Filled in from Samsung Health on Android. Kept on iPhone so backups move both ways.
enum HealthSource: String, CaseIterable {
    case NONE, STEPS, DISTANCE, RUN_DISTANCE, EXERCISE_MINUTES, ACTIVE_CALORIES
    static func from(_ name: String) -> HealthSource { HealthSource(rawValue: name) ?? .NONE }
}

enum LogType: String {
    case DONE, TIME, AMOUNT, HEALTH, SKIP
    /// BY_TIME: that day's own target time (changed for the day, or saved when a time was logged).
    case TARGET
    /// A note for the day; the text is kept on the entry.
    case NOTE
    static func from(_ name: String) -> LogType { LogType(rawValue: name) ?? .DONE }
}

enum Outcome: CaseIterable {
    case done, late, partial, missed, skipped, pending, off
    var label: String {
        switch self {
        case .done: return "Done"
        case .late: return "Late"
        case .partial: return "Not quite"
        case .missed: return "Missed"
        case .skipped: return "Skipped"
        case .pending: return "To do"
        case .off: return "Rest day"
        }
    }
    var counts: Bool { self == .done || self == .late || self == .partial || self == .missed }
}

struct Goal {
    var kind: Kind = .CHECK
    var goalValue: Double = 1
    var timeMinutes: Int = 0
    var days: Int = Days.all
    var startDay: Day = Day(epochDay: Int.min / 4)
    var health: HealthSource = .NONE
    /// BY_TIME: a different time on some weekdays (1 = Monday … 7 = Sunday).
    var dayTimes: [Int: Int] = [:]
    /// CHECK and AMOUNT: this many times a week, on any days. 0 = on set days.
    var weeklyTimes: Int = 0
    var pauseStart: Day?
    var pauseEnd: Day?

    var weekly: Bool { weeklyTimes > 0 && kind != .BY_TIME }
}

struct Log {
    let type: LogType
    let date: Day
    var value: Double = 1
}

enum Days {
    static let all = 127
    static let weekdays = 31
    static let weekend = 96
    static let names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    /// 0 = Monday … 6 = Sunday. 1 January 1970 was a Thursday.
    static func index(_ day: Day) -> Int { ((day.epochDay + 3) % 7 + 7) % 7 }
    static func bit(_ index: Int) -> Int { 1 << index }
    static func has(_ days: Int, _ index: Int) -> Bool { days & bit(index) != 0 }

    static func label(_ days: Int) -> String {
        switch days {
        case all: return "Every day"
        case weekdays: return "Weekdays"
        case weekend: return "Weekends"
        case 0: return "No days"
        default: return (0..<7).filter { has(days, $0) }.map { names[$0] }.joined(separator: ", ")
        }
    }
}

/// "1=540;2=600": per-weekday target times, the same text as on Android.
enum DayTimes {
    static func parse(_ text: String) -> [Int: Int] {
        var out: [Int: Int] = [:]
        for part in text.split(separator: ";") {
            let kv = part.split(separator: "=", omittingEmptySubsequences: false)
            guard kv.count == 2,
                  let d = Int(kv[0].trimmingCharacters(in: .whitespaces)), (1...7).contains(d),
                  let m = Int(kv[1].trimmingCharacters(in: .whitespaces)), (0..<1440).contains(m) else { continue }
            out[d] = m
        }
        return out
    }

    static func format(_ times: [Int: Int]) -> String {
        times.keys.sorted().map { "\($0)=\(times[$0]!)" }.joined(separator: ";")
    }
}

enum Times {
    static func format(_ minutes: Int) -> String {
        let m = ((minutes % 1440) + 1440) % 1440
        return String(format: "%02d:%02d", m / 60, m % 60)
    }

    static func parse(_ text: String) -> Int? {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ".", with: ":")
        var h: Int?, m: Int?
        if let colon = t.firstIndex(of: ":") {
            h = Int(t[..<colon]); m = Int(t[t.index(after: colon)...])
        } else if (3...4).contains(t.count) {
            h = Int(t.dropLast(2)); m = Int(t.suffix(2))
        } else if (1...2).contains(t.count) {
            h = Int(t); m = 0
        }
        guard let hh = h, let mm = m, (0...23).contains(hh), (0...59).contains(mm) else { return nil }
        return hh * 60 + mm
    }
}

enum Amounts {
    static func round(_ v: Double) -> Double { (v * 100).rounded(.toNearestOrAwayFromZero) / 100 }

    static func format(_ v: Double) -> String {
        let r = round(v)
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        f.maximumFractionDigits = 2
        f.minimumFractionDigits = 0
        return f.string(from: NSNumber(value: r)) ?? "\(r)"
    }

    static func withUnit(_ v: Double, _ unit: String) -> String { unit.isEmpty ? format(v) : "\(format(v)) \(unit)" }

    static func parse(_ text: String) -> Double? {
        let cleaned = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "").replacingOccurrences(of: ",", with: ".")
        guard let v = Double(cleaned), v > 0, v <= 10_000_000, !v.isNaN else { return nil }
        return round(v)
    }
}

enum Goals {
    /// Before 04:00, logging an evening target ("in bed by 23:00") counts for the day before.
    static let nightCutoff = 4 * 60

    static func isPaused(_ goal: Goal, _ day: Day) -> Bool {
        guard let from = goal.pauseStart, let to = goal.pauseEnd else { return false }
        return !(day < from) && !(to < day)
    }

    static func isScheduled(_ goal: Goal, _ day: Day) -> Bool {
        !(day < goal.startDay) && (goal.weekly || Days.has(goal.days, Days.index(day)))
    }

    /// BY_TIME: changed for that day, else that weekday's time, else the usual one.
    static func targetTime(_ goal: Goal, _ logs: [Log], _ day: Day) -> Int {
        if let t = logs.last(where: { $0.type == .TARGET }) { return Int(t.value) }
        return goal.dayTimes[Days.index(day) + 1] ?? goal.timeMinutes
    }

    static func amount(_ logs: [Log]) -> Double {
        let manual = logs.filter { $0.type == .AMOUNT }.reduce(0) { $0 + $1.value }
        let health = logs.filter { $0.type == .HEALTH }.map(\.value).max() ?? 0
        return Amounts.round(max(manual, health))
    }

    static func time(_ logs: [Log]) -> Int? { logs.filter { $0.type == .TIME }.map { Int($0.value) }.min() }

    static func skipped(_ logs: [Log]) -> Bool { logs.contains { $0.type == .SKIP } }

    static func progress(_ goal: Goal, _ logs: [Log]) -> Double {
        switch goal.kind {
        case .CHECK: return logs.contains { $0.type == .DONE } ? 1 : 0
        case .BY_TIME: return time(logs) != nil ? 1 : 0
        case .AMOUNT: return goal.goalValue <= 0 ? 0 : min(1, max(0, amount(logs) / goal.goalValue))
        }
    }

    static func reached(_ goal: Goal, _ logs: [Log], _ day: Day) -> Bool {
        switch goal.kind {
        case .CHECK: return logs.contains { $0.type == .DONE }
        case .BY_TIME: return time(logs).map { $0 <= targetTime(goal, logs, day) } ?? false
        case .AMOUNT: return amount(logs) >= goal.goalValue - 1e-9
        }
    }

    static func result(_ goal: Goal, _ logs: [Log], _ day: Day, _ today: Day) -> Outcome {
        if !isScheduled(goal, day) { return .off }
        if skipped(logs) || isPaused(goal, day) { return .skipped }
        let over = day < today
        switch goal.kind {
        case .CHECK:
            if logs.contains(where: { $0.type == .DONE }) { return .done }
        case .BY_TIME:
            if let t = time(logs) { return t <= targetTime(goal, logs, day) ? .done : .late }
        case .AMOUNT:
            let got = amount(logs)
            if got >= goal.goalValue - 1e-9 { return .done }
            if over && got > 0 && !goal.weekly { return .partial }
        }
        if goal.weekly {
            return weekStart(day) == weekStart(today) && !(today < day) ? .pending : .off
        }
        return over ? .missed : .pending
    }

    static func isOverdue(_ goal: Goal, _ logs: [Log], _ day: Day, _ today: Day, _ nowMinutes: Int) -> Bool {
        goal.kind == .BY_TIME && day == today && result(goal, logs, day, today) == .pending && nowMinutes > targetTime(goal, logs, day)
    }

    /// The day a time logged now belongs to, and its minutes (past 24:00 for after midnight).
    static func nightAdjust(_ goal: Goal, _ today: Day, _ nowMinutes: Int, _ yesterdayLogs: [Log]) -> (Day, Int) {
        let yesterday = today.plusDays(-1)
        let target = targetTime(goal, yesterdayLogs, yesterday)
        if goal.kind == .BY_TIME && nowMinutes < nightCutoff && target >= 18 * 60 && isScheduled(goal, yesterday) && time(yesterdayLogs) == nil {
            return (yesterday, nowMinutes + 24 * 60)
        }
        return (today, nowMinutes)
    }

    // MARK: Weeks, for "X times a week" targets

    static func timesInWeek(_ goal: Goal, _ byDay: [Day: [Log]], _ monday: Day, _ today: Day) -> Int {
        (0..<7).map { monday.plusDays($0) }.filter { d in
            !(today < d) && isScheduled(goal, d) && !isPaused(goal, d) && reached(goal, byDay[d] ?? [], d)
        }.count
    }

    static func weekResult(_ goal: Goal, _ byDay: [Day: [Log]], _ monday: Day, _ today: Day) -> Outcome {
        let end = monday.plusDays(6)
        if end < goal.startDay { return .off }
        if (0..<7).allSatisfy({ isPaused(goal, monday.plusDays($0)) }) { return .skipped }
        if timesInWeek(goal, byDay, monday, today) >= goal.weeklyTimes { return .done }
        return end < today ? .missed : .pending
    }

    static func currentStreak(_ goal: Goal, _ byDay: [Day: [Log]], _ today: Day) -> Int {
        var streak = 0
        if goal.weekly {
            var monday = weekStart(today)
            while !(monday.plusDays(6) < goal.startDay) && streak < 600 {
                switch weekResult(goal, byDay, monday, today) {
                case .done: streak += 1
                case .pending, .skipped, .off: break
                default: return streak
                }
                monday = monday.plusDays(-7)
            }
            return streak
        }
        var day = today
        while !(day < goal.startDay) {
            switch result(goal, byDay[day] ?? [], day, today) {
            case .done: streak += 1
            case .off, .skipped, .pending: break
            default: return streak
            }
            day = day.plusDays(-1)
            if today.epochDay - day.epochDay > 3660 { break }
        }
        return streak
    }

    static func bestStreak(_ goal: Goal, _ byDay: [Day: [Log]], _ today: Day) -> Int {
        let first = max(goal.startDay, max(byDay.keys.min() ?? today, today.plusDays(-3660)))
        var best = 0, run = 0
        if goal.weekly {
            var monday = weekStart(first)
            while !(today < monday) {
                switch weekResult(goal, byDay, monday, today) {
                case .done:
                    run += 1
                    best = max(best, run)
                case .pending, .skipped, .off: break
                default: run = 0
                }
                monday = monday.plusDays(7)
            }
            return best
        }
        var day = first
        while !(today < day) {
            switch result(goal, byDay[day] ?? [], day, today) {
            case .done:
                run += 1
                best = max(best, run)
            case .off, .skipped, .pending: break
            default: run = 0
            }
            day = day.plusDays(1)
        }
        return best
    }

    struct Rate {
        let done: Int
        let counted: Int
        var fraction: Double? { counted == 0 ? nil : Double(done) / Double(counted) }
        var percent: Int? { fraction.map { Int(($0 * 100).rounded(.toNearestOrAwayFromZero)) } }
    }

    static func hitRate(_ goal: Goal, _ byDay: [Day: [Log]], _ from: Day, _ to: Day, _ today: Day) -> Rate {
        var done = 0, counted = 0
        if goal.weekly {
            var monday = weekStart(max(from, goal.startDay))
            while !(min(to, today) < monday) {
                switch weekResult(goal, byDay, monday, today) {
                case .done:
                    done += 1
                    counted += 1
                case .missed: counted += 1
                default: break
                }
                monday = monday.plusDays(7)
            }
            return Rate(done: done, counted: counted)
        }
        var day = max(from, goal.startDay)
        let end = min(to, today)
        while !(end < day) {
            let r = result(goal, byDay[day] ?? [], day, today)
            if r == .done { done += 1 }
            if r.counts { counted += 1 }
            day = day.plusDays(1)
        }
        return Rate(done: done, counted: counted)
    }

    struct DayScore {
        let day: Day
        let done: Int
        let scheduled: Int
        var fraction: Double { scheduled == 0 ? 0 : Double(done) / Double(scheduled) }
    }

    static func dayScore(_ goals: [(Goal, [Day: [Log]])], _ day: Day, _ today: Day) -> DayScore {
        var done = 0, scheduled = 0
        for (goal, byDay) in goals {
            let r = result(goal, byDay[day] ?? [], day, today)
            if goal.weekly {
                if r == .done {
                    done += 1
                    scheduled += 1
                } else if r == .pending && day == today && weekResult(goal, byDay, weekStart(day), today) == .pending {
                    scheduled += 1
                }
                continue
            }
            switch r {
            case .off, .skipped: break
            case .done:
                done += 1
                scheduled += 1
            default: scheduled += 1
            }
        }
        return DayScore(day: day, done: done, scheduled: scheduled)
    }

    static func goalText(_ goal: Goal, _ unit: String) -> String {
        let base: String
        switch goal.kind {
        case .CHECK: base = "Any time"
        case .BY_TIME: base = "By \(Times.format(goal.timeMinutes))" + (goal.dayTimes.isEmpty ? "" : " (varies)")
        case .AMOUNT: base = Amounts.withUnit(goal.goalValue, unit)
        }
        guard goal.weekly else { return base }
        return (goal.kind == .CHECK ? "" : "\(base), ") + "\(goal.weeklyTimes)× a week"
    }

    static func progressText(_ goal: Goal, _ logs: [Log], _ unit: String) -> String {
        switch goal.kind {
        case .CHECK: return logs.contains { $0.type == .DONE } ? "Done" : ""
        case .BY_TIME: return time(logs).map { Times.format($0) } ?? ""
        case .AMOUNT: return "\(Amounts.format(amount(logs))) / \(Amounts.withUnit(goal.goalValue, unit))"
        }
    }

    static func weekStart(_ day: Day) -> Day { day.plusDays(-Days.index(day)) }

    // MARK: Monthly report

    struct Month {
        let done: Int
        let counted: Int
        /// Index of the target with the longest streak now, and that streak.
        let bestStreak: (Int, Int)?
        /// Index of the target whose hit rate rose most on last month, and by how many points.
        let mostImproved: (Int, Int)?
    }

    static func month(_ goals: [(Goal, [Day: [Log]])], _ today: Day) -> Month {
        let first = today.firstOfMonth
        let prevFirst = first.plusMonths(-1)
        let prevLast = first.plusDays(-1)
        var done = 0, counted = 0
        var best: (Int, Int)?
        var improved: (Int, Int)?
        for (i, (goal, byDay)) in goals.enumerated() {
            let now = hitRate(goal, byDay, first, today, today)
            done += now.done
            counted += now.counted
            let streak = currentStreak(goal, byDay, today)
            if streak > 0 && (best == nil || streak > best!.1) { best = (i, streak) }
            let before = hitRate(goal, byDay, prevFirst, prevLast, today)
            if let a = now.percent, let b = before.percent, a - b > 0, improved == nil || a - b > improved!.1 { improved = (i, a - b) }
        }
        return Month(done: done, counted: counted, bestStreak: best, mostImproved: improved)
    }
}

struct Template {
    let emoji: String
    let name: String
    let kind: Kind
    var goalValue: Double = 1
    var timeMinutes: Int = 0
    var unit: String = ""
    var days: Int = Days.all
    var weeklyTimes: Int = 0
}

enum Templates {
    static let all: [Template] = [
        Template(emoji: "⏰", name: "Wake up", kind: .BY_TIME, timeMinutes: 7 * 60),
        Template(emoji: "📚", name: "At the library", kind: .BY_TIME, timeMinutes: 9 * 60, days: Days.weekdays),
        Template(emoji: "🏃", name: "Run", kind: .AMOUNT, goalValue: 5, unit: "km", weeklyTimes: 3),
        Template(emoji: "👟", name: "Steps", kind: .AMOUNT, goalValue: 10_000, unit: "steps"),
        Template(emoji: "🔥", name: "Burn calories", kind: .AMOUNT, goalValue: 500, unit: "kcal"),
        Template(emoji: "💪", name: "Be active", kind: .AMOUNT, goalValue: 60, unit: "min"),
        Template(emoji: "🏋️", name: "Gym", kind: .CHECK, weeklyTimes: 3),
        Template(emoji: "💧", name: "Drink water", kind: .AMOUNT, goalValue: 8, unit: "glasses"),
        Template(emoji: "📖", name: "Read", kind: .AMOUNT, goalValue: 20, unit: "pages"),
        Template(emoji: "🧘", name: "Meditate", kind: .CHECK),
        Template(emoji: "🌙", name: "In bed", kind: .BY_TIME, timeMinutes: 23 * 60),
    ]
    static let emojis = ["🎯", "⏰", "📚", "🏃", "👟", "🔥", "💪", "🏋️", "🚴", "🏊", "🧘", "💧", "📖", "🌙", "🥗", "🍎", "🚭", "📵", "✍️", "🙏", "🎸", "💻", "🧹", "☀️"]
}
