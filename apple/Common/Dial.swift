import SwiftUI

/// The signature bezel: 100 ticks on a 270° arc, every tenth one longer. `fraction` lights ticks up to the score
/// and is animatable, so `withAnimation` sweeps the bezel up to a new value.
///
/// Draw it twice (minor and major ticks) because they use different stroke widths.
struct DialTicks: Shape {
    var fraction: Double
    var major: Bool
    /// Gap between the outer end of a tick and the edge of the frame.
    var outerInset: CGFloat
    var length: CGFloat

    var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard fraction > 0 else { return path }
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        let lit = Int((fraction * 100).rounded(.down))
        for i in 0...100 where (i % 10 == 0) == major && i <= lit {
            let angle = DialGeometry.angle(Double(i) / 100)
            let r0 = radius - outerInset - length
            let r1 = radius - outerInset
            path.move(to: CGPoint(x: center.x + r0 * CGFloat(cos(angle)), y: center.y + r0 * CGFloat(sin(angle))))
            path.addLine(to: CGPoint(x: center.x + r1 * CGFloat(cos(angle)), y: center.y + r1 * CGFloat(sin(angle))))
        }
        return path
    }
}

/// The small dot that rides the dial at the current value.
struct DialDot: Shape {
    var fraction: Double
    var inset: CGFloat
    var dotRadius: CGFloat

    var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let r = min(rect.width, rect.height) / 2 - inset
        let angle = DialGeometry.angle(fraction)
        let p = CGPoint(x: center.x + r * CGFloat(cos(angle)), y: center.y + r * CGFloat(sin(angle)))
        return Path(ellipseIn: CGRect(x: p.x - dotRadius, y: p.y - dotRadius, width: dotRadius * 2, height: dotRadius * 2))
    }
}

enum DialGeometry {
    /// Radians for a position 0…1 on the dial, starting bottom-left (135°) and sweeping 270° clockwise.
    static func angle(_ fraction: Double) -> Double { (135 + 270 * fraction) * .pi / 180 }
}

/// Helpers for the small looping animations (driven by `TimelineView`, so they cost nothing when off screen).
enum Motion {
    /// 0 → 1 → 0 over `period` seconds, eased like a breathing tween.
    static func pingPong(_ date: Date, period: Double) -> Double {
        let t = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
        return 0.5 - 0.5 * cos(2 * .pi * t)
    }

    /// 0 → 1 over `period` seconds, then starts again.
    static func loop(_ date: Date, period: Double) -> Double {
        date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
    }

    /// Scale of a heartbeat at phase 0…1 of one beat: a strong and a weak pulse, then rest.
    static func beat(_ phase: Double) -> CGFloat {
        let keys: [(Double, Double)] = [(0, 1), (0.12, 1.22), (0.28, 1), (0.38, 1.1), (0.55, 1), (1, 1)]
        for i in 1..<keys.count where phase <= keys[i].0 {
            let (x0, y0) = keys[i - 1]
            let (x1, y1) = keys[i]
            return CGFloat(y0 + (y1 - y0) * (phase - x0) / (x1 - x0))
        }
        return 1
    }
}
