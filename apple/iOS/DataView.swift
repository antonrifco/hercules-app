import SwiftUI
import HerculesShared

/// Data: the watch link, sensors, raw body data, personal bests and the (future) server queue.
struct DataView: View {
    @EnvironmentObject private var model: PhoneModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow("Signals")
                    Text("Body data").font(.system(size: 28, weight: .light)).foregroundStyle(Palette.text1)
                }
                WatchLinkSection(status: model.watch, lastSync: model.lastWatchSync)
                if model.healthNeedsAccess {
                    HealthAccessSection { Task { await model.requestHealthAccess() } }
                }
                SensorGrid(panel: model.sensors) { kind, on in model.toggleSensor(kind, on: on) }
                TodayStats(day: model.data?.today)
                PersonalBests(records: model.data?.records ?? [])
                ServerSection(pending: model.data?.pending ?? 0, message: model.serverMessage) { model.upload() }
                #if DEBUG
                HSection("Developer") {
                    OutlineButton(title: "Load 14 days of demo data") {
                        Task { await model.loadDemo() }
                    }
                }
                #endif
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
    }
}

/// Watch link status. Illustrated when no watch is linked.
private struct WatchLinkSection: View {
    let status: WatchLinkStatus
    let lastSync: Int64?

    var body: some View {
        HSection("Watch link", trailing: {
            PillTag(text: status.linked ? "Linked" : "Offline", color: status.linked ? Palette.cyan : Palette.text2)
        }) {
            if status.linked {
                Text("Apple Watch").font(.system(size: 17)).foregroundStyle(Palette.text1)
                Muted("Last data " + (lastSync.map { Fmt.relative($0).lowercased() } ?? "never"))
            } else {
                Illustration("ill_no_watch").frame(height: 170).frame(maxWidth: .infinity)
                Muted("Open Hercules on your Apple Watch. It syncs every 15 minutes over Bluetooth.").padding(.top, 4)
            }
        }
    }
}

/// Shown until Health access was asked: the phone reads the watch's nights from Health, so the score is ready
/// in the morning even if the watch app was not opened.
private struct HealthAccessSection: View {
    let onAllow: () -> Void

    var body: some View {
        HSection("Health") {
            Muted("Let Hercules read your Apple Watch's sleep, heart rate and activity from Health on this iPhone, so your score is ready each morning even before you open the watch app.")
            OutlineButton(title: "Allow Health access", action: onAllow).padding(.top, 10)
        }
    }
}

/// Two-column grid of sensor tiles; a lit tile is recording.
private struct SensorGrid: View {
    let panel: SensorPanel
    let onToggle: (SensorKind, Bool) -> Void

    var body: some View {
        HSection("Sensors", trailing: {
            Text(panel.summary).font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.text2)
        }) {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(panel.tiles, id: \.kind.name) { tile in
                    SensorTileView(tile: tile) { onToggle(tile.kind, !tile.on) }
                }
            }
        }
    }
}

private struct SensorTileView: View {
    let tile: SensorTile
    let onTap: () -> Void

    var body: some View {
        let tint = tile.on ? Palette.cyan : Palette.text2
        let dot = tile.live ? Palette.cyan : (tile.on ? Palette.cyan.opacity(0.35) : Palette.hairline)
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Image(systemName: Symbols.sensor(tile.kind.name)).font(.system(size: 18)).foregroundStyle(tint)
                    Spacer()
                    Circle().fill(dot).frame(width: 8, height: 8)
                }
                Text(tile.kind.label)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(tile.supported ? Palette.text1 : Palette.text2)
                    .padding(.top, 14)
                Eyebrow(tile.caption).padding(.top, 2)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tile.on ? Palette.cyan.opacity(0.07) : Palette.panel)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(tile.on ? Palette.cyan.opacity(0.5) : Palette.hairline, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!tile.supported)
        .accessibilityLabel("\(tile.kind.label), \(tile.caption)")
        .accessibilityHint(tile.on ? "Turns this sensor off" : "Turns this sensor on")
    }
}

private struct StatItem {
    let symbol: String
    let title: String
    let value: String
    let unit: String
    let hint: String
}

private struct TodayStats: View {
    let day: DailySummary?

    private static let grouping: NumberFormatter = {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        return f
    }()

    private var items: [StatItem] {
        let d = day
        return [
            StatItem(symbol: "figure.walk", title: "Steps", value: d.map { Self.grouping.string(from: NSNumber(value: $0.steps)) ?? "\($0.steps)" } ?? "--", unit: "", hint: "Movement today"),
            StatItem(symbol: "ruler", title: "Distance", value: d.map { String(format: "%.1f", $0.distanceMeters / 1000) } ?? "--", unit: "km", hint: "Walked and run"),
            StatItem(symbol: "heart.fill", title: "Resting pulse", value: d?.restingHr.map { "\($0.int64Value)" } ?? "--", unit: "bpm", hint: "Lower means better recovered"),
            StatItem(symbol: "waveform.path.ecg", title: "Peak heart rate", value: d?.maxHr.map { "\($0.int64Value)" } ?? "--", unit: "bpm", hint: "Highest reading today"),
            StatItem(symbol: "flame.fill", title: "Energy burned", value: d.map { "\(Int($0.calories.rounded()))" } ?? "--", unit: "kcal", hint: "Calories from activity"),
            StatItem(symbol: "figure.stairs", title: "Floors climbed", value: d.map { "\($0.floors)" } ?? "--", unit: "", hint: "Flights of stairs"),
        ]
    }

