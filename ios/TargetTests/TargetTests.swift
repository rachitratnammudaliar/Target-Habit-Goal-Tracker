import XCTest
@testable import Target

/// Mirrors the Kotlin GoalsTest and BackupTest so both apps calculate the same.
final class TargetTests: XCTestCase {
    private let today = Day(2026, 11, 4) // a Wednesday
    private func d(_ ago: Int) -> Day { today.plusDays(-ago) }
    private lazy var wake = Goal(kind: .BY_TIME, timeMinutes: 420, startDay: d(60))
    private lazy var run = Goal(kind: .AMOUNT, goalValue: 10, startDay: d(60), health: .RUN_DISTANCE)
    private lazy var gym = Goal(kind: .CHECK, days: Days.bit(0) | Days.bit(2), startDay: d(60))

    func testByTime() {
        XCTAssertEqual(Goals.result(wake, [Log(type: .TIME, date: d(1), value: 415)], d(1), today), .done)
        XCTAssertEqual(Goals.result(wake, [Log(type: .TIME, date: d(1), value: 420)], d(1), today), .done)
        XCTAssertEqual(Goals.result(wake, [Log(type: .TIME, date: d(1), value: 425)], d(1), today), .late)
        XCTAssertEqual(Goals.result(wake, [Log(type: .TIME, date: d(1), value: 500), Log(type: .TIME, date: d(1), value: 410)], d(1), today), .done)
        XCTAssertEqual(Goals.result(wake, [], d(1), today), .missed)
        XCTAssertEqual(Goals.result(wake, [], today, today), .pending)
    }

    func testAmounts() {
        XCTAssertEqual(Goals.result(run, [Log(type: .HEALTH, date: today, value: 10.2)], today, today), .done)
        XCTAssertEqual(Goals.result(run, [Log(type: .HEALTH, date: today, value: 6)], today, today), .pending)
        XCTAssertEqual(Goals.result(run, [Log(type: .HEALTH, date: d(1), value: 6)], d(1), today), .partial)
        let mixed = [Log(type: .AMOUNT, date: d(2), value: 4), Log(type: .AMOUNT, date: d(2), value: 6), Log(type: .HEALTH, date: d(2), value: 7)]
        XCTAssertEqual(Goals.amount(mixed), 10)
        XCTAssertEqual(Goals.result(run, mixed, d(2), today), .done)
        XCTAssertEqual(Goals.progressText(run, [Log(type: .HEALTH, date: today, value: 6)], "km"), "6 / 10 km")
    }

    func testScheduleAndSkips() {
        XCTAssertEqual(Days.index(today), 2)
        XCTAssertEqual(Goals.result(gym, [], d(1), today), .off)
        XCTAssertEqual(Goals.result(gym, [], d(2), today), .missed)
        XCTAssertEqual(Goals.result(gym, [Log(type: .SKIP, date: d(2))], d(2), today), .skipped)
    }

    func testStreaks() {
        var logs: [Day: [Log]] = [:]
        for i in 1...5 { logs[d(i)] = [Log(type: .TIME, date: d(i), value: 410)] }
        logs[d(3)] = [Log(type: .SKIP, date: d(3))]
        logs[d(6)] = [Log(type: .TIME, date: d(6), value: 450)]
        for i in 7...15 { logs[d(i)] = [Log(type: .TIME, date: d(i), value: 400)] }
        XCTAssertEqual(Goals.currentStreak(wake, logs, today), 4)
        logs[today] = [Log(type: .TIME, date: today, value: 405)]
        XCTAssertEqual(Goals.currentStreak(wake, logs, today), 5)
        XCTAssertEqual(Goals.bestStreak(wake, logs, today), 9)
        let gymLogs: [Day: [Log]] = [d(2): [Log(type: .DONE, date: d(2))], d(7): [Log(type: .DONE, date: d(7))], d(9): [Log(type: .DONE, date: d(9))]]
        XCTAssertEqual(Goals.currentStreak(gym, gymLogs, today), 3)
    }

