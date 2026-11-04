import SwiftUI
import HerculesShared

/// The instrument palette, identical to the Android apps: matte black, cyan and indigo, band colours for scores.
enum Palette {
    static let ink = Color(hex: 0x05050E)          // background (matches illustrations + splash)
    static let panel = Color(hex: 0x10111B)        // raised surfaces
    static let hairline = Color(hex: 0x22243A)     // 1 pt rules and borders
    static let text1 = Color(hex: 0xE9EBF5)
    static let text2 = Color(hex: 0x8B90A8)
    static let cyan = Color(hex: 0x3FE0FF)
    static let brand = Color(hex: 0x6E6BFF)        // indigo
    static let amber = Color(hex: 0xFFC857)

    // Watch: pure black with slightly lighter rules, as on Wear OS.
    static let watchLine = Color(hex: 0x262838)

    private static let prima = Color(hex: 0x2EE6A0)
    private static let baik = Color(hex: 0x9BE35A)
    private static let cukup = Color(hex: 0xFFC857)
    private static let rendah = Color(hex: 0xFF8C4C)
    private static let terkuras = Color(hex: 0xFF4D6D)

    static func band(_ band: ReadinessBand) -> Color {
        switch band.name {
        case "PRIMA": return prima
        case "BAIK": return baik
        case "CUKUP": return cukup
        case "RENDAH": return rendah
        default: return terkuras
        }
    }

    /// Colour of any readiness-scale number (component scores, forecasts, prep).
    static func score(_ score: Int) -> Color {
        band(Catalog.shared.bandOf(score: Int32(clamping: score)))
    }

    static func verdict(_ verdict: Verdict) -> Color {
        switch verdict.name {
        case "GO": return prima
        case "GO_WITH_PREP": return baik
        case "CONSIDER_RESCHEDULE": return cukup
        default: return terkuras
        }
    }

    static func tone(_ tone: Tone) -> Color {
        switch tone.name {
        case "POSITIVE": return prima
        case "CAUTION": return cukup
        default: return cyan
        }
    }

    static func window(_ kind: WindowKind) -> Color {
        switch kind.name {
        case "PEAK": return cyan
        case "SECOND_PEAK": return Color(hex: 0x2AA7C9)
        case "STEADY": return Color(hex: 0x2B3A5C)
        case "LATE": return Color(hex: 0x1A1B2A)
        default: return Color(hex: 0x3A2F55) // waking up, dip, winding down
        }
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

/// One icon language for the whole app: SF Symbols line/fill icons that match the Material Rounded set on Android.
enum Symbols {
    static func band(_ band: ReadinessBand) -> String {
        switch band.name {
        case "PRIMA": return "bolt.fill"
        case "BAIK": return "checkmark.circle.fill"
        case "CUKUP": return "scalemass.fill"
        case "RENDAH": return "chart.line.downtrend.xyaxis"
        default: return "battery.25"
        }
    }

    static func tone(_ tone: Tone) -> String {
        switch tone.name {
        case "POSITIVE": return "checkmark.circle"
        case "CAUTION": return "exclamationmark.triangle"
        default: return "lightbulb"
        }
    }

    static func component(_ kind: ComponentKind) -> String {
        switch kind.name {
        case "SLEEP": return "moon.zzz.fill"
        case "RECOVERY": return "waveform.path.ecg"
        case "SELF_REPORT": return "brain.head.profile"
        default: return "figure.walk"
        }
    }

    static func event(_ type: EventType) -> String {
        switch type.name {
        case "INTERVIEW": return "briefcase.fill"
        case "NEGOTIATION": return "person.2.fill"
        case "PRESENTATION": return "rectangle.inset.filled.and.person.filled"
        case "EXAM": return "graduationcap.fill"
        case "DECISION": return "arrow.triangle.branch"
        default: return "star.fill"
        }
    }

    /// For a `SensorKind` name ("HEART_RATE", …).
    static func sensor(_ name: String) -> String {
        switch name {
        case "HEART_RATE": return "waveform.path.ecg"
        case "SLEEP": return "moon.zzz.fill"
        case "STEPS": return "figure.walk"
        case "CALORIES": return "flame.fill"
        case "DISTANCE": return "ruler"
        default: return "figure.stairs"
        }
    }
}
