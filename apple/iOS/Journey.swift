import SwiftUI

// MARK: - Launch

/// Picks up where the system launch screen ends (same mark, same black): shows the splash artwork once,
/// then fades into the app. Tap to skip.
struct LaunchArtView: View {
    let onFinish: () -> Void
    @State private var visible = true

    var body: some View {
        Color(hex: 0x070B13)
            .overlay(Image("SplashArt").resizable().scaledToFill())
            .clipped()
            .ignoresSafeArea()
            .opacity(visible ? 1 : 0)
            .onTapGesture { finish() }
            .task {
                try? await Task.sleep(nanoseconds: 2_600_000_000)
                finish()
            }
            .accessibilityHidden(true)
    }

    private func finish() {
        guard visible else { return }
        withAnimation(.easeOut(duration: 0.5)) { visible = false }
        Task {
            try? await Task.sleep(nanoseconds: 500_000_000)
            onFinish()
        }
    }
}

// MARK: - Onboarding

private struct OnboardingStep {
    let image: String
    let eyebrow: String
    let title: String
    let body: String
}

private let onboardingSteps = [
    OnboardingStep(image: "ill_score", eyebrow: "01 · The dial", title: "Your brain has a readiness level",
                   body: "Every morning Hercules reads it, 1 to 100, from your sleep, heart and how you feel."),
    OnboardingStep(image: "ill_sleep", eyebrow: "02 · The setup", title: "Sleep sets the dial",
                   body: "Wear your Apple Watch to bed. That's the whole setup. It learns your normal in 7 nights."),
    OnboardingStep(image: "ill_plan", eyebrow: "03 · The plan", title: "Aim for the big day",
                   body: "Add an interview or a pitch up to 7 days out. Get a daily run-up and a clear call on the day."),
]

struct OnboardingView: View {
    let onDone: () -> Void
    @State private var page = 0

    var body: some View {
        let last = page == onboardingSteps.count - 1
        VStack(spacing: 0) {
            HStack {
                Text("HERCULES").font(.system(size: 13, weight: .medium)).tracking(5).foregroundStyle(Palette.text1)
                Spacer()
                Button(action: onDone) {
                    Text("SKIP").font(.system(size: 12)).tracking(1.5).foregroundStyle(Palette.text2)
                }
            }
            .padding(.top, 12)
            TabView(selection: $page) {
                ForEach(onboardingSteps.indices, id: \.self) { i in
                    StepPage(step: onboardingSteps[i])
                        .opacity(page == i ? 1 : 0.3)
                        .animation(.easeInOut(duration: 0.4), value: page)
                        .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            HStack {
                HStack(spacing: 6) {
                    ForEach(onboardingSteps.indices, id: \.self) { i in
                        Rectangle()
                            .fill(i <= page ? Palette.cyan : Palette.hairline)
                            .frame(width: i == page ? 28 : 10, height: 3)
                    }
                }
                .animation(.easeInOut(duration: 0.25), value: page)
                Spacer()
                Button {
                    if last { onDone() } else { withAnimation { page += 1 } }
                } label: {
                    Text(last ? "START" : "NEXT")
                        .font(.system(size: 14, weight: .semibold))
                        .tracking(1.5)
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, 22)
                        .padding(.vertical, 12)
                        .background(Palette.cyan)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .buttonStyle(.plain)
            }
            .padding(.vertical, 20)
        }
        .padding(.horizontal, 24)
        .background(Palette.ink.ignoresSafeArea())
    }
}

private struct StepPage: View {
    let step: OnboardingStep

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 0)
            Illustration(step.image).frame(maxWidth: .infinity).aspectRatio(1, contentMode: .fit)
            Eyebrow(step.eyebrow, color: Palette.cyan)
            Text(step.title)
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(Palette.text1)
                .padding(.top, 10)
            Text(step.body)
                .font(.system(size: 15))
                .foregroundStyle(Palette.text2)
                .lineSpacing(5)
                .padding(.top, 12)
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Navigation

enum PhoneTab: String, CaseIterable {
    case today = "Today"
    case plan = "Plan"
    case data = "Data"

    var symbol: String {
        switch self {
        case .today: return "speedometer"
        case .plan: return "calendar"
        case .data: return "chart.xyaxis.line"
        }
    }
}

/// Hairline-topped bar: the active tab gets a cyan rule and cyan icon. No pills.
struct HerculesTabBar: View {
    @Binding var selected: PhoneTab

    var body: some View {
        VStack(spacing: 0) {
            HairlineRule()
            HStack(spacing: 0) {
                ForEach(PhoneTab.allCases, id: \.self) { tab in
                    let on = tab == selected
                    Button { selected = tab } label: {
                        VStack(spacing: 0) {
                            Rectangle().fill(on ? Palette.cyan : Color.clear).frame(width: 28, height: 2)
                            Spacer(minLength: 0)
                            Image(systemName: tab.symbol).font(.system(size: 20)).foregroundStyle(on ? Palette.cyan : Palette.text2)
                            Text(tab.rawValue.uppercased())
                                .font(.system(size: 9))
                                .tracking(1.5)
                                .foregroundStyle(on ? Palette.text1 : Palette.text2)
                                .padding(.top, 4)
                            Spacer(minLength: 0)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 58)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tab.rawValue)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
        .background(Palette.ink.ignoresSafeArea(edges: .bottom))
    }
}
