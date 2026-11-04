import Foundation

/// Epoch milliseconds, the time unit of the shared Kotlin core.
func nowMs() -> Int64 { Date().ms }

extension Date {
    /// Epoch milliseconds.
    var ms: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}

/// Date and time formatting. English, 24-hour, device time zone: the same strings as the Android apps.
enum Fmt {
    private static func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone.autoupdatingCurrent
        f.dateFormat = pattern
        return f
    }

    private static let clockFormat = formatter("HH:mm")
    private static let shortDateFormat = formatter("EEE, d MMM")
    private static let longDateFormat = formatter("EEEE, d MMMM")
    private static let dayTimeFormat = formatter("EEE HH:mm")
    private static let relativeFormat: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.unitsStyle = .full
        return f
    }()

    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }

    /// "14:05"
    static func clock(_ ms: Int64) -> String { clockFormat.string(from: date(ms)) }
    /// "Wed, 4 Nov"
    static func shortDate(_ ms: Int64) -> String { shortDateFormat.string(from: date(ms)) }
    /// "Wednesday, 4 November"
    static func longDate(_ ms: Int64) -> String { longDateFormat.string(from: date(ms)) }
    /// "Wed 10:00"
    static func dayTime(_ ms: Int64) -> String { dayTimeFormat.string(from: date(ms)) }
    /// "5 minutes ago"
    static func relative(_ ms: Int64) -> String { relativeFormat.localizedString(for: date(ms), relativeTo: Date()) }

    /// "Today", "D-3", "Done"
    static func dayTag(_ daysUntil: Int32) -> String {
        if daysUntil < 0 { return "Done" }
        if daysUntil == 0 { return "Today" }
        return "D-\(daysUntil)"
    }
}

extension String {
    /// Shortens to at most `max` characters, ending with "…" when anything was cut.
    func clipped(_ max: Int) -> String {
        guard count > max else { return self }
        return String(prefix(max - 1)).trimmingCharacters(in: .whitespaces) + "…"
    }
}

/// A random id per install, like the Android `DeviceId`. Tags every reading this device records.
enum DeviceIdentity {
    static var current: String {
        let defaults = UserDefaults.standard
        if let id = defaults.string(forKey: "device_id") { return id }
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: "device_id")
        return id
    }
}

/// WatchConnectivity contract between the iPhone and Apple Watch apps (the Wear OS Data Layer paths, Apple style).
///
/// - Sync batches: watch → phone with `sendMessageData` (sample-batch v1 JSON); the reply is the ack JSON.
/// - Application context, phone → watch: latest `WatchState` JSON and the sensor choice.
/// - Application context, watch → phone: the sensor choice and the watch's `SensorReport`.
/// Each side's context always carries all of its keys, because a new context replaces the previous one.
enum WatchLink {
    static let stateKey = "hercules.state"
    static let sensorConfigKey = "hercules.sensors.config"
    static let sensorReportKey = "hercules.sensors.report"
    static let updatedKey = "hercules.updatedMs"
}
