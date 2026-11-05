import SwiftUI
import HerculesShared

/// Plan: big moments up to 7 days out, as stops on a countdown rail.
struct PlanView: View {
    let events: [EventStatus]
    let onOpen: (String) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Eyebrow("Countdown")
                Text("Big moments ahead")
                    .font(.system(size: 28, weight: .light))
                    .foregroundStyle(Palette.text1)
                    .padding(.top, 6)
                    .padding(.bottom, 20)
                if events.isEmpty {
                    Illustration("ill_plan").frame(height: 240).frame(maxWidth: .infinity)
                    Text("Nothing on the runway").font(.system(size: 17)).foregroundStyle(Palette.text1).padding(.top, 8)
                    Muted("Add an interview, pitch or exam up to 7 days out. Hercules builds the run-up for you.").padding(.top, 2)
                }
                ForEach(Array(events.enumerated()), id: \.element.event.id) { i, s in
                    RailRow(s: s, first: i == 0, last: i == events.count - 1) { onOpen(s.event.id) }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 96)
        }
        .scrollIndicators(.hidden)
    }
}

/// One stop on the countdown rail: node + D-x on the left, the event on the right.
private struct RailRow: View {
    let s: EventStatus
    let first: Bool
    let last: Bool
    let onTap: () -> Void

    var body: some View {
        let done = s.phase.name == "DONE"
        let accent = s.advice.map { Palette.verdict($0.verdict) } ?? (done ? Palette.text2 : Palette.cyan)
        Button(action: onTap) {
            PanelBox {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 6) {
                            Image(systemName: Symbols.event(s.event.type)).font(.system(size: 12)).foregroundStyle(Palette.text2)
                            Eyebrow("\(Fmt.shortDate(s.event.startMs)) · \(Fmt.clock(s.event.startMs))")
                        }
                        Text(s.event.title)
                            .font(.system(size: 17))
                            .foregroundStyle(Palette.text1)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 6)
                        Group {
                            if let a = s.advice {
                                PillTag(text: a.verdict.label, color: Palette.verdict(a.verdict))
                            } else if done {
                                PillTag(text: "Done", color: Palette.text2)
                            } else {
                                PillTag(text: "\(s.phase.label) · \(s.doneTaskIds.count)/\(s.todayTasks.count)", color: Palette.cyan, symbol: "checklist")
                            }
                        }
                        .padding(.top, 10)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if let prep = s.prepScore {
                        ScoreRing(score: prep.intValue, label: "prep", size: 54)
                    }
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 22)
            .padding(.leading, 64)
            .background(alignment: .topLeading) {
                RailMark(accent: accent, tag: Fmt.dayTag(s.daysUntil), first: first, last: last)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct RailMark: View {
    let accent: Color
    let tag: String
    let first: Bool
    let last: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            Canvas { ctx, size in
                let x: CGFloat = 10
                let y: CGFloat = 22
                if !first {
                    var p = Path()
                    p.move(to: CGPoint(x: x, y: 0))
                    p.addLine(to: CGPoint(x: x, y: y))
                    ctx.stroke(p, with: .color(Palette.hairline), lineWidth: 2)
                }
                if !last {
                    var p = Path()
                    p.move(to: CGPoint(x: x, y: y))
                    p.addLine(to: CGPoint(x: x, y: size.height))
                    ctx.stroke(p, with: .color(Palette.hairline), style: StrokeStyle(lineWidth: 2, dash: [3, 3]))
                }
                ctx.fill(Path(ellipseIn: CGRect(x: x - 6, y: y - 6, width: 12, height: 12)), with: .color(accent))
                ctx.fill(Path(ellipseIn: CGRect(x: x - 3, y: y - 3, width: 6, height: 6)), with: .color(Palette.ink))
            }
            Text(tag)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(accent)
                .lineLimit(1)
                .padding(.leading, 24)
                .padding(.top, 14)
        }
        .frame(width: 64)
        .frame(maxHeight: .infinity)
    }
}

// MARK: - Event detail

