import SwiftUI
import WatchKit
import HerculesShared

/*
 * The watch does three things only:
 *   1. today's readiness (score, band, peak window),
 *   2. what it is tracking (sleep, heart, steps) and whether that data reached the phone,
 *   3. readiness for the big moments planned in the next 7 days.
 * Planning, check-ins and sleep corrections live on the phone.
 */
struct WatchHomeView: View {
    @EnvironmentObject private var model: WatchModel
    @State private var showSensors = false

    var body: some View {
        let home = model.home
        let readiness = home?.readiness
        ScrollViewReader { proxy in
        ScrollView {
            VStack(spacing: 6) {
                // 1 · Today
                DialHeader(
                    score: readiness.map { Int($0.score) },
                    color: readiness.map { Palette.band($0.band) } ?? Palette.text2,
                    bandLabel: readiness?.band.label
                )
                if let peak = home?.peak {
                    HStack(spacing: 6) {
                        Rectangle().fill(Palette.cyan).frame(width: 10, height: 3)
                        WEyebrow("Peak \(Fmt.clock(peak.startMs))–\(Fmt.clock(peak.endMs))", color: Palette.cyan)
                    }
                }

                // 2 · Tracking
                WSectionLabel(text: "Tracking").id("tracking")
                HStack(alignment: .top, spacing: 14) {
                    SleepStat(value: home?.sleepText ?? "--")
                    HeartStat(bpm: home.map { Int($0.heartRate) } ?? 0)
                    StepsStat(steps: home?.steps ?? 0)
                }
                if let sync = home?.sync { SyncLineView(line: sync) }

                // 3 · Planned moments
                WSectionLabel(text: "Big moments ahead").id("moments")
                let moments = home?.moments ?? []
                if moments.isEmpty {
                    Text("Plan one on your phone").font(.footnote).foregroundStyle(Palette.text2).multilineTextAlignment(.center)
                }
                ForEach(moments, id: \.id) { MomentRowView(moment: $0) }

                Button { showSensors = true } label: {
                    Text("SENSORS")
                        .font(.system(size: 11))
                        .tracking(1.5)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .overlay(Capsule().stroke(Palette.watchLine, lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .padding(.top, 10)
            }
            .padding(.horizontal, 4)
        }
        .background(Color.black)
        .navigationDestination(isPresented: $showSensors) { SensorsView() }
        #if DEBUG
        .task {
            // Debug only, for screenshots: `-screen sensors`, `-scroll tracking|moments`.
            if UserDefaults.standard.string(forKey: "screen") == "sensors" { showSensors = true }
            if let target = UserDefaults.standard.string(forKey: "scroll") {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                proxy.scrollTo(target, anchor: .top)
            }
        }
        #endif
        }
    }
}

// MARK: - Dial

/// 100 ticks sweep up to the score while the number counts up. Without a score a comet circles the bezel and a
/// moon breathes: "waiting for a night of data".
private struct DialHeader: View {
    let score: Int?
    let color: Color
    let bandLabel: String?
    @State private var fill: Double = 0

    private var diameter: CGFloat { max(WKInterfaceDevice.current().screenBounds.width - 8, 120) }

    var body: some View {
        ZStack {
            if let score {
                DialTicks(fraction: 1, major: false, outerInset: 1.5, length: 3.5).stroke(Palette.watchLine, lineWidth: 1)
                DialTicks(fraction: 1, major: true, outerInset: 1.5, length: 7.5).stroke(Palette.watchLine, lineWidth: 2)
                DialTicks(fraction: fill, major: false, outerInset: 1.5, length: 3.5).stroke(color, lineWidth: 1)
                DialTicks(fraction: fill, major: true, outerInset: 1.5, length: 7.5).stroke(color, lineWidth: 2)
                VStack(spacing: 0) {
                    CountUpText(value: fill * 100)
                    WEyebrow("\(bandLabel ?? "") today", color: color)
                }
                .onAppear { sweep(score) }
                .onChange(of: score) { _, new in sweep(new) }
            } else {
                CometDial()
                VStack(spacing: 2) {
                    SleepOrbit().frame(width: 58, height: 58)
                    WEyebrow("Calibrating", color: Palette.cyan)
                    Text("Sleep with the watch on")
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.text2)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, 24)
            }
        }
        .frame(width: diameter, height: diameter)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(score.map { "Readiness \($0), \(bandLabel ?? "")" } ?? "Calibrating, sleep with the watch on")
    }

    private func sweep(_ score: Int) {
        fill = 0
        withAnimation(.easeInOut(duration: 1.4)) { fill = Double(score) / 100 }
    }
}

/// The score counts up in step with the bezel.
private struct CountUpText: View, Animatable {
    var value: Double

    var animatableData: Double {
        get { value }
        set { value = newValue }
    }

    var body: some View {
        Text("\(Int(value.rounded()))")
            .font(.system(size: 54, weight: .thin))
            .tracking(-2)
            .foregroundStyle(.white)
            .monospacedDigit()
    }
}

private struct CometDial: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 20)) { timeline in
            Canvas { ctx, size in
                let head = Motion.loop(timeline.date, period: 4) * 100
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let r = min(size.width, size.height) / 2
                for i in 0...100 {
                    let behind = (head - Double(i) + 100).truncatingRemainder(dividingBy: 100) // 0 at the head
                    let color = behind < 18 ? Palette.cyan.opacity(1 - behind / 18) : Palette.watchLine
                    let major = i % 10 == 0
                    let a = DialGeometry.angle(Double(i) / 100)
                    let r0 = r - (major ? 9 : 5)
                    let r1 = r - 1.5
                    var p = Path()
                    p.move(to: CGPoint(x: center.x + r0 * CGFloat(cos(a)), y: center.y + r0 * CGFloat(sin(a))))
                    p.addLine(to: CGPoint(x: center.x + r1 * CGFloat(cos(a)), y: center.y + r1 * CGFloat(sin(a))))
                    ctx.stroke(p, with: .color(color), lineWidth: major ? 2 : 1)
                }
            }
        }
    }
}

