import Foundation

/// A daily target. Field names match the Android app and the backup format.
struct Habit: Identifiable, Equatable {
    var id: Int64 = 0
    var name: String
    var emoji: String = "🎯"
    var kind: String = Kind.CHECK.rawValue
    var goalValue: Double = 1
    var timeMinutes: Int = 7 * 60
    var unit: String = ""
    var days: Int = Days.all
    var health: String = HealthSource.NONE.rawValue
    var step: Double = 1
    var startDay: Int = Day.today().epochDay
    var sortOrder: Int = 0
    var archived: Bool = false
    var notes: String = ""
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)

    var kindValue: Kind { Kind.from(kind) }
    var goal: Goal {
        Goal(kind: kindValue, goalValue: goalValue, timeMinutes: timeMinutes, days: days, startDay: Day(epochDay: startDay), health: HealthSource.from(health))
    }
}

struct Entry: Identifiable, Equatable {
    var id: Int64 = 0
    var targetId: Int64
    var epochDay: Int
    var type: String
    var value: Double = 1
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)

    var typeValue: LogType { LogType.from(type) }
    var log: Log { Log(type: typeValue, date: Day(epochDay: epochDay), value: value) }
}

/// The same JSON format as the Android app's backups.
enum Backup {
    static let format = "target-backup"

    enum BackupError: LocalizedError {
        case notABackup
        var errorDescription: String? { "This file isn't a Target backup." }
    }

    static func encode(_ targets: [Habit], _ entries: [Entry]) -> Data {
        let ids = Set(targets.map(\.id))
        let t: [[String: Any]] = targets.map { h in
            ["ref": h.id, "name": h.name, "emoji": h.emoji, "kind": h.kind, "goalValue": h.goalValue, "timeMinutes": h.timeMinutes,
             "unit": h.unit, "days": h.days, "health": h.health, "step": h.step, "startDay": h.startDay, "sortOrder": h.sortOrder,
             "archived": h.archived, "notes": h.notes, "createdAt": h.createdAt]
        }
        let l: [[String: Any]] = entries.filter { ids.contains($0.targetId) }.map { e in
            ["target": e.targetId, "epochDay": e.epochDay, "type": e.type, "value": e.value, "createdAt": e.createdAt]
        }
        let root: [String: Any] = ["format": format, "version": 1, "targets": t, "logs": l]
        return (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])) ?? Data()
    }

    private static func number(_ o: [String: Any], _ key: String) -> NSNumber? {
        guard let n = o[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n
    }

    private static func string(_ o: [String: Any], _ key: String, _ fallback: String = "") -> String { (o[key] as? String) ?? fallback }

    static func decode(_ data: Data) throws -> (targets: [Habit], entries: [Entry]) {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              root["format"] as? String == format else { throw BackupError.notABackup }
        var targets: [Habit] = []
        for (i, item) in (root["targets"] as? [Any] ?? []).enumerated() {
            guard let o = item as? [String: Any] else { continue }
            let name = string(o, "name").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, let ref = number(o, "ref")?.int64Value else { continue }
            func num(_ key: String, _ fallback: Double) -> Double {
                let v = number(o, key)?.doubleValue ?? fallback
                return v.isNaN || v < 0 ? fallback : min(v, 10_000_000)
            }
            let goalValue = num("goalValue", 1)
            let step = num("step", 1)
            let days = max(0, min(Days.all, number(o, "days")?.intValue ?? Days.all))
            let emoji = string(o, "emoji", "🎯")
            targets.append(Habit(
                id: ref,
                name: String(name.prefix(60)),
                emoji: emoji.isEmpty ? "🎯" : String(emoji.prefix(8)),
                kind: Kind.from(string(o, "kind")).rawValue,
                goalValue: goalValue > 0 ? goalValue : 1,
                timeMinutes: max(0, min(1439, number(o, "timeMinutes")?.intValue ?? 420)),
                unit: String(string(o, "unit").prefix(20)),
                days: days == 0 ? Days.all : days,
                health: HealthSource.from(string(o, "health")).rawValue,
                step: step > 0 ? step : 1,
                startDay: number(o, "startDay")?.intValue ?? 0,
                sortOrder: number(o, "sortOrder")?.intValue ?? i,
                archived: (o["archived"] as? Bool) ?? false,
                notes: String(string(o, "notes").prefix(500)),
                createdAt: number(o, "createdAt")?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000)
            ))
        }
        let refs = Set(targets.map(\.id))
        var entries: [Entry] = []
        for case let o as [String: Any] in root["logs"] as? [Any] ?? [] {
            guard let ref = number(o, "target")?.int64Value, refs.contains(ref),
                  let day = number(o, "epochDay")?.intValue,
                  let value = number(o, "value")?.doubleValue, !value.isNaN, value >= 0 else { continue }
            entries.append(Entry(targetId: ref, epochDay: day, type: LogType.from(string(o, "type")).rawValue, value: value,
                                 createdAt: number(o, "createdAt")?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000)))
        }
        return (targets, entries)
    }
}

/// Everything, saved on this iPhone only after every change.
@MainActor
final class Store: ObservableObject {
    @Published private(set) var targets: [Habit] = []
    @Published private(set) var entries: [Entry] = []
    @Published var problem: String?

    private let fileURL: URL
    private var nextEntryId: Int64 = 1

    init(directory: URL? = nil) {
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Target", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("targets.json")
        load()
    }

    // MARK: Reading

