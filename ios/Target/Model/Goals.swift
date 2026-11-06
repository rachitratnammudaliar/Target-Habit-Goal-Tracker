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
    case NONE, STEPS, DISTANCE, MOVEMENT, RUN_DISTANCE, EXERCISE_MINUTES, ACTIVE_CALORIES
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
    case done, over, good, late, partial, missed, skipped, pending, off
    var label: String {
        switch self {
        case .done: return "Done"
        case .over: return "Overachieved"
        case .good: return "Satisfied"
        case .late: return "Late"
        case .partial: return "Partly achieved"
        case .missed: return "Missed"
        case .skipped: return "Skipped"
        case .pending: return "To do"
        case .off: return "Rest day"
        }
    }
    var counts: Bool { self == .done || self == .over || self == .good || self == .late || self == .partial || self == .missed }
    /// Counts as a hit: adds to a streak and to the hit rate.
    var hit: Bool { self == .done || self == .over || self == .good }
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
    /// AMOUNT: goalValue is a total for the week (15 hours of study), not a daily amount.
    var weekTotal: Bool = false

    var weekly: Bool { (weeklyTimes > 0 && kind != .BY_TIME) || (weekTotal && kind == .AMOUNT) }
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

    /// Hours: "1.5", "1,5" or "1:30" -> 1.5.
    static func parseHours(_ text: String) -> Double? {
        let t = text.trimmingCharacters(in: .whitespaces)
        if t.contains(":") {
            let parts = t.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 2, let h = Int(parts[0].trimmingCharacters(in: .whitespaces)), let m = Int(parts[1].trimmingCharacters(in: .whitespaces)),
                  h >= 0, (0...59).contains(m), !(h == 0 && m == 0), h <= 24 else { return nil }
            return round(Double(h) + Double(m) / 60)
        }
        guard let v = parse(t), v <= 24 else { return nil }
        return v
    }

    /// 0.5 -> "30 min", 1.5 -> "1 h 30", 2 -> "2 h".
    static func hoursText(_ h: Double) -> String {
        let total = Int((h * 60).rounded())
        if total < 60 { return "\(total) min" }
        return total % 60 == 0 ? "\(total / 60) h" : "\(total / 60) h \(total % 60)"
    }
}

enum Goals {
    /// 60% is partly achieved, 80% satisfied, 100% done, 130% overachieved.
    static let partialAt = 0.6
    static let goodAt = 0.8
    static let overAt = 1.3
    /// 10 km = 13,500 steps.
    static let stepsPerKm = 1350.0

    /// Kilometres you've effectively moved: the further of the distance and the steps.
    static func movementKm(_ km: Double, _ steps: Double) -> Double { Amounts.round(max(km, steps / stepsPerKm)) }

    static func tier(_ amount: Double, _ goal: Double) -> Outcome? {
        if goal <= 0 { return nil }
        if amount >= goal * overAt - 1e-9 { return .over }
        if amount >= goal - 1e-9 { return .done }
        if amount >= goal * goodAt - 1e-9 { return .good }
        if amount >= goal * partialAt - 1e-9 { return .partial }
        return nil
    }

