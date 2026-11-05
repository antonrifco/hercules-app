import SwiftUI
import HerculesShared

// Building blocks of the phone UI: a precision instrument, not a wellness toy. Hairline rules instead of card
// stacks, sharp 6 pt corners, small letter-spaced labels and monospaced readouts (same as the Android app).

/// Small uppercase, letter-spaced label: the voice of every section.
struct Eyebrow: View {
    private let text: String
    private let color: Color

    init(_ text: String, color: Color = Palette.text2) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .medium))
            .tracking(1.8)
            .foregroundStyle(color)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
    }
}

struct Muted: View {
    private let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(Palette.text2)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct HairlineRule: View {
    var body: some View { Rectangle().fill(Palette.hairline).frame(height: 1) }
}

/// Section = a cyan tick, an eyebrow title and a hairline. No card.
struct HSection<Trailing: View, Content: View>: View {
    private let title: String
    private let trailing: Trailing
    private let content: Content

    init(_ title: String, @ViewBuilder trailing: () -> Trailing, @ViewBuilder content: () -> Content) {
        self.title = title
        self.trailing = trailing()
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Rectangle().fill(Palette.cyan).frame(width: 3, height: 12)
                Eyebrow(title, color: Palette.text1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                trailing
            }
            .frame(minHeight: 24)
            HairlineRule().padding(.top, 10)
            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 14)
        }
        .padding(.top, 10)
    }
}

extension HSection where Trailing == EmptyView {
    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.init(title, trailing: { EmptyView() }, content: content)
    }
}

/// A bordered panel with sharp corners, optionally with an accent edge on the left.
struct PanelBox<Content: View>: View {
    private let accent: Color?
    private let content: Content

    init(accent: Color? = nil, @ViewBuilder content: () -> Content) {
        self.accent = accent
        self.content = content()
    }

    var body: some View {
        HStack(spacing: 0) {
            if let accent {
                Rectangle().fill(accent).frame(width: 3)
            }
            content
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Palette.panel)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.hairline, lineWidth: 1))
    }
}

/// Outlined tag; reads like a status light rather than a candy pill.
struct PillTag: View {
    let text: String
    let color: Color
    var symbol: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            if let symbol {
                Image(systemName: symbol).font(.system(size: 11)).foregroundStyle(color)
            } else {
                Circle().fill(color).frame(width: 6, height: 6)
            }
            Text(text.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .overlay(RoundedRectangle(cornerRadius: 4).stroke(color.opacity(0.55), lineWidth: 1))
    }
}

/// The big bezel on Today: 100 ticks lit up to the score in the band colour, thin numerals in the middle.
struct ScoreGauge: View {
    let score: Int
    let color: Color
    var size: CGFloat = 260
    var caption = "readiness"
    @State private var progress: Double = 0

