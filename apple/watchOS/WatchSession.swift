import Foundation
import os
import WatchConnectivity
import HerculesShared

private let log = Logger(subsystem: "com.hercules.app.watchkitapp", category: "HerculesSync")

/// WatchConnectivity on the watch: the watchOS counterpart of Wear OS `PhoneRemoteSink` and the Data Layer items.
///
/// Implements the shared core's `WatchTransport`: each sync batch goes to the phone with `sendMessageData`, and the
/// phone's reply is the ack. The core marks only acknowledged ids as synced, so a failed send loses nothing.
final class WatchSession: NSObject, WCSessionDelegate, WatchTransport {
    /// The phone's application context (latest `WatchState` and sensor choice).
    var onPhoneContext: (@MainActor ([String: Any]) -> Void)?

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    // MARK: WatchTransport (called by the shared core, from any thread)

    func send(batchJson: String, onReply: @escaping (String) -> Void, onFailure: @escaping (String) -> Void) {
        guard WCSession.isSupported() else { return onFailure("no phone link on this device") }
        let session = WCSession.default
        guard session.activationState == .activated else { return onFailure("phone link not active yet") }
        guard session.isReachable else { return onFailure("phone app not reachable") }
        session.sendMessageData(
            Data(batchJson.utf8),
            replyHandler: { reply in onReply(String(decoding: reply, as: UTF8.self)) },
            errorHandler: { error in onFailure(error.localizedDescription) }
        )
    }

    /// The sensor choice and what this watch is actually recording, for the phone's sensor grid.
    func publish(sensorConfig: String, sensorReport: String) {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        do {
            try session.updateApplicationContext([
                WatchLink.sensorConfigKey: sensorConfig,
                WatchLink.sensorReportKey: sensorReport,
                WatchLink.updatedKey: nowMs(),
            ])
        } catch {
            log.error("could not update application context: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if let error { log.error("activation: \(error.localizedDescription, privacy: .public)") }
        let context = session.receivedApplicationContext
        guard !context.isEmpty else { return }
        Task { @MainActor in self.onPhoneContext?(context) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        Task { @MainActor in self.onPhoneContext?(applicationContext) }
    }
}