    var body: some View {
        HSection("Today") {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(items, id: \.title) { StatTile(item: $0) }
            }
        }
    }
}

/// A stat that explains itself: what it is, the number with its unit, and what it means.
private struct StatTile: View {
    let item: StatItem

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: item.symbol).font(.system(size: 14)).foregroundStyle(Palette.cyan)
                Text(item.title).font(.system(size: 12, weight: .medium)).foregroundStyle(Palette.text2).lineLimit(1)
            }
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(item.value).font(.system(size: 22, design: .monospaced)).foregroundStyle(Palette.text1).lineLimit(1).minimumScaleFactor(0.7)
                if !item.unit.isEmpty {
                    Text(item.unit).font(.system(size: 12)).foregroundStyle(Palette.text2)
                }
            }
            .padding(.top, 10)
            Text(item.hint)
                .font(.caption)
                .foregroundStyle(Palette.text2)
                .lineLimit(2, reservesSpace: true)
                .padding(.top, 4)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.hairline, lineWidth: 1))
    }
}

private struct PersonalBests: View {
    let records: [RecordLine]

    var body: some View {
        HSection("Personal bests") {
            if records.isEmpty { Muted("None yet") }
            VStack(spacing: 0) {
                ForEach(Array(records.enumerated()), id: \.offset) { i, r in
                    HStack {
                        Text(r.label).font(.subheadline).foregroundStyle(Palette.text1)
                        Spacer()
                        Text(r.value).font(.system(size: 14, design: .monospaced)).foregroundStyle(Palette.cyan)
                    }
                    .padding(.vertical, 10)
                    if i < records.count - 1 { HairlineRule() }
                }
            }
        }
    }
}

private struct ServerSection: View {
    let pending: Int64
    let message: String?
    let onUpload: () -> Void

    var body: some View {
        HSection("Server") {
            HStack(spacing: 10) {
                Text("\(pending)").font(.system(size: 22, design: .monospaced)).foregroundStyle(Palette.text1)
                Eyebrow("readings queued").frame(maxWidth: .infinity, alignment: .leading)
                OutlineButton(title: "Upload", action: onUpload)
            }
            if let message { Muted(message).padding(.top, 6) }
        }
    }
}

// MARK: - Guide

/// On-demand guide: the score scale, what feeds it, and its limits.
struct GuideView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Eyebrow("Guide")
                Text("Reading the dial").font(.system(size: 24, weight: .light)).foregroundStyle(Palette.text1).padding(.top, 6)
                Muted("How ready your brain is for focus and judgement today, against your own normal.")
                ForEach(Catalog.shared.bandRows, id: \.range) { row in
                    let color = Palette.band(row.band)
                    HStack(spacing: 12) {
                        Text(row.range).font(.system(size: 13, design: .monospaced)).foregroundStyle(color).frame(width: 72, alignment: .leading)
                        Image(systemName: Symbols.band(row.band)).font(.system(size: 16)).foregroundStyle(color).frame(width: 20)
                        VStack(alignment: .leading, spacing: 2) {
                            Eyebrow(row.band.label, color: color)
                            Text(row.band.headline).font(.subheadline).foregroundStyle(Palette.text1)
                        }
                    }
                    .padding(.vertical, 8)
                }
                .padding(.top, 10)

                Eyebrow("Ingredients", color: Palette.text1).padding(.top, 18)
                ForEach(Catalog.shared.components, id: \.name) { c in
                    VStack(spacing: 6) {
                        HStack(spacing: 10) {
                            Image(systemName: Symbols.component(c)).font(.system(size: 14)).foregroundStyle(Palette.cyan).frame(width: 20)
                            Text(c.label).font(.subheadline).foregroundStyle(Palette.text1).frame(maxWidth: .infinity, alignment: .leading)
                            Text("\(Int((c.weight * 100).rounded()))%").font(.system(size: 14, design: .monospaced)).foregroundStyle(Palette.text1)
                        }
                        ScoreBar(score: Int(c.weight * 250), color: Palette.cyan, height: 2)
                    }
                    .padding(.vertical, 6)
                }

                Eyebrow("Daily rhythm", color: Palette.text1).padding(.top, 18)
                ForEach(Catalog.shared.rhythm, id: \.name) { k in
                    HStack(spacing: 10) {
                        Rectangle().fill(Palette.window(k)).frame(width: 14, height: 6)
                        Text(k.label).font(.subheadline).foregroundStyle(Palette.text1).frame(maxWidth: .infinity, alignment: .leading)
                        Text(k.adjustment > 0 ? "+\(k.adjustment)" : "\(k.adjustment)").font(.system(size: 14, design: .monospaced)).foregroundStyle(Palette.text2)
                    }
                    .padding(.vertical, 4)
                }
                .padding(.top, 8)

                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "info.circle").font(.system(size: 13)).foregroundStyle(Palette.text2)
                    Muted("A heuristic guide, not a medical device. The first 7 days are calibration.")
                }
                .padding(.top, 18)
            }
            .padding(.horizontal, 24)
            .padding(.top, 28)
            .padding(.bottom, 32)
        }
        .background(Palette.panel.ignoresSafeArea())
    }
}