    func testRatesAndScores() {
        let logs: [Day: [Log]] = [d(1): [Log(type: .TIME, date: d(1), value: 410)], d(2): [Log(type: .TIME, date: d(2), value: 480)], d(3): [Log(type: .SKIP, date: d(3))]]
        let rate = Goals.hitRate(wake, logs, d(4), today, today)
        XCTAssertEqual(rate.done, 1)
        XCTAssertEqual(rate.counted, 3)
        XCTAssertEqual(rate.percent, 33)
        let score = Goals.dayScore([(wake, logs), (gym, [:]), (run, [d(1): [Log(type: .HEALTH, date: d(1), value: 12)]])], d(1), today)
        XCTAssertEqual(score.done, 2)
        XCTAssertEqual(score.scheduled, 2)
    }

    func testFormatting() {
        XCTAssertEqual(Times.format(420), "07:00")
        XCTAssertEqual(Times.parse("7:05"), 425)
        XCTAssertEqual(Times.parse("705"), 425)
        XCTAssertNil(Times.parse("25:00"))
        XCTAssertEqual(Amounts.format(10_000), "10,000")
        XCTAssertEqual(Amounts.format(6.25), "6.25")
        XCTAssertEqual(Amounts.parse("1,5"), 1.5)
        XCTAssertEqual(Days.label(Days.weekdays), "Weekdays")
        XCTAssertEqual(Days.label(gym.days), "Mon, Wed")
        XCTAssertEqual(Goals.weekStart(today), Day(2026, 11, 2))
    }