    /// A time picked by hand. For an evening target (18:00 or later), 02:00 is after midnight:
    /// 26:00 and late. Picked for today it belongs to last night.
    static func pickAdjust(_ goal: Goal, _ day: Day, _ today: Day, _ minutes: Int, _ targetOn: (Day) -> Int) -> (Day, Int) {
        if goal.kind != .BY_TIME || minutes >= nightCutoff { return (day, minutes) }
        let night = (day == today && isScheduled(goal, day.plusDays(-1))) ? day.plusDays(-1) : day
        return targetOn(night) >= 18 * 60 ? (night, minutes + 24 * 60) : (day, minutes)
    }

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
            if goal.weekTotal {
                if got > 0 { return .done }
            } else {
                let t = tier(got, goal.goalValue)
                if t == .over || t == .done { return t! }
                if over && !goal.weekly, let t = t { return t }
            }
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
        if goal.weekTotal {
            let t = tier(weekAmount(goal, byDay, monday, today), goal.goalValue)
            if t == .over || t == .done { return t! }
            if !(end < today) { return .pending }
            return t ?? .missed
        }
        if timesInWeek(goal, byDay, monday, today) >= goal.weeklyTimes { return .done }
        return end < today ? .missed : .pending
    }

    /// weekTotal targets: the amount added up over the week starting [monday], up to today.
    static func weekAmount(_ goal: Goal, _ byDay: [Day: [Log]], _ monday: Day, _ today: Day) -> Double {
        Amounts.round((0..<7).map { monday.plusDays($0) }
            .filter { !(today < $0) && !($0 < goal.startDay) && !isPaused(goal, $0) }
            .reduce(0) { $0 + amount(byDay[$1] ?? []) })
    }

    static func currentStreak(_ goal: Goal, _ byDay: [Day: [Log]], _ today: Day) -> Int {
        var streak = 0
        if goal.weekly {
            var monday = weekStart(today)
            while !(monday.plusDays(6) < goal.startDay) && streak < 600 {
                let w = weekResult(goal, byDay, monday, today)
                if w.hit { streak += 1 } else if w == .pending || w == .skipped || w == .off || w == .partial {} else { return streak }
                monday = monday.plusDays(-7)
            }
            return streak
        }
        var day = today
        while !(day < goal.startDay) {
            let r = result(goal, byDay[day] ?? [], day, today)
            if r.hit { streak += 1 } else if r == .off || r == .skipped || r == .pending || r == .partial {} else { return streak }
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
                let w = weekResult(goal, byDay, monday, today)
                if w.hit {
                    run += 1
                    best = max(best, run)
                } else if w == .pending || w == .skipped || w == .off || w == .partial {} else { run = 0 }
                monday = monday.plusDays(7)
            }
            return best
        }
        var day = first
        while !(today < day) {
            let r = result(goal, byDay[day] ?? [], day, today)
            if r.hit {
                run += 1
                best = max(best, run)
            } else if r == .off || r == .skipped || r == .pending || r == .partial {} else { run = 0 }
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
                let w = weekResult(goal, byDay, monday, today)
                if w.hit {
                    done += 1
                    counted += 1
                } else if w == .missed || w == .partial { counted += 1 }
                monday = monday.plusDays(7)
            }
            return Rate(done: done, counted: counted)
        }
        var day = max(from, goal.startDay)
        let end = min(to, today)
        while !(end < day) {
            let r = result(goal, byDay[day] ?? [], day, today)
            if r.hit { done += 1 }
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
                if r.hit {
                    done += 1
                    scheduled += 1
                } else if r == .pending && day == today && weekResult(goal, byDay, weekStart(day), today) == .pending {
                    scheduled += 1
                }
                continue
            }
            switch r {
            case .off, .skipped: break
            case .done, .over, .good:
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
        case .AMOUNT: base = Amounts.withUnit(goal.goalValue, unit) + (goal.health == .MOVEMENT ? " or \(Amounts.format(goal.goalValue * stepsPerKm)) steps" : "")
        }
        if goal.weekTotal { return base + " a week" }
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

    // MARK: Weekly and monthly summary

    struct Summary {
        let from: Day
        let to: Day
        let done: Int
        let counted: Int
        let previousPercent: Int?
        /// 0 = Monday … 6 = Sunday.
        let bestDay: Int?
        let toughestDay: Int?
        let totals: [(String, Double)]
        let overachieved: Int
        let top: (Int, Int)?
        let weakest: (Int, Int)?
        var percent: Int? { counted == 0 ? nil : Int((Double(done) * 100 / Double(counted)).rounded()) }
    }

    static func summary(_ goals: [(Goal, [Day: [Log]])], units: [String], from: Day, to: Day, prevFrom: Day, prevTo: Day, today: Day) -> Summary {
        let end = min(to, today)
        var done = 0, counted = 0, prevDone = 0, prevCounted = 0, over = 0
        var perTarget: [(Int, Int)] = []
        var totals: [String: Double] = [:]
        for (i, (goal, byDay)) in goals.enumerated() {
            let rate = hitRate(goal, byDay, from, to, today)
            done += rate.done
            counted += rate.counted
            let before = hitRate(goal, byDay, prevFrom, prevTo, today)
            prevDone += before.done
            prevCounted += before.counted
            if let p = rate.percent { perTarget.append((i, p)) }
            if goal.weekly {
                var monday = weekStart(max(from, goal.startDay))
                while !(end < monday) {
                    if weekResult(goal, byDay, monday, today) == .over { over += 1 }
                    monday = monday.plusDays(7)
                }
            } else {
                var d = max(from, goal.startDay)
                while !(end < d) {
                    if result(goal, byDay[d] ?? [], d, today) == .over { over += 1 }
                    d = d.plusDays(1)
                }
            }
            if goal.kind == .AMOUNT {
                var d = max(from, goal.startDay)
                var sum = 0.0
                while !(end < d) {
                    if !isPaused(goal, d) { sum += amount(byDay[d] ?? []) }
                    d = d.plusDays(1)
                }
                let unit = i < units.count ? units[i] : ""
                if sum > 0 && !unit.isEmpty { totals[unit, default: 0] += sum }
            }
        }
        var byWeekday: [Int: [Double]] = [:]
        var d = from
        while !(end < d) {
            let sc = dayScore(goals, d, today)
            if sc.scheduled > 0 && d != today { byWeekday[Days.index(d), default: []].append(sc.fraction) }
            d = d.plusDays(1)
        }
        let avg = byWeekday.mapValues { $0.reduce(0, +) / Double($0.count) }
        let best = avg.max { $0.value < $1.value }
        let worst = avg.min { $0.value < $1.value }
        let spread = best != nil && worst != nil && best!.key != worst!.key && best!.value - worst!.value >= 0.15
        let order = ["km", "steps", "h", "min", "kcal"]
        let sorted = totals.sorted { (order.firstIndex(of: $0.key) ?? 99, $0.key) < (order.firstIndex(of: $1.key) ?? 99, $1.key) }
        return Summary(
            from: from, to: to, done: done, counted: counted,
            previousPercent: prevCounted == 0 ? nil : Int((Double(prevDone) * 100 / Double(prevCounted)).rounded()),
            bestDay: spread ? best!.key : nil, toughestDay: spread ? worst!.key : nil,
            totals: sorted.map { ($0.key, Amounts.round($0.value)) },
            overachieved: over,
            top: perTarget.count > 1 ? perTarget.max { $0.1 < $1.1 } : nil,
            weakest: perTarget.count > 1 ? perTarget.min { $0.1 < $1.1 } : nil
        )
    }

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
    var weekTotal: Bool = false
}

enum Templates {
    static let all: [Template] = [
        Template(emoji: "⏰", name: "Wake up", kind: .BY_TIME, timeMinutes: 7 * 60),
        Template(emoji: "📚", name: "At the library", kind: .BY_TIME, timeMinutes: 9 * 60, days: Days.weekdays),
        Template(emoji: "🚶", name: "Move", kind: .AMOUNT, goalValue: 10, unit: "km"),
        Template(emoji: "🏃", name: "Run", kind: .AMOUNT, goalValue: 5, unit: "km", weeklyTimes: 3),
        Template(emoji: "🎓", name: "Self-study", kind: .AMOUNT, goalValue: 15, unit: "h", weekTotal: true),
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
