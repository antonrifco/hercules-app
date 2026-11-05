import SwiftUI
import HerculesShared

/// Today: the dial, the day strip, readouts, the next big moment, tips, sleep, check-in and the 14-day trend.
struct TodayView: View {
    let snap: PhoneSnapshot?
    let onOpenEvent: (String) -> Void
    let onOpenGuide: () -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    TopLine(nowMs: snap?.nowMs ?? nowMs(), onOpenGuide: onOpenGuide)
                    if let r = snap?.readiness { Hero(r: r) } else { EmptyHero() }
                    if let snap { DayStripView(snap: snap, onOpen: onOpenEvent) }
                    if let r = snap?.readiness { Readouts(r: r).id("readout") }
                    if let next = snap?.nextEvent { NextEventPanel(s: next, onOpen: onOpenEvent) }
                    if let r = snap?.readiness { Tips(tips: r.suggestions).id("tips") }
                    SleepBlock(snap: snap).id("sleep")
                    CheckInBlock(existing: snap?.checkIn).id("checkin")
                    if let snap, snap.trendDays >= 2 { TrendBlock(snap: snap).id("trend") }
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
            #if DEBUG
            .task {
                // Debug only, for screenshots: `-scroll readout|tips|sleep|checkin|trend`.
                guard let target = UserDefaults.standard.string(forKey: "scroll") else { return }
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                proxy.scrollTo(target, anchor: .top)
            }
            #endif
        }
    }
}

private struct TopLine: View {
    let nowMs: Int64
    let onOpenGuide: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text("HERCULES")
                .font(.system(size: 13, weight: .medium))
                .tracking(5)
                .foregroundStyle(Palette.text1)
            Rectangle().fill(Palette.hairline).frame(width: 18, height: 1)
            Eyebrow(Fmt.shortDate(nowMs)).frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onOpenGuide) {
                Image(systemName: "info.circle").font(.system(size: 18)).foregroundStyle(Palette.text2).frame(width: 44, height: 44)
            }
            .accessibilityLabel("How to read the score")
        }
    }
}

private struct Hero: View {
    let r: Readiness

    var body: some View {
        let color = Palette.band(r.band)
        VStack(spacing: 0) {
            ScoreGauge(score: Int(r.score), color: color)
            HStack(spacing: 8) {
                Image(systemName: Symbols.band(r.band)).font(.system(size: 14)).foregroundStyle(color)
                Eyebrow(r.band.label, color: color)
            }
            Text(r.band.headline)
                .font(.system(size: 24, weight: .light))
                .tracking(-0.5)
                .foregroundStyle(Palette.text1)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
            if r.confidence.name != "HIGH" {
                PillTag(text: "\(r.confidence.label) \(min(Int(r.baselineDays), 7))/7", color: Palette.text2).padding(.top, 10)
            }
        }
        .frame(maxWidth: .infinity)
        .background(RadialGradient(colors: [color.opacity(0.16), .clear], center: .center, startRadius: 0, endRadius: 190))
    }
}

