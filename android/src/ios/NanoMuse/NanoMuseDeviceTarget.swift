//
//  NanoMuseDeviceTarget.swift
//  nanoMuse
//
//  A chat connected to one device of the account (#907): after "@mac …" the
//  chat stays with the Mac, and later messages go there without the mention
//  until the chip above the composer switches back to this phone. Kept per
//  chat, on this phone, by device id; not synced. While connected, every task
//  names this chat as its `conversation`, so the device keeps one conversation
//  per chat (docs/hub.md, Tasks). Android: io.github.nanomuse.sync.DeviceTarget.
//

import SwiftUI

@MainActor
final class NanoMuseDeviceTargets: ObservableObject {
    static let shared = NanoMuseDeviceTargets()

    struct Target: Codable, Equatable {
        let id: String
        /// The name when it was connected, for the chip while the hub's list is not in yet.
        let name: String
        /// What every task of this chat names as its `conversation` on that device: made once per
        /// connection, kept when a draft becomes a saved chat, so the device's context carries on.
        let conversation: String
    }

    private static let key = "nanomuse.deviceTargets"
    @Published private(set) var targets: [String: Target] = [:]

    private init() {
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode([String: Target].self, from: data) {
            targets = saved
        }
    }

    func target(for session: String) -> Target? { targets[session] }

    func connect(_ session: String, to device: HubDevice) {
        if let current = targets[session], current.id == device.id {
            guard current.name != device.name else { return }
            targets[session] = Target(id: device.id, name: device.name, conversation: current.conversation)
        } else {
            targets[session] = Target(id: device.id, name: device.name, conversation: "chat-\(UUID().uuidString.lowercased())")
        }
        save()
    }

    func disconnect(_ session: String) {
        guard targets.removeValue(forKey: session) != nil else { return }
        save()
    }

    /// A draft became a session: its connection follows it.
    func rebind(from draft: String, to real: String) {
        guard let target = targets.removeValue(forKey: draft) else { return }
        targets[real] = target
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(targets) { UserDefaults.standard.set(data, forKey: Self.key) }
    }
}

/// Above the composer while the chat is connected: "→ mac", a menu to switch, × to come back here.
struct NanoMuseDeviceTargetChip: View {
    @ObservedObject var vm: AIChatViewModel
    @ObservedObject private var targets = NanoMuseDeviceTargets.shared
    @ObservedObject private var hub = NanoMuseHub.shared

    private var key: String { vm.nmSessionKey }

    var body: some View {
        if let target = targets.target(for: key) {
            let device = hub.others.first { $0.id == target.id }
            let name = device?.name ?? target.name
            HStack(spacing: 6) {
                Menu {
                    ForEach(hub.others.filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }) { d in
                        Button {
                            targets.connect(key, to: d)
                        } label: {
                            Label(d.name, systemImage: d.isPhone ? "iphone" : "desktopcomputer")
                        }
                    }
                    Divider()
                    Button {
                        targets.disconnect(key)
                    } label: {
                        Label(AppLocalized("This phone"), systemImage: "iphone")
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.right").font(.system(size: 11, weight: .semibold))
                        Image(systemName: device?.isPhone == true ? "iphone" : "desktopcomputer").font(.system(size: 12))
                        Text(String(format: AppLocalized("To %@"), name)).font(.caption.weight(.medium)).lineLimit(1)
                        Circle().fill(device?.online == true ? Color.green : Color.gray.opacity(0.5)).frame(width: 6, height: 6)
                    }
                    .foregroundStyle(.primary)
                }
                .accessibilityLabel(Text(String(format: AppLocalized("Messages in this chat go to %@. Change"), name)))
                Spacer(minLength: 4)
                Button {
                    targets.disconnect(key)
                } label: {
                    Image(systemName: "xmark").font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary).padding(3)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(AppLocalized("Send to this phone again")))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(NanoMuseTones.fill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }
}
