import SwiftUI

@main
struct TargetApp: App {
    @StateObject private var store = Store()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .tint(Color(rgb: 0x4F46E5))
        }
    }
}

extension Color {
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

struct RootView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.scenePhase) private var phase
    @State private var today = Day.today()

    var body: some View {
        TabView {
            TodayView(today: today).tabItem { Label("Today", systemImage: "target") }
            ProgressTab(today: today).tabItem { Label("Progress", systemImage: "chart.bar") }
        }
        .onChange(of: phase) { p in if p == .active { today = Day.today() } }
        .alert("Something went wrong", isPresented: Binding(get: { store.problem != nil }, set: { if !$0 { store.problem = nil } })) {
            Button("OK") { store.problem = nil }
        } message: {
            Text(store.problem ?? "")
        }
    }
}

/// Colours for each result, the same everywhere.
func resultColor(_ r: Outcome) -> Color {
    switch r {
    case .done: return Color(rgb: 0x16A34A)
    case .over: return Color(rgb: 0x0891B2)
    case .good: return Color(rgb: 0x65A30D)
    case .late, .partial: return Color(rgb: 0xD97706)
    case .missed: return Color(rgb: 0xDC2626)
    case .skipped: return Color(rgb: 0x94A3B8)
    case .pending: return Color.secondary.opacity(0.3)
    case .off: return Color.secondary.opacity(0.12)
    }
}

func dayLabel(_ day: Day, _ today: Day) -> String {
    if day == today { return "Today" }
    if day == today.plusDays(-1) { return "Yesterday" }
    return "\(Days.names[Days.index(day)]), \(day.format(.short))"
}

struct EmojiBadge: View {
    let emoji: String
    let tint: Color
    var size: CGFloat = 44

    var body: some View {
        Text(emoji).font(.system(size: size * 0.5))
            .frame(width: size, height: size)
            .background(Circle().fill(tint.opacity(0.16)))
    }
}

struct ProgressRing: View {
    let fraction: Double
    var size: CGFloat = 80
    var label: String

    var body: some View {
        ZStack {
            Circle().stroke(Color.accentColor.opacity(0.15), lineWidth: 10)
            Circle().trim(from: 0, to: max(0, min(1, fraction)))
                .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(label).font(.headline)
        }
        .frame(width: size, height: size)
    }
}

struct StatTile: View {
    let value: String
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.weight(.bold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.caption).foregroundColor(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(.secondarySystemBackground)))
    }
}

/// A calendar heatmap: one column per week, Monday at the top.
struct Heatmap: View {
    let weeks: [[Outcome?]]
    var cell: CGFloat = 13

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(weeks.enumerated()), id: \.offset) { _, week in
                VStack(spacing: 3) {
                    ForEach(Array(week.enumerated()), id: \.offset) { _, r in
                        RoundedRectangle(cornerRadius: 3).fill(r.map { resultColor($0) } ?? .clear).frame(width: cell, height: cell)
                    }
                }
            }
        }
    }
}

struct HeatmapKey: View {
    var body: some View {
        HStack(spacing: 10) {
            ForEach([Outcome.over, .done, .good, .late, .missed, .skipped, .off], id: \.label) { r in
                HStack(spacing: 4) {
                    RoundedRectangle(cornerRadius: 2).fill(resultColor(r)).frame(width: 10, height: 10)
                    Text(r == .late ? "Late / partly" : r.label).font(.caption2).foregroundColor(.secondary)
                }
            }
        }
    }
}

@MainActor
func heatWeeks(_ t: Habit, _ store: Store, _ today: Day, _ weeks: Int) -> [[Outcome?]] {
    let goal = t.goal
    let byDay = store.logsByDay(t.id)
    let first = Goals.weekStart(today).plusDays(-7 * (weeks - 1))
    return (0..<weeks).map { w in
        (0..<7).map { d in
            let day = first.plusDays(w * 7 + d)
            return today < day ? nil : Goals.result(goal, byDay[day] ?? [], day, today)
        }
    }
}

/// Undo messages shown for a few seconds.
struct UndoState: Equatable {
    let id = UUID()
    let message: String
    let undo: (() -> Void)?
    static func == (a: UndoState, b: UndoState) -> Bool { a.id == b.id }
}

func showUndo(_ state: Binding<UndoState?>, _ message: String, _ undo: (() -> Void)? = nil) {
    let new = UndoState(message: message, undo: undo)
    withAnimation { state.wrappedValue = new }
    DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
        if state.wrappedValue == new { withAnimation { state.wrappedValue = nil } }
    }
}

extension View {
    func undoOverlay(_ state: Binding<UndoState?>) -> some View {
        overlay(alignment: .bottom) {
            if let u = state.wrappedValue {
                HStack {
                    Text(u.message).foregroundColor(.white).lineLimit(2)
                    Spacer()
                    if let undo = u.undo {
                        Button("Undo") {
                            undo()
                            withAnimation { state.wrappedValue = nil }
                        }
                        .foregroundColor(Color(rgb: 0xB4B0FF))
                    }
                }
                .padding()
                .background(RoundedRectangle(cornerRadius: 14).fill(Color.black.opacity(0.85)))
                .padding()
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }
}
