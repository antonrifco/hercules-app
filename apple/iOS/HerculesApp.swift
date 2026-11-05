import SwiftUI
import UIKit
import HerculesShared

/// Starts WatchConnectivity as early as possible: the watch can wake this app in the background just to deliver
/// a sync batch, before any window exists.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        #if DEBUG
        // Debug only, for screenshots: `-skipOnboarding YES`.
        if UserDefaults.standard.bool(forKey: "skipOnboarding") { UserDefaults.standard.set(true, forKey: "onboarded") }
        #endif
        PhoneModel.shared.start()
        return true
    }
}

@main
struct HerculesApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = PhoneModel.shared
    @AppStorage("onboarded") private var onboarded = false
    @State private var showLaunchArt = true
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ZStack {
                if onboarded {
                    RootView()
                } else {
                    OnboardingView {
                        onboarded = true
                        Task { await model.requestHealthAccess() }
                    }
                }
                if showLaunchArt {
                    LaunchArtView { showLaunchArt = false }
                }
            }
            .environmentObject(model)
            .preferredColorScheme(.dark) // always dark: it reads like an instrument panel, and matches icon and splash
            .onChange(of: scenePhase, initial: true) { _, phase in
                if phase == .active { model.becameActive() } else { model.resignedActive() }
            }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: PhoneModel
    @State private var tab: PhoneTab = .today
    @State private var openEventId: String?
    @State private var adding = false
    @State private var guide = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottomTrailing) {
                Group {
                    switch tab {
                    case .today:
                        TodayView(snap: model.snapshot, onOpenEvent: { openEventId = $0 }, onOpenGuide: { guide = true })
                    case .plan:
                        PlanView(events: model.snapshot?.events ?? [], onOpen: { openEventId = $0 })
                    case .data:
                        DataView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if tab == .plan {
                    NewMomentButton { adding = true }
                        .padding(.trailing, 20)
                        .padding(.bottom, 16)
                }
            }
            .background(Palette.ink.ignoresSafeArea())
            .safeAreaInset(edge: .bottom, spacing: 0) { HerculesTabBar(selected: $tab) }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(item: $openEventId) { id in
                EventDetailView(eventId: id).environmentObject(model)
            }
        }
        .tint(Palette.text1)
        .sheet(isPresented: $adding) {
            AddMomentSheet().environmentObject(model)
        }
        .sheet(isPresented: $guide) {
            GuideView().presentationDetents([.large]).presentationDragIndicator(.visible)
        }
        #if DEBUG
        .task { await openDebugScreen() }
        #endif
    }

    #if DEBUG
    /// Debug only, for screenshots: `-screen plan|data|guide|add|event`.
    private func openDebugScreen() async {
        switch UserDefaults.standard.string(forKey: "screen") ?? "" {
        case "plan": tab = .plan
        case "data": tab = .data
        case "guide": guide = true
        case "add":
            tab = .plan
            adding = true
        case "event":
            tab = .plan
            for _ in 0..<40 where openEventId == nil {
                if let id = model.snapshot?.nextEvent?.event.id ?? model.snapshot?.events.first?.event.id {
                    openEventId = id
                } else {
                    try? await Task.sleep(nanoseconds: 250_000_000)
                }
            }
        default: break
        }
    }
    #endif
}

private struct NewMomentButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: "plus").font(.system(size: 15, weight: .semibold))
                Text("NEW MOMENT").font(.system(size: 14, weight: .semibold)).tracking(1.5)
            }
            .foregroundStyle(Palette.ink)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(Palette.cyan)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .shadow(color: Palette.cyan.opacity(0.25), radius: 12, y: 4)
        }
        .buttonStyle(.plain)
    }
}