/// Breathing crescent moon with twinkling stars.
private struct SleepOrbit: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 20)) { timeline in
            Canvas { ctx, size in
                let t = timeline.date
                let breath = 0.92 + 0.14 * Motion.pingPong(t, period: 4.4)
                let twinkle = Motion.loop(t, period: 2.6)
                let orbit = Motion.loop(t, period: 9) * 360
                let c = CGPoint(x: size.width / 2, y: size.height / 2)
                let side = min(size.width, size.height)
                let r = side * 0.26 * CGFloat(breath)
                ctx.fill(circle(c, r * 1.9), with: .color(Palette.cyan.opacity(0.10)))
                ctx.fill(circle(c, r), with: .color(Palette.cyan))
                ctx.fill(circle(CGPoint(x: c.x + r * 0.45, y: c.y - r * 0.32), r * 0.86), with: .color(.black))
                for (i, base) in [0.0, 120.0, 240.0].enumerated() {
                    let a = (base + orbit) * .pi / 180
                    let d = side * 0.44
                    let phase = (twinkle + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                    let alpha = phase < 0.5 ? phase * 2 : (1 - phase) * 2
                    let star = CGPoint(x: c.x + d * CGFloat(cos(a)), y: c.y + d * CGFloat(sin(a)))
                    ctx.fill(circle(star, 1.6), with: .color(.white.opacity(0.25 + 0.75 * alpha)))
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func circle(_ c: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
    }
}

/// Heart with rings expanding outward, used on the permission screen.
struct PulseRings: View {
    var body: some View {
        ZStack {
            TimelineView(.animation(minimumInterval: 1.0 / 20)) { timeline in
                Canvas { ctx, size in
                    let p = Motion.loop(timeline.date, period: 1.8)
                    let side = min(size.width, size.height)
                    let c = CGPoint(x: size.width / 2, y: size.height / 2)
                    for k in 0...1 {
                        let q = (p + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
                        let r = side * CGFloat(0.2 + 0.3 * q)
                        ctx.stroke(
                            Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                            with: .color(Palette.cyan.opacity((1 - q) * 0.6)),
                            lineWidth: 1.5
                        )
                    }
                }
            }
            BeatingIcon(symbol: "heart.fill", bpm: 64, size: 20)
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Tracking

/// An icon that beats like a heart at `bpm`.
private struct BeatingIcon: View {
    let symbol: String
    let bpm: Int
    let size: CGFloat

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { timeline in
            let period = 60.0 / Double(min(max(bpm, 40), 180))
            Image(systemName: symbol)
                .font(.system(size: size))
                .foregroundStyle(Palette.cyan)
                .scaleEffect(Motion.beat(Motion.loop(timeline.date, period: period)))
        }
    }
}

private struct HeartStat: View {
    let bpm: Int

    var body: some View {
        StatColumn(value: bpm > 0 ? "\(bpm)" : "--", label: "bpm") {
            BeatingIcon(symbol: "heart.fill", bpm: bpm > 0 ? bpm : 60, size: 13)
        }
    }
}

private struct SleepStat: View {
    let value: String

    var body: some View {
        StatColumn(value: value, label: "sleep") {
            TimelineView(.animation(minimumInterval: 1.0 / 20)) { timeline in
                Image(systemName: "moon.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.cyan.opacity(0.55 + 0.45 * Motion.pingPong(timeline.date, period: 3.6)))
                    .rotationEffect(.degrees(-8 + 16 * Motion.pingPong(timeline.date, period: 5.2)))
            }
        }
    }
}

private struct StepsStat: View {
    let steps: Int64

    var body: some View {
        StatColumn(value: "\(steps)", label: "steps") {
            TimelineView(.animation(minimumInterval: 1.0 / 20)) { timeline in
                let bob = Motion.pingPong(timeline.date, period: 1.04)
                Image(systemName: "figure.walk")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.cyan)
                    .offset(y: -2 * bob)
                    .rotationEffect(.degrees((bob - 0.5) * 6))
            }
        }
    }
}

private struct StatColumn<Icon: View>: View {
    let value: String
    let label: String
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        VStack(spacing: 1) {
            icon().frame(width: 18, height: 18)
            Text(value).font(.system(size: 13, design: .monospaced)).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7)
            Text(label.uppercased()).font(.system(size: 8)).tracking(1).foregroundStyle(Palette.text2)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct SyncLineView: View {
    let line: SyncLine

    var body: some View {
        let tint = line.warning ? Palette.amber : Palette.text2
        HStack(spacing: 5) {
            Image(systemName: line.ok ? "checkmark.icloud" : "icloud.slash").font(.system(size: 10)).foregroundStyle(tint)
            Text(line.text).font(.system(size: 10)).foregroundStyle(tint).lineLimit(2)
        }
        .padding(.top, 4)
    }
}

// MARK: - Moments

/// One planned moment: countdown, title, and the readiness forecast for that day as a small ring.
private struct MomentRowView: View {
    let moment: WatchMoment

    var body: some View {
        let accent: Color = moment.verdict.map { Palette.verdict($0) }
            ?? (moment.forecast >= 0 ? Palette.score(Int(moment.forecast)) : Palette.cyan)
        HStack(spacing: 6) {
            Text(moment.tag)
                .font(moment.isToday ? .system(size: 11, weight: .medium) : .system(size: 18, weight: .thin))
                .tracking(moment.isToday ? 1.2 : 0)
                .foregroundStyle(accent)
                .frame(width: 34, alignment: .leading)
            VStack(alignment: .leading, spacing: 1) {
                Text(moment.title).font(.system(size: 13, weight: .medium)).foregroundStyle(.white).lineLimit(1)
                WEyebrow(moment.verdict?.label ?? Fmt.dayTime(moment.startMs), color: accent, centered: false)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            ForecastRing(score: moment.forecast >= 0 ? Int(moment.forecast) : nil, color: accent)
                .frame(width: 30, height: 30)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.watchLine, lineWidth: 1))
    }
}

/// Forecast readiness for the moment's day; the arc fills in when shown.
private struct ForecastRing: View {
    let score: Int?
    let color: Color
    @State private var sweep: Double = 0

    var body: some View {
        ZStack {
            Circle().stroke(Palette.watchLine, lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: sweep)
                .stroke(color, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(score.map { "\($0)" } ?? "–").font(.system(size: 10, design: .monospaced)).foregroundStyle(.white)
        }
        .padding(1.25)
        .onAppear { withAnimation(.easeInOut(duration: 1.1)) { sweep = Double(score ?? 0) / 100 } }
        .onChange(of: score) { _, new in withAnimation(.easeInOut(duration: 1.1)) { sweep = Double(new ?? 0) / 100 } }
    }
}

// MARK: - Small parts

private struct WSectionLabel: View {
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            Rectangle().fill(Palette.cyan).frame(width: 3, height: 9)
            WEyebrow(text, color: .white)
        }
        .padding(.top, 10)
        .padding(.bottom, 2)
    }
}

private struct WEyebrow: View {
    let text: String
    var color: Color = Palette.text2
    var centered = true

    init(_ text: String, color: Color = Palette.text2, centered: Bool = true) {
        self.text = text
        self.color = color
        self.centered = centered
    }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .medium))
            .tracking(1.4)
            .foregroundStyle(color)
            .multilineTextAlignment(centered ? .center : .leading)
            .lineLimit(1)
            .truncationMode(.tail)
    }
}