struct EventDetailView: View {
    let eventId: String
    @EnvironmentObject private var model: PhoneModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let snap = model.snapshot, let s = snap.events.first(where: { $0.event.id == eventId }) {
                EventDetailContent(s: s, today: snap.today) { dismiss() }
            } else {
                Color.clear
            }
        }
        .background(Palette.ink.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Eyebrow("Countdown", color: Palette.text1) }
        }
        .toolbarBackground(Palette.ink, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

private struct EventDetailContent: View {
    let s: EventStatus
    let today: String
    let onDeleted: () -> Void
    @EnvironmentObject private var model: PhoneModel
    @State private var confirmDelete = false
    @State private var openTask: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header
                if let a = s.advice { adviceSection(a) }
                runUp
                if !s.todayTasks.isEmpty { checklist }
                OutlineButton(title: "Delete", fullWidth: true) { confirmDelete = true }
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
        .alert("Delete \u{201C}\(s.event.title)\u{201D}?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                Task {
                    await model.deleteEvent(id: s.event.id)
                    onDeleted()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its checklist progress is deleted too.")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: Symbols.event(s.event.type)).font(.system(size: 14)).foregroundStyle(Palette.cyan)
                Eyebrow("Stakes · \(s.event.importance.label)", color: Palette.cyan)
            }
            Text(s.event.title)
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(Palette.text1)
                .padding(.top, 8)
            Muted("\(Fmt.longDate(s.event.startMs)) · \(Fmt.clock(s.event.startMs))")
            HStack(alignment: .bottom, spacing: 14) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(Fmt.dayTag(s.daysUntil))
                        .font(.system(size: 64, weight: .thin))
                        .tracking(-2)
                        .foregroundStyle(Palette.text1)
                    Eyebrow(s.phase.label, color: Palette.cyan)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let prep = s.prepScore { ScoreRing(score: prep.intValue, label: "prep") }
                if let forecast = s.projectedScore { ScoreRing(score: forecast.intValue, label: "forecast") }
            }
            .padding(.top, 22)
        }
    }

    private func adviceSection(_ a: DecisionAdvice) -> some View {
        let go = a.verdict.name == "GO" || a.verdict.name == "GO_WITH_PREP"
        let color = Palette.verdict(a.verdict)
        return HSection("Event-day call") {
            Illustration(go ? "ill_go" : "ill_wait").frame(height: 180).frame(maxWidth: .infinity)
            PanelBox(accent: color) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(a.verdict.label).font(.system(size: 22)).foregroundStyle(color)
                                .fixedSize(horizontal: false, vertical: true)
                            Text(a.headline).font(.subheadline).foregroundStyle(Palette.text1)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        ScoreRing(score: Int(a.expectedScore), label: "at \(Fmt.clock(s.event.startMs))", size: 60)
                    }
                    ForEach(Array(a.actions.prefix(3).enumerated()), id: \.offset) { _, action in
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Image(systemName: "arrow.right").font(.system(size: 12)).foregroundStyle(color)
                            Text(action).font(.subheadline).foregroundStyle(Palette.text1)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            .padding(.top, 8)
        }
    }

    private var runUp: some View {
        HSection("Run-up") {
            HStack(alignment: .bottom, spacing: 0) {
                ForEach(Array(s.history.enumerated()), id: \.offset) { i, day in
                    RunUpBar(day: day, offset: s.history.count - 1 - i, isToday: day.dayKey == today)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private var checklist: some View {
        HSection("Today's checklist", trailing: {
            Text("\(s.doneTaskIds.count)/\(s.todayTasks.count)").font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.cyan)
        }) {
            VStack(spacing: 0) {
                ForEach(Array(s.todayTasks.enumerated()), id: \.element.id) { i, task in
                    TaskRow(
                        task: task,
                        done: s.doneTaskIds.contains(task.id),
                        open: openTask == task.id,
                        onToggle: { done in Task { await model.setTask(eventId: s.event.id, taskId: task.id, done: done) } },
                        onExpand: { withAnimation(.easeInOut(duration: 0.2)) { openTask = openTask == task.id ? nil : task.id } }
                    )
                    if i < s.todayTasks.count - 1 { HairlineRule() }
                }
            }
        }
    }
}

private struct RunUpBar: View {
    let day: DayScore
    let offset: Int
    let isToday: Bool

    var body: some View {
        let score = day.score?.intValue
        let color = score.map { Palette.score($0) }
        VStack(spacing: 0) {
            Text(score.map { "\($0)" } ?? "·").font(.system(size: 11, design: .monospaced)).foregroundStyle(color ?? Palette.text2)
            RoundedRectangle(cornerRadius: 2)
                .fill(color ?? Palette.hairline)
                .frame(width: 16, height: CGFloat(score ?? 6) * 0.7)
                .overlay(RoundedRectangle(cornerRadius: 2).stroke(isToday ? Palette.text1 : Color.clear, lineWidth: 1.5))
                .padding(.top, 4)
            Text(offset == 0 ? "D" : "-\(offset)")
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(isToday ? Palette.cyan : Palette.text2)
                .padding(.top, 6)
        }
    }
}

private struct TaskRow: View {
    let task: PrepTask
    let done: Bool
    let open: Bool
    let onToggle: (Bool) -> Void
    let onExpand: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Button { onToggle(!done) } label: {
                    Image(systemName: done ? "checkmark.square.fill" : "square")
                        .font(.system(size: 22))
                        .foregroundStyle(done ? Palette.cyan : Palette.text2)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(done ? "Mark \(task.title) as not done" : "Mark \(task.title) as done")
                Text(task.title)
                    .font(.body)
                    .foregroundStyle(done ? Palette.text2 : Palette.text1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onExpand)
            }
            .padding(.vertical, 4)
            if open {
                Muted(task.detail).padding(.leading, 48).padding(.bottom, 8)
            }
        }
    }
}

