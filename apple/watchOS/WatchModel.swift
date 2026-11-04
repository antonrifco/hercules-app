import Foundation
import WatchKit
import os
import HerculesShared

private let log = Logger(subsystem: "com.hercules.app.watchkitapp", category: "HerculesSync")

enum WatchTasks {
    /// Background app refresh: collect, then hand data to the phone. Every 15 minutes, like the Wear OS SyncWorker.
    static let refresh = "hercules-sync"
}

/// The watch's state and jobs: the watchOS counterpart of Wear OS `WearGraph`, `SyncWorker` and `SyncStatus`.
///
/// The watch does three things only: today's readiness, what it is tracking and whether that reached the phone,
/// and readiness for the big moments ahead. Planning, check-ins and sleep corrections live on the phone.
@MainActor
final class WatchModel: ObservableObject {
    static let shared = WatchModel()

    let core: HerculesCore
    private let health: HealthCollector
    private let link = WatchSession()
    private let defaults = UserDefaults.standard

    @Published private(set) var home: WatchHome?
    @Published private(set) var needsPermission = false
    @Published private(set) var sensorConfig: String

    private var phoneState: String?
    private var lastSyncOk: Int64
    private var syncProblem: String?
    private var syncing = false
    private var syncAgain = false
    private var demo = false
    private var loop: Task<Void, Never>?

    private enum Keys {
        static let sensorConfig = "sensor_config"
        static let phoneState = "phone_state"
        static let lastOk = "sync_ok"
        static let lastAttempt = "sync_attempt"
        static let problem = "sync_problem"
    }

    private init() {
        let deviceId = DeviceIdentity.current
        let defaults = UserDefaults.standard
        core = AppleFactory.shared.core(deviceId: deviceId)
        health = HealthCollector(deviceId: deviceId)
        sensorConfig = defaults.string(forKey: Keys.sensorConfig) ?? SensorSync.shared.defaultConfig()
        phoneState = defaults.string(forKey: Keys.phoneState)
        lastSyncOk = Int64(defaults.integer(forKey: Keys.lastOk))
        syncProblem = defaults.string(forKey: Keys.problem)

        health.ingest = { [weak self] batch in await self?.ingest(batch) ?? false }
        link.onPhoneContext = { [weak self] context in self?.applyPhoneContext(context) }
        link.activate()
        // Observers must be registered at every launch, including background launches by HealthKit.
        Task { await bootstrap() }
    }

    var enabledSensors: [String] { SensorSync.shared.enabled(configJson: sensorConfig) }
    var supportedSensors: [String] { HealthCollector.supported }

    func isEnabled(_ sensor: String) -> Bool { SensorSync.shared.isEnabled(configJson: sensorConfig, sensor: sensor) }

    private func bootstrap() async {
        #if DEBUG
        // Debug only: launch with `-demo` to load the same 14-day demo as the phone, without HealthKit.
        if CommandLine.arguments.contains("-demo") {
            demo = true
            _ = try? await core.loadDemo()
        }
        #endif
        if !demo {
            if await health.needsAuthorization() {
                needsPermission = true
            } else {
                startCollecting()
            }
        }
        publishSensors()
        await reloadHome()
        scheduleBackgroundRefresh()
    }

    // MARK: Permission and sensors

    func allowHealthAccess() async {
        do {
            try await health.requestAuthorization()
        } catch {
            log.error("authorization: \(error.localizedDescription, privacy: .public)")
        }
        needsPermission = false
        startCollecting()
        publishSensors()
    }

    private func startCollecting() {
        let sensors = enabledSensors
        health.start(sensors)
        Task {
            await health.collectAll(sensors)
            await syncNow()
        }
    }

    /// Changed on the watch: store, re-register, and tell the phone.
    func setSensor(_ sensor: String, on: Bool) {
        sensorConfig = SensorSync.shared.toggle(configJson: sensorConfig, sensor: sensor, on: on, nowMs: nowMs())
        applySensorChoice()
    }