    var body: some View {
        ZStack {
            DialTicks(fraction: 1, major: false, outerInset: 4, length: 10)
                .stroke(Palette.hairline, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
            DialTicks(fraction: 1, major: true, outerInset: 4, length: 18)
                .stroke(Palette.hairline, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
            DialTicks(fraction: progress, major: false, outerInset: 4, length: 10)
                .stroke(color, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
            DialTicks(fraction: progress, major: true, outerInset: 4, length: 18)
                .stroke(color, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
            DialDot(fraction: progress, inset: 34, dotRadius: 4).fill(color)
            Circle().fill(color.opacity(0.06)).frame(width: size * 0.62, height: size * 0.62)
            VStack(spacing: 0) {
                Text("\(score)")
                    .font(.system(size: 88, weight: .thin))
                    .tracking(-4)
                    .foregroundStyle(Palette.text1)
                Eyebrow(caption)
            }
        }
        .frame(width: size, height: size)
        .onAppear { sweep() }
        .onChange(of: score) { _, _ in sweep() }
    }

    private func sweep() {
        withAnimation(.easeInOut(duration: 1.1)) { progress = Double(score) / 100 }
    }
}

/// Small dial for secondary scores (prep, forecast, event time).
struct ScoreRing: View {
    let score: Int
    let label: String
    var size: CGFloat = 68
    var color: Color? = nil
    @State private var progress: Double = 0

    var body: some View {
        let tint = color ?? Palette.score(score)
        VStack(spacing: 4) {
            ZStack {
                Circle().stroke(Palette.hairline, lineWidth: 2)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(tint, style: StrokeStyle(lineWidth: 3.2, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(score)")
                    .font(.system(size: size * 0.28, design: .monospaced))
                    .foregroundStyle(Palette.text1)
            }
            .padding(4)
            .frame(width: size, height: size)
            Eyebrow(label)
        }
        .onAppear { fill() }
        .onChange(of: score) { _, _ in fill() }
    }

    private func fill() {
        withAnimation(.easeInOut(duration: 0.8)) { progress = Double(score) / 100 }
    }
}

struct ScoreBar: View {
    let score: Int
    var color: Color? = nil
    var height: CGFloat = 4
    @State private var progress: Double = 0

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle().fill(Palette.hairline)
                Rectangle().fill(color ?? Palette.score(score)).frame(width: geo.size.width * progress)
            }
        }
        .frame(height: height)
        .onAppear { withAnimation(.easeInOut(duration: 0.7)) { progress = min(max(Double(score) / 100, 0), 1) } }
        .onChange(of: score) { _, new in withAnimation(.easeInOut(duration: 0.7)) { progress = min(max(Double(new) / 100, 0), 1) } }
    }
}

/// Thin vertical bars (nights of sleep, days of readiness). Bars that aren't `present` have no data.
struct MiniBars: View {
    let bars: [Bar]
    let maxValue: Double
    var target: Double? = nil
    var height: CGFloat = 84
    let colorOf: (Bar) -> Color

    var body: some View {
        VStack(spacing: 6) {
            Canvas { ctx, size in
                let slot = size.width / CGFloat(max(bars.count, 1))
                let w = min(slot * 0.34, 10)
                for (i, bar) in bars.enumerated() {
                    let x = CGFloat(i) * slot + (slot - w) / 2
                    ctx.fill(Path(CGRect(x: x + w / 2 - 0.5, y: 0, width: 1, height: size.height)), with: .color(Palette.hairline))
                    guard bar.present else { continue }
                    let h = CGFloat(min(max(bar.value / maxValue, 0.03), 1)) * size.height
                    ctx.fill(
                        Path(roundedRect: CGRect(x: x, y: size.height - h, width: w, height: h), cornerRadius: w / 2),
                        with: .color(colorOf(bar))
                    )
                }
                if let target {
                    let y = size.height - CGFloat(min(max(target / maxValue, 0), 1)) * size.height
                    var line = Path()
                    line.move(to: CGPoint(x: 0, y: y))
                    line.addLine(to: CGPoint(x: size.width, y: y))
                    ctx.stroke(line, with: .color(Palette.text2.opacity(0.6)), style: StrokeStyle(lineWidth: 1, dash: [3, 4]))
                }
            }
            .frame(height: height)
            HStack(spacing: 0) {
                ForEach(Array(bars.enumerated()), id: \.offset) { _, bar in
                    Text(bar.label.uppercased())
                        .font(.system(size: 9))
                        .tracking(1)
                        .foregroundStyle(Palette.text2)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

/// Icon + monospaced value + eyebrow label.
struct FactTile: View {
    let symbol: String
    let value: String
    let label: String
    var tint: Color = Palette.cyan

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Image(systemName: symbol).font(.system(size: 16)).foregroundStyle(tint).frame(height: 20)
            Text(value)
                .font(.system(size: 17, design: .monospaced))
                .foregroundStyle(Palette.text1)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.top, 8)
            Eyebrow(label).padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Journey illustration (generated artwork on the brand black, so it blends into the page).
struct Illustration: View {
    private let name: String
    init(_ name: String) { self.name = name }

    var body: some View {
        Image(name).resizable().scaledToFit().accessibilityHidden(true)
    }
}

/// Full-width primary action: cyan with black text.
struct PrimaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title.uppercased())
                .font(.system(size: 14, weight: .semibold))
                .tracking(1.5)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .foregroundStyle(Palette.ink)
                .background(Palette.cyan)
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
    }
}

/// Hairline-outlined secondary action.
struct OutlineButton: View {
    let title: String
    var fullWidth = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title.uppercased())
                .font(.system(size: 12, weight: .medium))
                .tracking(1.4)
                .foregroundStyle(Palette.text1)
                .frame(maxWidth: fullWidth ? .infinity : nil)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.hairline, lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A selectable chip (stakes, day).
struct Chip: View {
    let text: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 13))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .foregroundStyle(selected ? Palette.cyan : Palette.text1)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .padding(.horizontal, 6)
                .background(selected ? Color(hex: 0x0D2A33) : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(selected ? Palette.cyan.opacity(0.6) : Palette.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
