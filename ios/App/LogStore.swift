// DPGrabber iOS app - In-memory run log.
//
// The log lives only in memory (max 500 entries, oldest dropped) and is
// never written to disk. Engine callbacks arrive on the engine's task,
// so appending hops to the main actor.

import Foundation

// NOTE: no `import DPGrabberCore` here. The Xcode project compiles the
// core sources and the app sources into one app target, so the core
// types (LogLevel etc.) are in the same module. The SwiftPM package is
// only used to test the core on Linux.

struct LogEntry: Identifiable {
    let id = UUID()
    let time: Date
    let level: LogLevel
    let message: String
}

@MainActor
final class LogStore: ObservableObject {
    @Published private(set) var entries: [LogEntry] = []
    private let maxEntries = 500

    func append(_ level: LogLevel, _ message: String) {
        entries.append(LogEntry(time: Date(), level: level, message: message))
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
    }

    func clear() {
        entries.removeAll()
    }

    var count: Int { entries.count }
}
