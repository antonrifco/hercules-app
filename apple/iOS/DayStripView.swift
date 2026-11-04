import SwiftUI
import HerculesShared

/// The signature view: one line for the whole day. Last night's sleep, focus windows, the current moment and
/// today's events, on a single time axis.
struct DayStripView: View {
    let snap: PhoneSnapshot
    let onOpen: (String) -> Void

    private static let hour: Int64 = 3_600_000

    private var start: Int64 { min(snap.sleep?.startMs ?? (snap.dayStartMs - 2 * Self.hour), snap.dayStartMs - Self.hour) }
    private var end: Int64 { snap.dayStartMs + 24 * Self.hour }
    private var events: [EventStatus] {
        snap.todayEvents.filter { $0.event.startMs >= start && $0.event.startMs <= end }
    }

    var body: some View {
        let events = self.events
        HSection("Your day", trailing: {
            if let peak = snap.peak {
                Eyebrow("peak \(Fmt.clock(peak.startMs))", color: Palette.cyan)
            }
        }) {
            Canvas { ctx, size in draw(ctx, size: size, events: events) }
                .frame(height: events.isEmpty ? 92 : 120)
                .accessibilityLabel(accessibilitySummary)
            if !events.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(events, id: \.event.id) { e in
                            Button("Open \(e.event.title.clipped(27))") { onOpen(e.event.id) }
                                .font(.system(size: 12))
                                .tint(Palette.cyan)
                                .lineLimit(1)
                        }
                    }
                    .padding(.vertical, 6)
                }
            }
            HStack(spacing: 16) {
                LegendItem(color: Palette.brand.opacity(0.6), text: "Sleep")
                LegendItem(color: Palette.cyan, text: "Peak")
                LegendItem(color: Color(hex: 0x2AA7C9), text: "2nd wind")
                LegendItem(color: Color(hex: 0x3A2F55), text: "Dip")
            }
            .padding(.top, 4)
        }
    }

    private var accessibilitySummary: String {
        var parts: [String] = []
        if let s = snap.sleep { parts.append("Slept \(Catalog.shared.duration(minutes: s.asleepMinutes))") }
        if let p = snap.peak { parts.append("peak focus \(Fmt.clock(p.startMs)) to \(Fmt.clock(p.endMs))") }
        return parts.isEmpty ? "Your day" : parts.joined(separator: ", ")
    }

    private func draw(_ ctx: GraphicsContext, size: CGSize, events: [EventStatus]) {
        let span = Double(end - start)
        func x(_ t: Int64) -> CGFloat { CGFloat(min(max(Double(t - start) / span, 0), 1)) * size.width }
        let top: CGFloat = events.isEmpty ? 8 : 36
        let sleepH: CGFloat = 26
        let bandY = top + sleepH + 8
        let bandH: CGFloat = 8

        // Hour grid every 6 hours.
        var t = snap.dayStartMs - 6 * Self.hour
        while t <= end {
            if t >= start {
                let gx = x(t)
                ctx.stroke(line(gx, top - 4, gx, bandY + bandH + 6), with: .color(Palette.hairline), lineWidth: 1)
                let label = ctx.resolve(
                    Text(String(Fmt.clock(t).prefix(2))).font(.system(size: 9)).tracking(1).foregroundColor(Palette.text2)
                )
                let w = label.measure(in: size).width
                ctx.draw(label, at: CGPoint(x: min(max(gx - w / 2, 0), size.width - w), y: bandY + bandH + 10), anchor: .topLeading)
            }
            t += 6 * Self.hour
        }

        // Last night's sleep.
        if let s = snap.sleep {
            let x0 = x(s.startMs)
            let x1 = x(s.endMs)
            ctx.fill(
                Path(roundedRect: CGRect(x: x0, y: top, width: max(x1 - x0, 0), height: sleepH), cornerRadius: 4),
                with: .color(Palette.brand.opacity(0.35))
            )
            let label = ctx.resolve(
                Text("SLEEP \(Catalog.shared.duration(minutes: s.asleepMinutes))").font(.system(size: 9)).tracking(1).foregroundColor(Palette.text1)
            )
            let m = label.measure(in: size)
            if m.width < x1 - x0 - 8 {
                ctx.draw(label, at: CGPoint(x: x0 + 6, y: top + (sleepH - m.height) / 2), anchor: .topLeading)
            }
        }

        // Focus windows.
        for w in snap.readiness?.windows ?? [] {
            let x0 = x(w.startMs)
            let x1 = x(w.endMs)
            if x1 > x0 {
                ctx.fill(Path(CGRect(x: x0, y: bandY, width: x1 - x0 - 1, height: bandH)), with: .color(Palette.window(w.kind)))
            }
        }

        // Events: diamond pins with labels.
        for e in events {
            let ex = x(e.event.startMs)
            let d: CGFloat = 5
            let y = top - 14
            var diamond = Path()
            diamond.move(to: CGPoint(x: ex, y: y - d))
            diamond.addLine(to: CGPoint(x: ex + d, y: y))
            diamond.addLine(to: CGPoint(x: ex, y: y + d))
            diamond.addLine(to: CGPoint(x: ex - d, y: y))
            diamond.closeSubpath()
            ctx.fill(diamond, with: .color(e.advice.map { Palette.verdict($0.verdict) } ?? Palette.cyan))
            ctx.stroke(line(ex, y + d, ex, bandY + bandH), with: .color(Palette.text2), lineWidth: 1)

            // Up to 27 title characters, and never wider than the strip.
            var title = e.event.title.clipped(27)
            var label = ctx.resolve(Text("\(Fmt.clock(e.event.startMs)) \(title)").font(.system(size: 10)).foregroundColor(Palette.text1))
            var m = label.measure(in: CGSize(width: .infinity, height: .infinity))
            while m.width > size.width, title.count > 2 {
                title = String(title.dropLast(2)) + "…"
                label = ctx.resolve(Text("\(Fmt.clock(e.event.startMs)) \(title)").font(.system(size: 10)).foregroundColor(Palette.text1))
                m = label.measure(in: CGSize(width: .infinity, height: .infinity))
            }
            let lx = max(min(ex + 8, size.width - m.width), 0)
            ctx.draw(label, at: CGPoint(x: lx, y: y - m.height / 2 - 12), anchor: .topLeading)
        }

        // Now.
        if snap.nowMs >= start && snap.nowMs <= end {
            let nx = x(snap.nowMs)
            ctx.stroke(line(nx, top - 6, nx, bandY + bandH + 4), with: .color(Palette.cyan), lineWidth: 2)
            ctx.fill(Path(ellipseIn: CGRect(x: nx - 4, y: bandY + bandH, width: 8, height: 8)), with: .color(Palette.cyan))
        }
    }

    private func line(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: x0, y: y0))
        p.addLine(to: CGPoint(x: x1, y: y1))
        return p
    }
}

private struct LegendItem: View {
    let color: Color
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            Rectangle().fill(color).frame(width: 10, height: 4)
            Text(text.uppercased()).font(.system(size: 9)).tracking(1).foregroundStyle(Palette.text2)
        }
    }
}