    var active: [Habit] { targets.filter { !$0.archived }.sorted { $0.sortOrder != $1.sortOrder ? $0.sortOrder < $1.sortOrder : $0.createdAt < $1.createdAt } }
    func target(_ id: Int64) -> Habit? { targets.first { $0.id == id } }
    func entries(_ id: Int64) -> [Entry] {
        entries.filter { $0.targetId == id }.sorted { $0.epochDay != $1.epochDay ? $0.epochDay > $1.epochDay : $0.createdAt > $1.createdAt }
    }
    func logsByDay(_ id: Int64) -> [Day: [Log]] { Dictionary(grouping: entries.filter { $0.targetId == id }.map(\.log), by: \.date) }
    func logs(_ id: Int64, _ day: Day) -> [Log] { entries.filter { $0.targetId == id && $0.epochDay == day.epochDay }.map(\.log) }
    var goals: [(Goal, [Day: [Log]])] { active.map { ($0.goal, logsByDay($0.id)) } }

    // MARK: Saving

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let contents = try Backup.decode(try Data(contentsOf: fileURL))
            targets = contents.targets
            entries = contents.entries.map { e in
                var copy = e
                copy.id = nextEntryId
                nextEntryId += 1
                return copy
            }
        } catch {
            let kept = fileURL.deletingLastPathComponent().appendingPathComponent("targets-unreadable-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: fileURL, to: kept)
            problem = "Your saved data couldn't be read, so Target started empty. The old file was kept."
        }
    }

    private func persist() {
        do {
            try Backup.encode(targets, entries).write(to: fileURL, options: [.atomic, .completeFileProtection])
        } catch {
            problem = "Couldn't save: \(error.localizedDescription)"
        }
    }

    @discardableResult
    func save(_ target: Habit) -> Int64 {
        if target.id == 0 {
            var copy = target
            copy.id = (targets.map(\.id).max() ?? 0) + 1
            copy.sortOrder = (targets.map(\.sortOrder).max() ?? 0) + 1
            targets.append(copy)
            persist()
            return copy.id
        }
        targets = targets.map { $0.id == target.id ? target : $0 }
        persist()
        return target.id
    }

    func delete(_ id: Int64) {
        targets.removeAll { $0.id == id }
        entries.removeAll { $0.targetId == id }
        persist()
    }

    func setArchived(_ id: Int64, _ archived: Bool) {
        guard var t = target(id) else { return }
        t.archived = archived
        save(t)
    }

    func move(_ id: Int64, by: Int) {
        var list = active
        guard let i = list.firstIndex(where: { $0.id == id }), list.indices.contains(i + by) else { return }
        list.insert(list.remove(at: i), at: i + by)
        for (index, t) in list.enumerated() {
            if let k = targets.firstIndex(where: { $0.id == t.id }) { targets[k].sortOrder = index }
        }
        persist()
    }

    @discardableResult
    private func add(_ targetId: Int64, _ day: Day, _ type: LogType, _ value: Double = 1) -> Int64 {
        entries.append(Entry(id: nextEntryId, targetId: targetId, epochDay: day.epochDay, type: type.rawValue, value: value))
        nextEntryId += 1
        persist()
        return nextEntryId - 1
    }

    private func removeAll(_ targetId: Int64, _ day: Day, _ type: LogType) {
        entries.removeAll { $0.targetId == targetId && $0.epochDay == day.epochDay && $0.type == type.rawValue }
    }

    /// Done, or undone if it already was. Returns true if it's now done.
    @discardableResult
    func toggleDone(_ t: Habit, _ day: Day) -> Bool {
        if entries.contains(where: { $0.targetId == t.id && $0.epochDay == day.epochDay && $0.type == LogType.DONE.rawValue }) {
            removeAll(t.id, day, .DONE)
            persist()
            return false
        }
        add(t.id, day, .DONE)
        return true
    }

    /// Done at [minutes] after midnight; replaces an earlier time for that day.
    @discardableResult
    func logTime(_ t: Habit, _ day: Day, _ minutes: Int) -> Int64 {
        removeAll(t.id, day, .TIME)
        return add(t.id, day, .TIME, Double(minutes))
    }

    @discardableResult
    func addAmount(_ t: Habit, _ day: Day, _ amount: Double) -> Int64? {
        guard amount > 0 else { return nil }
        return add(t.id, day, .AMOUNT, amount)
    }

    func toggleSkip(_ t: Habit, _ day: Day) {
        if entries.contains(where: { $0.targetId == t.id && $0.epochDay == day.epochDay && $0.type == LogType.SKIP.rawValue }) {
            removeAll(t.id, day, .SKIP)
            persist()
        } else {
            add(t.id, day, .SKIP)
        }
    }

    func clearDay(_ t: Habit, _ day: Day) {
        [LogType.DONE, .TIME, .AMOUNT].forEach { removeAll(t.id, day, $0) }
        persist()
    }

    func deleteEntry(_ id: Int64) {
        entries.removeAll { $0.id == id }
        persist()
    }

    // MARK: Backup

    func exportFile() throws -> URL {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("target-backup-\(f.string(from: Date())).json")
        try Backup.encode(targets, entries).write(to: url, options: .atomic)
        return url
    }

    func importData(_ data: Data) throws -> Int {
        let contents = try Backup.decode(data)
        var next = (targets.map(\.id).max() ?? 0) + 1
        let sortStart = (targets.map(\.sortOrder).max() ?? 0) + 1
        for (i, t) in contents.targets.enumerated() {
            var copy = t
            copy.id = next
            copy.sortOrder = sortStart + i
            targets.append(copy)
            for e in contents.entries where e.targetId == t.id {
                var ev = e
                ev.id = nextEntryId
                ev.targetId = next
                nextEntryId += 1
                entries.append(ev)
            }
            next += 1
        }
        persist()
        return contents.targets.count
    }
}