    private func applySensorChoice() {
        defaults.set(sensorConfig, forKey: Keys.sensorConfig)
        if !needsPermission && !demo { startCollecting() }
        publishSensors()
    }

    private func publishSensors() {
        let supported = supportedSensors
        let active = needsPermission ? [] : enabledSensors.filter { supported.contains($0) }
        link.publish(
            sensorConfig: sensorConfig,
            sensorReport: SensorSync.shared.report(supported: supported, active: active, nowMs: nowMs())
        )
    }

    /// From the phone: the latest readiness + moments, and its sensor choice (applied only if newer).
    private func applyPhoneContext(_ context: [String: Any]) {
        if let state = context[WatchLink.stateKey] as? String {
            phoneState = state
            defaults.set(state, forKey: Keys.phoneState)
        }
        if let config = context[WatchLink.sensorConfigKey] as? String {
            let newest = SensorSync.shared.latest(localJson: sensorConfig, remoteJson: config)
            if newest != sensorConfig {
                sensorConfig = newest
                applySensorChoice()
            }
        }
        Task { await reloadHome() }
    }

    // MARK: Data flow

    private func ingest(_ batch: HealthBatch) async -> Bool {
        do {
            try await core.ingest(batch: batch)
        } catch {
            log.error("ingest: \(error.localizedDescription, privacy: .public)")
            return false
        }
        await reloadHome()
        Task { await syncNow() }
        return true
    }

    /// Summaries → retention → hand data to the phone. Runs one at a time; a request during a run queues one more.
    func syncNow() async {
        guard !syncing else {
            syncAgain = true
            return
        }
        syncing = true
        repeat {
            syncAgain = false
            do {
                let report = try await core.syncToPhone(transport: link)
                record(problem: report.problem, delivered: Int(report.delivered))
                log.info("sync: delivered=\(report.delivered) problem=\(report.problem ?? "-", privacy: .public)")
            } catch {
                record(problem: error.localizedDescription, delivered: 0)
            }
        } while syncAgain
        syncing = false
        await reloadHome()
    }

    /// "OK" only moves when the phone actually acknowledged data; an empty run just clears the problem.
    private func record(problem: String?, delivered: Int) {
        let now = nowMs()
        defaults.set(Int(now), forKey: Keys.lastAttempt)
        if delivered > 0 {
            lastSyncOk = now
            defaults.set(Int(now), forKey: Keys.lastOk)
        }
        syncProblem = problem
        defaults.set(problem, forKey: Keys.problem)
    }

    func reloadHome() async {
        do {
            home = try await core.watchHome(phoneStateJson: phoneState, lastSyncOkMs: lastSyncOk, syncProblem: syncProblem)
        } catch {
            log.error("home: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: Lifecycle

    /// While the app is visible: collect, hand data to the phone and refresh the screen every minute.
    func becameActive() {
        loop?.cancel()
        loop = Task { [weak self] in
            guard let self else { return }
            if !self.needsPermission && !self.demo { await self.health.collectAll(self.enabledSensors) }
            while !Task.isCancelled {
                await self.syncNow()
                try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
            }
        }
    }

    func resignedActive() {
        loop?.cancel()
        loop = nil
    }

    nonisolated static func runBackgroundRefresh() async {
        await shared.backgroundRefresh()
    }

    private func backgroundRefresh() async {
        if !needsPermission && !demo { await health.collectAll(enabledSensors) }
        await syncNow()
        scheduleBackgroundRefresh()
    }

    func scheduleBackgroundRefresh() {
        WKApplication.shared().scheduleBackgroundRefresh(
            withPreferredDate: Date(timeIntervalSinceNow: 15 * 60),
            userInfo: WatchTasks.refresh as NSString
        ) { error in
            if let error { log.error("schedule refresh: \(error.localizedDescription, privacy: .public)") }
        }
    }
}
