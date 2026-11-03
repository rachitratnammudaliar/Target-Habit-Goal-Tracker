import Foundation

// Calendar days without times, the same as Kotlin LocalDate epoch days.

// MARK: - Day

/// A calendar date without a time, stored as days since 1970-01-01 (the same as Kotlin's epoch day).
struct Day: Hashable, Comparable, Codable {
    let epochDay: Int

    init(epochDay: Int) { self.epochDay = epochDay }

    private static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    init(_ year: Int, _ month: Int, _ day: Int) {
        let date = Day.calendar.date(from: DateComponents(year: year, month: month, day: day))!
        self.epochDay = Int((date.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    /// Today in the phone's own time zone.
    static func today() -> Day { Day(localDate: Date()) }

    /// The calendar day a moment falls on, in the phone's time zone.
    init(localDate: Date) {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: localDate)
        self.init(c.year!, c.month!, c.day!)
    }

    var date: Date { Date(timeIntervalSince1970: TimeInterval(epochDay) * 86_400) }

    /// Midnight of this day in the phone's time zone, for date pickers.
    var localDate: Date {
        let c = Day.calendar.dateComponents([.year, .month, .day], from: date)
        return Calendar.current.date(from: c) ?? date
    }

    var components: (year: Int, month: Int, day: Int) {
        let c = Day.calendar.dateComponents([.year, .month, .day], from: date)
        return (c.year!, c.month!, c.day!)
    }

    var lengthOfMonth: Int { Day.calendar.range(of: .day, in: .month, for: date)!.count }

    var firstOfMonth: Day { let c = components; return Day(c.year, c.month, 1) }

    var lastOfMonth: Day { let c = components; return Day(c.year, c.month, lengthOfMonth) }

    func plusDays(_ n: Int) -> Day { Day(epochDay: epochDay + n) }

    /// Adds months like java.time: the day is clamped to the month's length (31 Jan + 1 month = 28/29 Feb).
    func plusMonths(_ n: Int) -> Day {
        let c = components
        let total = c.year * 12 + (c.month - 1) + n
        let year = Int((Double(total) / 12).rounded(.down))
        let month = total - year * 12 + 1
        let length = Day(year, month, 1).lengthOfMonth
        return Day(year, month, min(c.day, length))
    }

    func plusYears(_ n: Int) -> Day { plusMonths(n * 12) }

    static func < (a: Day, b: Day) -> Bool { a.epochDay < b.epochDay }

    func format(_ style: DayStyle) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_GB")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = style == .long ? "d MMM yyyy" : "d MMM"
        return f.string(from: date)
    }

    var monthName: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_GB")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "MMMM"
        return f.string(from: date)
    }
}

enum DayStyle { case long, short }
