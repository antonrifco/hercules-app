import Foundation
import UIKit
import HerculesShared

/// Flip to true once the backend exists (an HTTP sink in the shared core). Everything received from the watch is
/// already queued (synced = 0) for that moment, exactly as on Android.
let serverSyncEnabled = false

/// The phone's state and actions: the iOS counterpart of Android's `MobileGraph` plus what its Compose screens observe.
/// All scoring and coaching runs in the shared Kotlin core; this class only loads snapshots and forwards actions.
@MainActor
final class PhoneModel: ObservableObject {
    static let shared = PhoneModel()

    let core: HerculesCore
    private let link = PhoneLink()
    private let defaults = UserDefaults.standard

    @Published private(set) var snapshot: PhoneSnapshot?
    @Published private(set) var data: DataSnapshot?
    @Published private(set) var sensors: SensorPanel
    @Published private(set) var watch = WatchLinkStatus()
    @Published private(set) var lastWatchSync: Int64?
    @Published var serverMessage: String?

    private var started = false
    private var loop: Task<Void, Never>?
    private var pendingPush: Task<Void, Never>?

    private var sensorConfig: String {
        didSet { defaults.set(sensorConfig, forKey: Keys.sensorConfig) }
    }
    private var sensorReport: String? {
        didSet { defaults.set(sensorReport, forKey: Keys.sensorReport) }
    }

    private enum Keys {
        static let lastSync = "last_watch_sync"
        static let sensorConfig = "sensor_config"
        static let sensorReport = "sensor_report"
    }

    private init() {
        core = AppleFactory.shared.core(deviceId: DeviceIdentity.current)
        let config = UserDefaults.standard.string(forKey: Keys.sensorConfig) ?? SensorSync.shared.defaultConfig()
        let report = UserDefaults.standard.string(forKey: Keys.sensorReport)
        sensorConfig = config
        sensorReport = report
        sensors = SensorSync.shared.panel(configJson: config, reportJson: report)
        let last = Int64(UserDefaults.standard.integer(forKey: Keys.lastSync))
        lastWatchSync = last > 0 ? last : nil
    }

    /// Called at launch (also for background launches that only deliver a watch batch).
    func start() {
        guard !started else { return }
        started = true
        link.onBatch = { [weak self] json in
            guard let self else { throw CancellationError() }
            return try await self.receive(json)
        }
        link.onWatchContext = { [weak self] context in self?.applyWatchContext(context) }
        link.onActivated = { [weak self] in
            guard let self else { return }
            self.watch = self.link.status
            self.schedulePush()
        }
        link.activate()
        Task {
            #if DEBUG
            // Debug only: launch with `-demo` (Xcode scheme argument or `xcrun simctl launch … -demo`).
            if CommandLine.arguments.contains("-demo") { await loadDemo() }
            #endif
            _ = try? await core.applyRetention()
            await reload()
        }
    }

    /// While the app is in front: recompute and push to the watch every 5 minutes (like the Android app).
    func becameActive() {
        loop?.cancel()
        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshAndPush()
                try? await Task.sleep(nanoseconds: 300 * 1_000_000_000)
            }
        }
    }

    func resignedActive() {
        loop?.cancel()
        loop = nil
    }

    // MARK: Loading

    func reload() async {
        if let s = try? await core.phoneSnapshot() { snapshot = s }
        if let d = try? await core.dataSnapshot() { data = d }
        sensors = SensorSync.shared.panel(configJson: sensorConfig, reportJson: sensorReport)
        watch = link.status
    }

    /// Recompute readiness and give the watch the latest score + upcoming moments.
    func refreshAndPush() async {
        _ = try? await core.refresh()
        await pushToWatch()
        await reload()
    }

    func pushToWatch() async {
        let state = try? await core.watchStateJson()
        link.publish(state: state, sensorConfig: sensorConfig)
    }

    /// Many batches can arrive back to back; push once they settle.
    private func schedulePush() {
        pendingPush?.cancel()
        pendingPush = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await self?.refreshAndPush()
        }
    }

    // MARK: Watch link

    private func receive(_ json: String) async throws -> String {
        // The watch may have woken this app in the background just for this batch: finish storing and replying.
        let background = UIApplication.shared.beginBackgroundTask(withName: "hercules-watch-batch")
        defer { UIApplication.shared.endBackgroundTask(background) }
        let ack = try await core.receiveFromWatch(batchJson: json)
        let now = nowMs()
        defaults.set(Int(now), forKey: Keys.lastSync)
        lastWatchSync = now
        schedulePush() // new sleep/HR data can change today's score: recompute and send it back
        return ack
    }

    private func applyWatchContext(_ context: [String: Any]) {
        if let config = context[WatchLink.sensorConfigKey] as? String {
            sensorConfig = SensorSync.shared.latest(localJson: sensorConfig, remoteJson: config)
        }
        if let report = context[WatchLink.sensorReportKey] as? String {
            sensorReport = report
        }
        sensors = SensorSync.shared.panel(configJson: sensorConfig, reportJson: sensorReport)
        watch = link.status
    }

    // MARK: Actions

    func saveCheckIn(energy: Int, stress: Int) async {
        _ = try? await core.saveCheckIn(energy: Int32(energy), stress: Int32(stress))
        await pushToWatch()
        await reload()
    }

    func correctSleep(_ draft: SleepDraft) async {
        _ = try? await core.correctSleep(draft: draft)
        await pushToWatch()
        await reload()
    }

    func addEvent(title: String, importance: Importance, dayOffset: Int, hour: Int, minute: Int) async {
        _ = try? await core.addEvent(title: title, importance: importance, dayOffset: Int32(dayOffset), hour: Int32(hour), minute: Int32(minute))
        await pushToWatch()
        await reload()
    }

    func deleteEvent(id: String) async {
        _ = try? await core.deleteEvent(id: id)
        await pushToWatch()
        await reload()
    }

    func setTask(eventId: String, taskId: String, done: Bool) async {
        _ = try? await core.setTaskDone(eventId: eventId, taskId: taskId, done: done)
        await reload()
    }

    /// A new sensor choice goes to the watch; it re-registers and reports back.
    func toggleSensor(_ kind: SensorKind, on: Bool) {
        sensorConfig = SensorSync.shared.toggle(configJson: sensorConfig, sensor: kind.name, on: on, nowMs: nowMs())
        sensors = SensorSync.shared.panel(configJson: sensorConfig, reportJson: sensorReport)
        Task { await pushToWatch() }
    }

    func upload() {
        serverMessage = serverSyncEnabled ? "Server sync is not wired yet." : "No server yet, data stays queued."
    }

    func loadDemo() async {
        _ = try? await core.loadDemo()
        await pushToWatch()
        await reload()
    }
}
