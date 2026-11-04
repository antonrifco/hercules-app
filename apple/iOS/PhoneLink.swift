import Foundation
import os
import WatchConnectivity

private let log = Logger(subsystem: "com.hercules.app", category: "HerculesPhoneSync")

/// What the Data tab shows about the watch.
struct WatchLinkStatus: Equatable {
    var paired = false
    var installed = false
    var reachable = false

    var linked: Bool { paired && installed }
}

/// WatchConnectivity on the phone: the Apple counterpart of Android's `WatchSyncListenerService` plus the
/// Data Layer items the phone writes.
///
/// Delegate callbacks arrive on a background queue; they hop to the main actor, where the shared Kotlin core is used.
final class PhoneLink: NSObject, WCSessionDelegate {
    /// Stores a sync batch (JSON) and returns the ack JSON to reply with.
    var onBatch: (@MainActor (String) async throws -> String)?
    /// The watch's application context (sensor choice + report).
    var onWatchContext: (@MainActor ([String: Any]) -> Void)?
    /// Session became usable: a good moment to push the latest state.
    var onActivated: (@MainActor () -> Void)?

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    var status: WatchLinkStatus {
        guard WCSession.isSupported() else { return WatchLinkStatus() }
        let s = WCSession.default
        guard s.activationState == .activated else { return WatchLinkStatus() }
        return WatchLinkStatus(paired: s.isPaired, installed: s.isWatchAppInstalled, reachable: s.isReachable)
    }

    /// Latest readiness + events for the watch, and the sensor choice. Delivered when the watch is reachable;
    /// only the newest context is kept, like a Data Layer item.
    func publish(state: String?, sensorConfig: String) {
        guard WCSession.isSupported() else { return }
        let s = WCSession.default
        guard s.activationState == .activated, s.isPaired, s.isWatchAppInstalled else { return }
        var context: [String: Any] = [WatchLink.sensorConfigKey: sensorConfig, WatchLink.updatedKey: nowMs()]
        if let state { context[WatchLink.stateKey] = state }
        do {
            try s.updateApplicationContext(context)
        } catch {
            log.error("could not update application context: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        let context = session.receivedApplicationContext
        Task { @MainActor in
            if !context.isEmpty { self.onWatchContext?(context) }
            self.onActivated?()
        }
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        session.activate() // the user switched watches
    }

    /// A sync batch from the watch. The reply carries the ids that were stored; without a reply the watch keeps
    /// the data and retries later, so nothing is lost if the phone fails mid-way.
    func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
        let json = String(decoding: messageData, as: UTF8.self)
        Task { @MainActor in
            guard let handler = self.onBatch else { return }
            do {
                let ack = try await handler(json)
                replyHandler(Data(ack.utf8))
            } catch {
                log.error("failed to store watch batch: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        Task { @MainActor in self.onWatchContext?(applicationContext) }
    }

    func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.onActivated?() }
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.onActivated?() }
    }
}
