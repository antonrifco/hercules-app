import SwiftUI
import HerculesShared

@main
struct HerculesWatchApp: App {
    @StateObject private var model = WatchModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environmentObject(model)
                .onChange(of: scenePhase, initial: true) { _, phase in
                    if phase == .active { model.becameActive() } else { model.resignedActive() }
                }
        }
        .backgroundTask(.appRefresh(WatchTasks.refresh)) {
            await WatchModel.runBackgroundRefresh()
        }
    }
}

struct WatchRootView: View {
    @EnvironmentObject private var model: WatchModel

    var body: some View {
        NavigationStack {
            if model.needsPermission {
                PermissionView()
            } else {
                WatchHomeView()
            }
        }
        .tint(Palette.cyan)
    }
}

/// Health access, asked once. Choose to allow heart rate and sleep so nights are scored.
struct PermissionView: View {
    @EnvironmentObject private var model: WatchModel

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                PulseRings().frame(width: 64, height: 64)
                Text("Hercules reads your heart and sleep from Health to score your day. Allow them so nights are tracked.")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Palette.text2)
                Button("Allow") {
                    Task { await model.allowHealthAccess() }
                }
                .tint(Palette.cyan)
            }
            .padding(.horizontal, 4)
        }
    }
}

/// Switch background sensors on or off; the phone sees the same choice.
struct SensorsView: View {
    @EnvironmentObject private var model: WatchModel

    var body: some View {
        List {
            ForEach(Catalog.shared.sensors, id: \.name) { kind in
                let available = model.supportedSensors.contains(kind.name)
                Toggle(isOn: Binding(
                    get: { available && model.isEnabled(kind.name) },
                    set: { model.setSensor(kind.name, on: $0) }
                )) {
                    HStack(spacing: 8) {
                        Image(systemName: Symbols.sensor(kind.name)).foregroundStyle(Palette.cyan).frame(width: 20)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(kind.label)
                            Text(available ? kind.feeds : "Not on this watch")
                                .font(.footnote)
                                .foregroundStyle(Palette.text2)
                        }
                    }
                }
                .tint(Palette.cyan)
                .disabled(!available)
            }
        }
        .navigationTitle("Sensors")
    }
}
