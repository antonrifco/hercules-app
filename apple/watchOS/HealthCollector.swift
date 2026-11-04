import Foundation
import HealthKit
import os
import HerculesShared

private let log = Logger(subsystem: "com.hercules.app.watchkitapp", category: "Hercules")

/// HealthKit on the watch: the watchOS counterpart of Wear OS passive monitoring.
///
/// For each enabled sensor an `HKObserverQuery` with background delivery wakes the app when new data arrives;
/// an anchored query then reads only what is new since last time and hands it to the shared core.
/// The first read goes back 14 days, so the personal baseline is ready right away instead of after a week.
@MainActor
final class HealthCollector {
    /// Stores a batch. Returns false when it could not be stored; the anchor then stays put and the same
    /// readings are read again next time (sample ids are deterministic, so that is harmless).
    var ingest: ((HealthBatch) async -> Bool)?

    private let store = HKHealthStore()
    private let deviceId: String
    private var observers: [HKObserverQuery] = []
    private static let backfill: TimeInterval = 14 * 24 * 3600

    init(deviceId: String) {
        self.deviceId = deviceId
    }

    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Sensors this watch can record: all of them where HealthKit exists. Sleep comes from the watch's own
    /// sleep tracking (Sleep app / sleep focus), read from HealthKit.
    static var supported: [String] { isAvailable ? Catalog.shared.sensors.map(\.name) : [] }

    // MARK: Mapping (SensorKind name → HealthKit)

    nonisolated static func sampleType(_ sensor: String) -> HKSampleType? {
        switch sensor {
        case "HEART_RATE": return HKQuantityType(.heartRate)
        case "STEPS": return HKQuantityType(.stepCount)
        case "CALORIES": return HKQuantityType(.activeEnergyBurned)
        case "DISTANCE": return HKQuantityType(.distanceWalkingRunning)
        case "FLOORS": return HKQuantityType(.flightsClimbed)
        case "SLEEP": return HKCategoryType(.sleepAnalysis)
        default: return nil
        }
    }

    nonisolated static func unit(_ sensor: String) -> HKUnit {
        switch sensor {
        case "HEART_RATE": return HKUnit.count().unitDivided(by: .minute())
        case "CALORIES": return .kilocalorie()
        case "DISTANCE": return .meter()
        default: return .count()
        }
    }

    /// Only readings this Apple Watch made: phone-recorded steps synced into HealthKit would double count.
    nonisolated static func recordedByWatch(_ sample: HKSample) -> Bool {
        sample.sourceRevision.productType?.hasPrefix("Watch") == true
    }

    /// Core, deep, REM or unspecified sleep. "In bed" and "awake" are not sleep.
    nonisolated static func isAsleep(_ value: Int) -> Bool {
        guard let v = HKCategoryValueSleepAnalysis(rawValue: value) else { return false }
        return HKCategoryValueSleepAnalysis.allAsleepValues.contains(v)
    }

    private static var allReadTypes: Set<HKObjectType> {
        Set(Catalog.shared.sensors.compactMap { sampleType($0.name) }.map { $0 as HKObjectType })
    }

    // MARK: Authorization

    /// True until the user has been asked once (HealthKit never reveals whether reading was allowed).
    func needsAuthorization() async -> Bool {
        guard Self.isAvailable else { return false }
        do {
            return try await store.statusForAuthorizationRequest(toShare: [], read: Self.allReadTypes) == .shouldRequest
        } catch {
            return true
        }
    }

    /// Asks for every sensor at once, so switching one on later needs no new prompt.
    func requestAuthorization() async throws {
        guard Self.isAvailable else { return }
        try await store.requestAuthorization(toShare: [], read: Self.allReadTypes)
    }

    // MARK: Collection

    /// (Re-)registers observers with background delivery for exactly these sensors.
    func start(_ sensors: [String]) {
        stop()
        guard Self.isAvailable else { return }
        for name in Catalog.shared.sensors.map(\.name) {
            guard let type = Self.sampleType(name) else { continue }
            guard sensors.contains(name) else {
                store.disableBackgroundDelivery(for: type) { _, _ in }
                continue
            }
            let sensor = name
            let query = HKObserverQuery(sampleType: type, predicate: nil) { [weak self] _, completion, error in
                if let error {
                    log.error("observer \(sensor, privacy: .public): \(error.localizedDescription, privacy: .public)")
                    completion()
                    return
                }
                Task { @MainActor in
                    await self?.collect(sensor)
                    completion()
                }
            }
            store.execute(query)
            observers.append(query)
            store.enableBackgroundDelivery(for: type, frequency: .hourly) { ok, error in
                if !ok { log.error("background delivery \(sensor, privacy: .public): \(error?.localizedDescription ?? "?", privacy: .public)") }
            }
        }
        log.info("collecting \(sensors.joined(separator: ","), privacy: .public)")
    }

    func stop() {
        for query in observers { store.stop(query) }
        observers.removeAll()
    }

    func collectAll(_ sensors: [String]) async {
        for sensor in sensors { await collect(sensor) }
    }

    /// Reads what is new for one sensor since the stored anchor and ingests it.
    func collect(_ sensor: String) async {
        guard Self.isAvailable, let type = Self.sampleType(sensor) else { return }
        let since = HKQuery.predicateForSamples(withStart: Date(timeIntervalSinceNow: -Self.backfill), end: nil, options: [])
        let batch = HealthBatch(deviceId: deviceId)
        let newAnchor: HKQueryAnchor
        do {
            if let quantityType = type as? HKQuantityType {
                let descriptor = HKAnchoredObjectQueryDescriptor(
                    predicates: [.quantitySample(type: quantityType, predicate: since)],
                    anchor: Anchors.load(sensor)
                )
                let result = try await descriptor.result(for: store)
                let unit = Self.unit(sensor)
                for sample in result.addedSamples where Self.recordedByWatch(sample) {
                    batch.quantity(sensor: sensor, value: sample.quantity.doubleValue(for: unit), startMs: sample.startDate.ms, endMs: sample.endDate.ms)
                }
                newAnchor = result.newAnchor
            } else if let categoryType = type as? HKCategoryType {
                let descriptor = HKAnchoredObjectQueryDescriptor(
                    predicates: [.categorySample(type: categoryType, predicate: since)],
                    anchor: Anchors.load(sensor)
                )
                let result = try await descriptor.result(for: store)
                for sample in result.addedSamples where Self.isAsleep(sample.value) {
                    batch.asleep(startMs: sample.startDate.ms, endMs: sample.endDate.ms)
                }
                newAnchor = result.newAnchor
            } else {
                return
            }
        } catch {
            log.error("read \(sensor, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return
        }
        if batch.count > 0, let ingest = self.ingest {
            let stored = await ingest(batch)
            guard stored else { return }
        }
        Anchors.save(newAnchor, for: sensor)
    }
}

/// Where each sensor's anchored query left off.
private enum Anchors {
    private static func key(_ sensor: String) -> String { "hk_anchor_\(sensor)" }

    static func load(_ sensor: String) -> HKQueryAnchor? {
        guard let data = UserDefaults.standard.data(forKey: key(sensor)) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: data)
    }

    static func save(_ anchor: HKQueryAnchor, for sensor: String) {
        guard let data = try? NSKeyedArchiver.archivedData(withRootObject: anchor, requiringSecureCoding: true) else { return }
        UserDefaults.standard.set(data, forKey: key(sensor))
    }
}
