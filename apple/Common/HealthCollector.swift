import Foundation
import HealthKit
import os
import HerculesShared

private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "com.hercules.app", category: "Health")

/// Reads Apple Watch data from HealthKit: the watchOS counterpart of Wear OS passive monitoring, also used on the
/// iPhone (Apple syncs the watch's Health data to the phone, so the phone has fresh nights even when the watch app
/// has not been opened).
///
/// For each enabled sensor an `HKObserverQuery` with background delivery wakes the app when new data arrives;
/// an anchored query then reads only what is new since last time, a chunk at a time, and hands it to the shared core.
/// The first read goes back 14 days, so the personal baseline is ready right away instead of after a week.
@MainActor
final class HealthCollector {
    /// Stores a batch. Returns false when it could not be stored; the anchor then stays put and the same
    /// readings are read again next time (sample ids are deterministic, so that is harmless).
    var ingest: ((HealthBatch) async -> Bool)?

    private let store = HKHealthStore()
    private let deviceId: String
    private var observers: [HKObserverQuery] = []
    /// One read per sensor at a time; a request during a read runs once more afterwards.
    private var reading: Set<String> = []
    private var readAgain: Set<String> = []

    private static let backfill: TimeInterval = 14 * 24 * 3600
    /// Samples per anchored query: keeps memory flat during the first 14-day read.
    private static let chunk = 2_000
    /// Up to 100k samples per read; whatever is left is picked up next time.
    private static let maxChunks = 50

    init(deviceId: String) {
        self.deviceId = deviceId
    }

    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Sensors that can be read here: all of them where HealthKit exists. Sleep comes from the watch's own
    /// sleep tracking (Sleep app / sleep focus).
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

    /// Only readings an Apple Watch made: iPhone-recorded steps would double count.
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
            log.error("authorization status: \(error.localizedDescription, privacy: .public)")
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
            // Runs once right away, then whenever HealthKit has new data (also in the background).
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

    /// Reads and ingests what is new for one sensor. Concurrent requests for the same sensor are folded into
    /// one more read after the current one, so the same data is never read twice in parallel.
    func collect(_ sensor: String) async {
        guard Self.isAvailable, let type = Self.sampleType(sensor) else { return }
        if reading.contains(sensor) {
            readAgain.insert(sensor)
            return
        }
        reading.insert(sensor)
        repeat {
            readAgain.remove(sensor)
            await drain(sensor, type: type)
        } while readAgain.contains(sensor)
        reading.remove(sensor)
    }

    /// Chunk by chunk from the stored anchor; the anchor moves only after a chunk is stored.
    private func drain(_ sensor: String, type: HKSampleType) async {
        for _ in 0..<Self.maxChunks {
            let batch = HealthBatch(deviceId: deviceId)
            let page: (returned: Int, anchor: HKQueryAnchor)
            do {
                page = try await readChunk(sensor, type: type, into: batch)
            } catch {
                log.error("read \(sensor, privacy: .public): \(error.localizedDescription, privacy: .public)")
                return
            }
            if batch.count > 0, let ingest = self.ingest {
                let stored = await ingest(batch)
                guard stored else { return }
            }
            Anchors.save(page.anchor, for: sensor)
            if page.returned < Self.chunk { return }
        }
    }

    private func readChunk(_ sensor: String, type: HKSampleType, into batch: HealthBatch) async throws -> (returned: Int, anchor: HKQueryAnchor) {
        let since = HKQuery.predicateForSamples(withStart: Date(timeIntervalSinceNow: -Self.backfill), end: nil, options: [])
        if let quantityType = type as? HKQuantityType {
            let descriptor = HKAnchoredObjectQueryDescriptor(
                predicates: [.quantitySample(type: quantityType, predicate: since)],
                anchor: Anchors.load(sensor),
                limit: Self.chunk
            )
            let result = try await descriptor.result(for: store)
            let unit = Self.unit(sensor)
            for sample in result.addedSamples where Self.recordedByWatch(sample) {
                batch.quantity(sensor: sensor, value: sample.quantity.doubleValue(for: unit), startMs: sample.startDate.ms, endMs: sample.endDate.ms)
            }
            return (result.addedSamples.count + result.deletedObjects.count, result.newAnchor)
        }
        if let categoryType = type as? HKCategoryType {
            let descriptor = HKAnchoredObjectQueryDescriptor(
                predicates: [.categorySample(type: categoryType, predicate: since)],
                anchor: Anchors.load(sensor),
                limit: Self.chunk
            )
            let result = try await descriptor.result(for: store)
            for sample in result.addedSamples where Self.isAsleep(sample.value) {
                batch.asleep(startMs: sample.startDate.ms, endMs: sample.endDate.ms)
            }
            return (result.addedSamples.count + result.deletedObjects.count, result.newAnchor)
        }
        throw HealthReadError.unsupportedType
    }
}

private enum HealthReadError: Error {
    case unsupportedType
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