private struct EmptyHero: View {
    var body: some View {
        VStack(spacing: 8) {
            Illustration("ill_sleep").frame(height: 260)
            Eyebrow("No reading yet", color: Palette.cyan)
            Text("Sleep sets the dial").font(.system(size: 24, weight: .light)).foregroundStyle(Palette.text1)
            Muted("Wear the watch to bed tonight, or log a check-in below.").multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Four instrument readouts; tap one to see what it measured.
private struct Readouts: View {
    let r: Readiness
    @State private var selected: Int?

    var body: some View {
        HSection("Readout") {
            HStack(spacing: 10) {
                ForEach(Array(r.components.enumerated()), id: \.offset) { i, c in
                    ReadoutTile(component: c, selected: selected == i) {
                        withAnimation(.easeInOut(duration: 0.2)) { selected = selected == i ? nil : i }
                    }
                }
            }
            if let i = selected, i < r.components.count {
                let c = r.components[i]
                VStack(alignment: .leading, spacing: 4) {
                    Text(c.detail).font(.subheadline).foregroundStyle(Palette.text1)
                    Muted("\(Int((c.kind.weight * 100).rounded()))% of the score. \(c.kind.explanation)")
                }
                .padding(.top, 10)
            }
        }
    }
}

private struct ReadoutTile: View {
    let component: ReadinessComponent
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        let color = Palette.score(Int(component.score))
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                Image(systemName: Symbols.component(component.kind)).font(.system(size: 14)).foregroundStyle(color)
                Text("\(component.score)")
                    .font(.system(size: 22, design: .monospaced))
                    .foregroundStyle(Palette.text1)
                    .padding(.top, 10)
                ScoreBar(score: Int(component.score), color: color, height: 2).padding(.top, 6)
                Text(component.kind.label.uppercased())
                    .font(.system(size: 9))
                    .tracking(1)
                    .foregroundStyle(Palette.text2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .padding(.top, 6)
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(selected ? color : Palette.hairline, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct NextEventPanel: View {
    let s: EventStatus
    let onOpen: (String) -> Void

    var body: some View {
        let accent = s.advice.map { Palette.verdict($0.verdict) } ?? Palette.cyan
        HSection("Next") {
            Button { onOpen(s.event.id) } label: {
                PanelBox(accent: accent) {
                    HStack(spacing: 16) {
                        Text(Fmt.dayTag(s.daysUntil))
                            .font(.system(size: 40, weight: .thin))
                            .foregroundStyle(accent)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 6) {
                                Image(systemName: Symbols.event(s.event.type)).font(.system(size: 12)).foregroundStyle(Palette.text2)
                                Eyebrow("\(s.event.importance.label) · \(Fmt.clock(s.event.startMs))")
                            }
                            Text(s.event.title)
                                .font(.system(size: 17))
                                .foregroundStyle(Palette.text1)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                            if let a = s.advice {
                                PillTag(text: a.verdict.label, color: accent).padding(.top, 4)
                            } else {
                                PillTag(text: s.phase.label, color: Palette.cyan).padding(.top, 4)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if let a = s.advice {
                            ScoreRing(score: Int(a.expectedScore), label: "at \(Fmt.clock(s.event.startMs))", size: 58)
                        } else if let prep = s.prepScore {
                            ScoreRing(score: prep.intValue, label: "prep", size: 58)
                        }
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }
}

private struct Tips: View {
    let tips: [Suggestion]
    @State private var open: Set<Int> = []

    var body: some View {
        let shown = Array(tips.prefix(4))
        HSection("Today's moves") {
            VStack(spacing: 0) {
                ForEach(Array(shown.enumerated()), id: \.offset) { i, tip in
                    TipRow(index: i, tip: tip, open: open.contains(i)) {
                        withAnimation(.easeInOut(duration: 0.2)) { toggle(i) }
                    }
                    if i < shown.count - 1 { HairlineRule() }
                }
            }
        }
    }

    private func toggle(_ i: Int) {
        if open.contains(i) {
            _ = open.remove(i)
        } else {
            _ = open.insert(i)
        }
    }
}

private struct TipRow: View {
    let index: Int
    let tip: Suggestion
    let open: Bool
    let onTap: () -> Void

    var body: some View {
        let color = Palette.tone(tip.tone)
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 14) {
                    Text(twoDigits(index + 1)).font(.system(size: 13, design: .monospaced)).foregroundStyle(color)
                    Text(tip.title)
                        .font(.body)
                        .foregroundStyle(Palette.text1)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: Symbols.tone(tip.tone)).font(.system(size: 14)).foregroundStyle(color.opacity(0.8))
                }
                if open {
                    Text(tip.detail)
                        .font(.subheadline)
                        .foregroundStyle(Palette.text2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 34)
                }
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SleepBlock: View {
    let snap: PhoneSnapshot?
    @EnvironmentObject private var model: PhoneModel
    @State private var correcting = false

    var body: some View {
        let sleep = snap?.sleep
        HSection("Sleep", trailing: {
            Button(sleep == nil ? "Add" : "Correct") { correcting = true }
                .font(.system(size: 13))
                .tint(Palette.cyan)
        }) {
            if let s = sleep {
                HStack(alignment: .top, spacing: 8) {
                    FactTile(
                        symbol: "moon.zzz.fill",
                        value: Catalog.shared.duration(minutes: s.asleepMinutes),
                        label: "asleep",
                        tint: Palette.score(Int(Catalog.shared.sleepScore(hours: Double(s.asleepMinutes) / 60)))
                    )
                    FactTile(symbol: "moon.fill", value: Fmt.clock(s.startMs), label: "in bed", tint: Palette.brand)
                    FactTile(symbol: "sun.max.fill", value: Fmt.clock(s.endMs), label: "awake", tint: Palette.brand)
                    FactTile(symbol: "heart.fill", value: s.sleepingHrAvg.map { "\(Int($0.doubleValue.rounded()))" } ?? "--", label: "bpm", tint: Palette.brand)
                }
                HStack(spacing: 6) {
                    Image(systemName: s.corrected ? "pencil" : "applewatch").font(.system(size: 12)).foregroundStyle(Palette.text2)
                    Eyebrow(s.corrected ? "Corrected by you" : "Auto-tracked by your watch")
                }
                .padding(.top, 10)
            } else {
                Muted("Nothing tracked last night. Wear the watch to bed and sleep is recorded automatically.")
            }
            if let snap {
                MiniBars(bars: snap.sleepWeek, maxValue: 10, target: 7) { Palette.score(Int($0.colorScore)) }
                    .padding(.top, 18)
            }
        }
        .sheet(isPresented: $correcting) {
            CorrectSleepSheet(sleep: sleep).environmentObject(model)
        }
    }
}

/// Fix the times the watch detected (or add a night it missed). A bedtime from noon on belongs to the previous
/// evening; the wake time is on this morning. A corrected night is never overwritten by detection.
private struct CorrectSleepSheet: View {
    let sleep: SleepNight?
    @EnvironmentObject private var model: PhoneModel
    @Environment(\.dismiss) private var dismiss
    @State private var bed = Date()
    @State private var wake = Date()
    @State private var loaded = false

    var body: some View {
        let draft = model.core.sleepDraft(bedHour: hour(bed), bedMinute: minute(bed), wakeHour: hour(wake), wakeMinute: minute(wake))
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Muted("Your watch tracks sleep on its own. Fix the times only if they look wrong.")
                    Eyebrow("Went to sleep").padding(.top, 8)
                    DatePicker("Went to sleep", selection: $bed, displayedComponents: .hourAndMinute)
                        .datePickerStyle(.wheel)
                        .labelsHidden()
                        .frame(maxWidth: .infinity)
                    Eyebrow("Woke up")
                    DatePicker("Woke up", selection: $wake, displayedComponents: .hourAndMinute)
                        .datePickerStyle(.wheel)
                        .labelsHidden()
                        .frame(maxWidth: .infinity)
                    Text(draft.message)
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(draft.valid ? Palette.cyan : Palette.text2)
                }
                .padding(20)
            }
            .background(Palette.panel.ignoresSafeArea())
            .navigationTitle(sleep == nil ? "Add last night" : "Correct last night")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            await model.correctSleep(draft)
                            dismiss()
                        }
                    }
                    .disabled(!draft.valid)
                }
            }
        }
        .environment(\.locale, Locale(identifier: "en_GB")) // 24-hour wheels, like the rest of the app
        .onAppear {
            guard !loaded else { return }
            let t = model.core.sleepTimes(sleep: sleep)
            bed = time(t.bedHour, t.bedMinute)
            wake = time(t.wakeHour, t.wakeMinute)
            loaded = true
        }
    }

    private func hour(_ d: Date) -> Int32 { Int32(Calendar.current.component(.hour, from: d)) }
    private func minute(_ d: Date) -> Int32 { Int32(Calendar.current.component(.minute, from: d)) }
    private func time(_ h: Int32, _ m: Int32) -> Date {
        Calendar.current.date(bySettingHour: Int(h), minute: Int(m), second: 0, of: Date()) ?? Date()
    }
}

private struct CheckInBlock: View {
    let existing: CheckIn?
    @EnvironmentObject private var model: PhoneModel
    @State private var editing = false
    @State private var energy = 3
    @State private var stress = 3

    var body: some View {
        let showForm = editing || existing == nil
        HSection("Check-in", trailing: {
            if let e = existing, !showForm {
                Button("Edit") {
                    energy = Int(e.energy)
                    stress = Int(e.stress)
                    editing = true
                }
                .font(.system(size: 13))
                .tint(Palette.cyan)
            }
        }) {
            if let e = existing, !showForm {
                HStack(spacing: 12) {
                    Illustration("ill_checkin").frame(width: 96, height: 96)
                    FactTile(symbol: "bolt.fill", value: "\(e.energy)/5", label: "energy", tint: Palette.score(Int(e.energy) * 20))
                    FactTile(symbol: "brain.head.profile", value: "\(e.stress)/5", label: "stress", tint: Palette.score(120 - Int(e.stress) * 20))
                }
            } else {
                SegmentScale(title: "Energy", symbol: "bolt.fill", low: "Drained", high: "Charged", value: $energy, higherIsBetter: true)
                SegmentScale(title: "Stress", symbol: "brain.head.profile", low: "Calm", high: "Tense", value: $stress, higherIsBetter: false)
                    .padding(.top, 16)
                PrimaryButton(title: "Log check-in") {
                    Task {
                        await model.saveCheckIn(energy: energy, stress: stress)
                        editing = false
                    }
                }
                .padding(.top, 14)
            }
        }
    }
}

/// Five tappable segments that fill up to the chosen value, coloured good → bad.
private struct SegmentScale: View {
    let title: String
    let symbol: String
    let low: String
    let high: String
    @Binding var value: Int
    let higherIsBetter: Bool

    var body: some View {
        let color = Palette.score(higherIsBetter ? value * 20 : 120 - value * 20)
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: symbol).font(.system(size: 14)).foregroundStyle(color)
                Eyebrow(title, color: Palette.text1).frame(maxWidth: .infinity, alignment: .leading)
                Text("\(value)/5").font(.system(size: 14, design: .monospaced)).foregroundStyle(color)
            }
            HStack(spacing: 4) {
                ForEach(1...5, id: \.self) { v in
                    RoundedRectangle(cornerRadius: 3)
                        .fill(v <= value ? color.opacity(0.35 + 0.13 * Double(v)) : Palette.panel)
                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(v <= value ? Color.clear : Palette.hairline, lineWidth: 1))
                        .frame(height: 26)
                        .contentShape(Rectangle())
                        .onTapGesture { value = v }
                        .accessibilityLabel("\(title) \(v) of 5")
                        .accessibilityAddTraits(.isButton)
                }
            }
            HStack {
                Text(low.uppercased()).font(.system(size: 9)).tracking(1).foregroundStyle(Palette.text2)
                Spacer()
                Text(high.uppercased()).font(.system(size: 9)).tracking(1).foregroundStyle(Palette.text2)
            }
        }
    }
}

private struct TrendBlock: View {
    let snap: PhoneSnapshot

    var body: some View {
        HSection("14 days", trailing: {
            Text("avg \(snap.trendAverage)").font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.text2)
        }) {
            MiniBars(bars: snap.trend, maxValue: 100, target: 70) { Palette.score(Int($0.colorScore)) }
        }
    }
}