// MARK: - Add a big moment

struct AddMomentSheet: View {
    @EnvironmentObject private var model: PhoneModel
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var importanceName = "HIGH"
    @State private var dayOffset = 3
    @State private var hour = 10
    @State private var minute = 0
    @FocusState private var titleFocused: Bool

    var body: some View {
        let draft = model.core.eventDraft(title: title, dayOffset: Int32(dayOffset), hour: Int32(hour), minute: Int32(minute))
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    TextField("e.g. Interview at Acme", text: $title)
                        .focused($titleFocused)
                        .submitLabel(.done)
                        .padding(12)
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(titleFocused ? Palette.cyan : Palette.hairline, lineWidth: 1))
                    Eyebrow("Stakes").padding(.top, 18)
                    HStack(spacing: 8) {
                        ForEach(Catalog.shared.importances, id: \.name) { i in
                            Chip(text: i.label, selected: importanceName == i.name) { importanceName = i.name }
                        }
                    }
                    .padding(.top, 6)
                    Eyebrow("When").padding(.top, 18)
                    promise.padding(.top, 8)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], alignment: .leading, spacing: 8) {
                        ForEach(model.core.dayChoices(), id: \.offset) { d in
                            Chip(text: label(d), selected: dayOffset == Int(d.offset)) { dayOffset = Int(d.offset) }
                        }
                    }
                    .padding(.top, 10)
                    HStack {
                        Eyebrow("Time").frame(maxWidth: .infinity, alignment: .leading)
                        TimeStepper(value: twoDigits(hour), onMinus: { hour = (hour + 23) % 24 }, onPlus: { hour = (hour + 1) % 24 })
                        Text(":").font(.system(size: 20, design: .monospaced)).foregroundStyle(Palette.text2)
                        TimeStepper(value: twoDigits(minute), onMinus: { minute = (minute + 45) % 60 }, onPlus: { minute = (minute + 15) % 60 })
                    }
                    .padding(.top, 14)
                    Group {
                        if draft.passed {
                            Muted("That time has already passed.")
                        } else if let hint = draft.hint {
                            TimingHintRow(hint: hint)
                        }
                    }
                    .padding(.top, 12)
                }
                .padding(20)
            }
            .background(Palette.panel.ignoresSafeArea())
            .navigationTitle("New big moment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(!draft.valid)
                }
            }
            .task {
                // Type the title straight away; the sheet needs a moment before it can take focus.
                try? await Task.sleep(nanoseconds: 350_000_000)
                titleFocused = true
            }
        }
    }

    /// Makes the 7-day promise explicit right where the user picks the date.
    private var promise: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "scope").font(.system(size: 16)).foregroundStyle(Palette.cyan)
            Text("Pick a day up to 7 days out. Hercules tracks your readiness every day until then and guides your sleep and prep, so your mind is at its best on the big day.")
                .font(.footnote)
                .foregroundStyle(Palette.text1)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .background(Palette.cyan.opacity(0.07))
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.cyan.opacity(0.35), lineWidth: 1))
    }

    private func label(_ d: DayChoice) -> String {
        switch d.offset {
        case 0: return "Today"
        case 1: return "Tomorrow"
        default: return Fmt.shortDate(d.dayStartMs + 12 * 3_600_000)
        }
    }

    private func save() {
        guard let importance = Catalog.shared.importances.first(where: { $0.name == importanceName }) else { return }
        let (t, d, h, m) = (title, dayOffset, hour, minute)
        Task {
            await model.addEvent(title: t, importance: importance, dayOffset: d, hour: h, minute: m)
            dismiss()
        }
    }
}

/// Compact − value + control (hours step by 1, minutes by 15).
private struct TimeStepper: View {
    let value: String
    let onMinus: () -> Void
    let onPlus: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onMinus) {
                Image(systemName: "minus").font(.system(size: 13)).foregroundStyle(Palette.text2).frame(width: 34, height: 34)
            }
            .accessibilityLabel("Less")
            Text(value).font(.system(size: 18, design: .monospaced)).foregroundStyle(Palette.text1).frame(width: 32)
            Button(action: onPlus) {
                Image(systemName: "plus").font(.system(size: 13)).foregroundStyle(Palette.text2).frame(width: 34, height: 34)
            }
            .accessibilityLabel("More")
        }
        .buttonStyle(.plain)
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.hairline, lineWidth: 1))
    }
}

/// Says in plain words whether the chosen time is a good one for a demanding moment.
private struct TimingHintRow: View {
    let hint: TimingHint

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Rectangle().fill(Palette.window(hint.window)).frame(width: 10, height: 4).padding(.top, 6)
            Text(hint.text)
                .font(.footnote)
                .foregroundStyle(hint.good ? Palette.text1 : Palette.text2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