    func testBackupMatchesAndroid() throws {
        let json = """
        {"format":"target-backup","version":1,"targets":[
          {"ref":4,"name":"Run","emoji":"🏃","kind":"AMOUNT","goalValue":10,"unit":"km","days":31,"health":"RUN_DISTANCE","step":1,"startDay":20000,"sortOrder":2,"archived":false,"notes":"","createdAt":5},
          {"ref":5,"name":" "}],
         "logs":[{"target":4,"epochDay":20010,"type":"HEALTH","value":6.4,"createdAt":1},{"target":5,"epochDay":20010,"type":"DONE","value":1},{"target":4,"value":1}]}
        """
        let back = try Backup.decode(Data(json.utf8))
        XCTAssertEqual(back.targets.count, 1)
        XCTAssertEqual(back.targets[0].health, "RUN_DISTANCE")
        XCTAssertEqual(back.targets[0].days, Days.weekdays)
        XCTAssertEqual(back.entries.count, 1)
        let again = try Backup.decode(Backup.encode(back.targets, back.entries))
        XCTAssertEqual(again.targets, back.targets)
        XCTAssertThrowsError(try Backup.decode(Data(#"{"format":"kitchtally-backup"}"#.utf8)))
    }

    func testTimesPerWeekdayAndForOneDay() {
        var library = wake
        library.timeMinutes = 540
        library.dayTimes = [2: 600]
        XCTAssertEqual(Goals.targetTime(library, [], d(1)), 600)
        XCTAssertEqual(Goals.targetTime(library, [], today), 540)
        XCTAssertEqual(Goals.result(library, [Log(type: .TIME, date: d(1), value: 590)], d(1), today), .done)
        XCTAssertEqual(Goals.result(library, [Log(type: .TIME, date: today, value: 590)], today, today), .late)
        let moved = [Log(type: .TARGET, date: today, value: 660), Log(type: .TIME, date: today, value: 650)]
        XCTAssertEqual(Goals.result(library, moved, today, today), .done)
        var earlier = wake
        earlier.timeMinutes = 400
        XCTAssertEqual(Goals.result(earlier, [Log(type: .TARGET, date: d(2), value: 420), Log(type: .TIME, date: d(2), value: 415)], d(2), today), .done)
        XCTAssertEqual(DayTimes.parse(DayTimes.format([2: 600, 1: 540])), [1: 540, 2: 600])
        XCTAssertEqual(DayTimes.parse("3=60;x;9=10;2=9999"), [3: 60])
    }

    func testOverdueAndAfterMidnight() {
        XCTAssertTrue(Goals.isOverdue(wake, [], today, today, 421))
        XCTAssertFalse(Goals.isOverdue(wake, [], today, today, 420))
        XCTAssertFalse(Goals.isOverdue(wake, [Log(type: .TIME, date: today, value: 400)], today, today, 600))
        var bed = wake
        bed.timeMinutes = 23 * 60
        let (day, minutes) = Goals.nightAdjust(bed, today, 30, [])
        XCTAssertEqual(day, d(1))
        XCTAssertEqual(minutes, 24 * 60 + 30)
        XCTAssertEqual(Goals.result(bed, [Log(type: .TIME, date: d(1), value: Double(minutes))], d(1), today), .late)
        XCTAssertEqual(Times.format(minutes), "00:30")
        XCTAssertEqual(Goals.nightAdjust(wake, today, 30, []).0, today)
        XCTAssertEqual(Goals.nightAdjust(bed, today, 300, []).1, 300)
    }

    func testTimesAWeek() {
        let gym3 = Goal(kind: .CHECK, startDay: d(60), weeklyTimes: 3)
        var logs: [Day: [Log]] = [:]
        func done(_ ago: Int...) { for a in ago { logs[d(a)] = [Log(type: .DONE, date: d(a))] } }
        done(16, 14, 12, 8, 6, 0)
        XCTAssertEqual(Goals.weekResult(gym3, logs, Day(2026, 10, 19), today), .done)
        XCTAssertEqual(Goals.weekResult(gym3, logs, Day(2026, 10, 26), today), .missed)
        XCTAssertEqual(Goals.weekResult(gym3, logs, Goals.weekStart(today), today), .pending)
        XCTAssertEqual(Goals.result(gym3, [], d(5), today), .off)
        XCTAssertEqual(Goals.result(gym3, [], d(1), today), .pending)
        XCTAssertEqual(Goals.result(gym3, logs[today] ?? [], today, today), .done)
        XCTAssertEqual(Goals.currentStreak(gym3, logs, today), 0)
        XCTAssertEqual(Goals.bestStreak(gym3, logs, today), 1)
        let rate = Goals.hitRate(gym3, logs, Day(2026, 10, 19), today, today)
        XCTAssertEqual(rate.done, 1)
        XCTAssertEqual(rate.counted, 2)
        done(1, 2)
        XCTAssertEqual(Goals.weekResult(gym3, logs, Goals.weekStart(today), today), .done)
        XCTAssertEqual(Goals.currentStreak(gym3, logs, today), 1)
        XCTAssertEqual(Goals.goalText(gym3, ""), "3× a week")
        var run5 = run
        run5.goalValue = 5
        run5.weeklyTimes = 3
        XCTAssertEqual(Goals.goalText(run5, "km"), "5 km, 3× a week")
        var wake3 = wake
        wake3.weeklyTimes = 3
        XCTAssertFalse(wake3.weekly)
    }

    func testPausedDaysAreSkipped() {
        var paused = wake
        paused.pauseStart = d(3)
        paused.pauseEnd = d(1)
        XCTAssertEqual(Goals.result(paused, [], d(2), today), .skipped)
        XCTAssertEqual(Goals.result(paused, [], d(4), today), .missed)
        let logs: [Day: [Log]] = [d(4): [Log(type: .TIME, date: d(4), value: 400)], d(5): [Log(type: .TIME, date: d(5), value: 400)]]
        XCTAssertEqual(Goals.currentStreak(paused, logs, today), 2)
    }

    func testMonthlyReport() {
        var wakeLogs: [Day: [Log]] = [:]
        for day in [Day(2026, 11, 1), Day(2026, 11, 2), Day(2026, 11, 3), Day(2026, 10, 31)] { wakeLogs[day] = [Log(type: .TIME, date: day, value: 400)] }
        let m = Goals.month([(wake, wakeLogs), (gym, [:])], today)
        XCTAssertEqual(m.done, 3)
        XCTAssertEqual(m.bestStreak?.0, 0)
        XCTAssertEqual(m.bestStreak?.1, 4)
        XCTAssertEqual(m.mostImproved?.0, 0)
    }

    func testBackupV2KeepsNewFields() throws {
        var h = Habit(id: 1, name: "Library", kind: Kind.BY_TIME.rawValue, timeMinutes: 540)
        h.dayTimes = "2=600"
        h.pauseStart = 20000
        h.pauseEnd = 20003
        var g = Habit(id: 2, name: "Gym")
        g.weeklyTimes = 3
        let entries = [Entry(targetId: 1, epochDay: 20001, type: "NOTE", note: "exam week"), Entry(targetId: 1, epochDay: 20001, type: "TARGET", value: 660)]
        let back = try Backup.decode(Backup.encode([h, g], entries))
        XCTAssertEqual(back.targets[0].dayTimes, "2=600")
        XCTAssertEqual(back.targets[0].pauseEnd, 20003)
        XCTAssertEqual(back.targets[1].weeklyTimes, 3)
        XCTAssertEqual(back.entries.first { $0.type == "NOTE" }?.note, "exam week")
        XCTAssertEqual(back.entries.first { $0.type == "TARGET" }?.value, 660)
    }

    func testAmountLevels() {
        let r10 = Goal(kind: .AMOUNT, goalValue: 10, startDay: d(60))
        func day(_ km: Double) -> Outcome { Goals.result(r10, [Log(type: .HEALTH, date: d(1), value: km)], d(1), today) }
        XCTAssertEqual(day(13), .over)
        XCTAssertEqual(day(10), .done)
        XCTAssertEqual(day(12.9), .done)
        XCTAssertEqual(day(8), .good)
        XCTAssertEqual(day(9.9), .good)
        XCTAssertEqual(day(6), .partial)
        XCTAssertEqual(day(7.9), .partial)
        XCTAssertEqual(day(5.9), .missed)
        XCTAssertEqual(Goals.result(r10, [Log(type: .HEALTH, date: today, value: 8)], today, today), .pending)
        XCTAssertEqual(Goals.result(r10, [Log(type: .HEALTH, date: today, value: 14)], today, today), .over)
        XCTAssertTrue(Outcome.good.hit && Outcome.over.hit && !Outcome.partial.hit)
    }

    func testMovementCountsDistanceOrStepsWhicheverIsMore() {
        XCTAssertEqual(Goals.movementKm(3, 13_500), 10, accuracy: 1e-9)
        XCTAssertEqual(Goals.movementKm(10, 4_000), 10, accuracy: 1e-9)
        var move = Goal(kind: .AMOUNT, goalValue: 10)
        move.health = .MOVEMENT
        XCTAssertEqual(Goals.goalText(move, "km"), "10 km or 13,500 steps")
    }

    func testPartlyDaysKeepAStreakAliveButDontAddToIt() {
        let goal = Goal(kind: .AMOUNT, goalValue: 10, startDay: d(30))
        let logs: [Day: [Log]] = [
            d(1): [Log(type: .HEALTH, date: d(1), value: 11)],
            d(2): [Log(type: .HEALTH, date: d(2), value: 6.5)],
            d(3): [Log(type: .HEALTH, date: d(3), value: 9)],
            d(4): [Log(type: .HEALTH, date: d(4), value: 10)],
            d(5): [Log(type: .HEALTH, date: d(5), value: 3)],
        ]
        XCTAssertEqual(Goals.currentStreak(goal, logs, today), 3)
        let rate = Goals.hitRate(goal, logs, d(5), d(1), today)
        XCTAssertEqual(rate.done, 3)
        XCTAssertEqual(rate.counted, 5)
    }

    func testHoursForTheWeek() {
        var study = Goal(kind: .AMOUNT, goalValue: 15, startDay: d(60))
        study.weekTotal = true
        XCTAssertTrue(study.weekly)
        let monday = Goals.weekStart(today)
        let logs: [Day: [Log]] = [
            monday: [Log(type: .AMOUNT, date: monday, value: 3)],
            monday.plusDays(1): [Log(type: .AMOUNT, date: monday.plusDays(1), value: 2.5), Log(type: .AMOUNT, date: monday.plusDays(1), value: 1)],
        ]
        XCTAssertEqual(Goals.weekAmount(study, logs, monday, today), 6.5, accuracy: 1e-9)
        XCTAssertEqual(Goals.weekResult(study, logs, monday, today), .pending)
        XCTAssertEqual(Goals.goalText(study, "h"), "15 h a week")
        let last = monday.plusDays(-7)
        func week(_ total: Double) -> [Day: [Log]] { [last: [Log(type: .AMOUNT, date: last, value: total)]] }
        XCTAssertEqual(Goals.weekResult(study, week(20), last, today), .over)
        XCTAssertEqual(Goals.weekResult(study, week(15), last, today), .done)
        XCTAssertEqual(Goals.weekResult(study, week(12), last, today), .good)
        XCTAssertEqual(Goals.weekResult(study, week(9), last, today), .partial)
        XCTAssertEqual(Goals.weekResult(study, week(4), last, today), .missed)
        XCTAssertEqual(Goals.currentStreak(study, week(15), today), 1)
        XCTAssertEqual(Goals.result(study, logs[monday]!, monday, today), .done)
        XCTAssertEqual(Goals.result(study, [], d(10), today), .off)
    }

    func testParsingHours() {
        XCTAssertEqual(Amounts.parseHours("1.5"), 1.5)
        XCTAssertEqual(Amounts.parseHours("1,5"), 1.5)
        XCTAssertEqual(Amounts.parseHours("1:30"), 1.5)
        XCTAssertEqual(Amounts.parseHours("0:45"), 0.75)
        XCTAssertNil(Amounts.parseHours("0:00"))
        XCTAssertNil(Amounts.parseHours("1:75"))
        XCTAssertNil(Amounts.parseHours("30"))
        XCTAssertNil(Amounts.parseHours("abc"))
        XCTAssertEqual(Amounts.hoursText(0.5), "30 min")
        XCTAssertEqual(Amounts.hoursText(1.5), "1 h 30")
        XCTAssertEqual(Amounts.hoursText(2), "2 h")
    }

    func testATimePickedAfterMidnightForABedtimeIsLateForThatNight() {
        let bed = Goal(kind: .BY_TIME, timeMinutes: 23 * 60 + 45, startDay: d(30))
        let target: (Day) -> Int = { _ in 23 * 60 + 45 }
        let (day, minutes) = Goals.pickAdjust(bed, today, today, 120, target)
        XCTAssertEqual(day, d(1))
        XCTAssertEqual(minutes, 26 * 60)
        XCTAssertEqual(Goals.result(bed, [Log(type: .TIME, date: day, value: Double(minutes))], day, today), .late)
        XCTAssertEqual(Goals.pickAdjust(bed, d(3), today, 120, target).0, d(3))
        XCTAssertEqual(Goals.pickAdjust(bed, d(3), today, 120, target).1, 26 * 60)
        XCTAssertEqual(Goals.pickAdjust(bed, today, today, 23 * 60 + 30, target).1, 23 * 60 + 30)
        XCTAssertEqual(Goals.pickAdjust(bed, today, today, 240, target).1, 240)
        let wake = Goal(kind: .BY_TIME, timeMinutes: 7 * 60)
        XCTAssertEqual(Goals.pickAdjust(wake, today, today, 90) { _ in 7 * 60 }.1, 90)
        XCTAssertEqual(Goals.pickAdjust(bed, d(1), today, 26 * 60, target).1, 26 * 60)
    }

    func testWeeklySummary() {
        let move = Goal(kind: .AMOUNT, goalValue: 10, startDay: d(40))
        let byDay: [Day: [Log]] = [
            d(3): [Log(type: .HEALTH, date: d(3), value: 9)],
            d(2): [Log(type: .HEALTH, date: d(2), value: 12)],
            d(1): [Log(type: .HEALTH, date: d(1), value: 14)],
        ]
        let monday = Goals.weekStart(today)
        let s = Goals.summary([(move, byDay)], units: ["km"], from: monday, to: monday.plusDays(6), prevFrom: monday.plusDays(-7), prevTo: monday.plusDays(-1), today: today)
        XCTAssertEqual(s.done, 2)
        XCTAssertEqual(s.counted, 2)
        XCTAssertEqual(s.overachieved, 1)
        XCTAssertEqual(s.totals.count, 1)
        XCTAssertEqual(s.totals[0].0, "km")
        XCTAssertEqual(s.totals[0].1, 26, accuracy: 1e-9)
        XCTAssertEqual(s.percent, 100)
        XCTAssertEqual(s.previousPercent, 14)
    }

    func testBackupKeepsWeekTotal() throws {
        var h = Habit(id: 1, name: "Self-study", kind: Kind.AMOUNT.rawValue, goalValue: 15)
        h.unit = "h"
        h.weekTotal = true
        let back = try Backup.decode(Backup.encode([h], []))
        XCTAssertTrue(back.targets[0].weekTotal)
        XCTAssertTrue(back.targets[0].goal.weekly)
    }
}
