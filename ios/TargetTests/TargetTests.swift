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
}
