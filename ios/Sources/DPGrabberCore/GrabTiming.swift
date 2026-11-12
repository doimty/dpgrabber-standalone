// DPGrabber iOS - Precise grab time (PROTOCOL.md §8.1).
//
// grab_time = reTime + confirmCountDown; wait = grab_time - now.
// `createTime` is only informational and is not used in the math.

import Foundation

public enum GrabWaitReason: Equatable {
    /// Fields present; wait is the time to wait.
    case ok
    /// reTime / confirmCountDown missing; grab immediately.
    case missingFields
    /// Grab time already passed; grab immediately.
    case alreadyOpen
}

public struct GrabWait: Equatable {
    public let waitSeconds: Double
    public let grabEpochMs: Int?
    public let reason: GrabWaitReason

    public init(waitSeconds: Double, grabEpochMs: Int?, reason: GrabWaitReason) {
        self.waitSeconds = waitSeconds
        self.grabEpochMs = grabEpochMs
        self.reason = reason
    }
}

/// Compute the precise wait for one order that passed the filter.
/// `nowEpochSec` is the local wall clock in seconds.
public func computeGrabWait(order: [String: Any], nowEpochSec: Double) -> GrabWait {
    let reMs = normalizeMs(order["reTime"])
    guard reMs > 0 else {
        return GrabWait(waitSeconds: 0, grabEpochMs: nil, reason: .missingFields)
    }
    let countdown: Double?
    if let n = order["confirmCountDown"] as? NSNumber {
        countdown = n.doubleValue
    } else if let s = order["confirmCountDown"] as? String {
        countdown = Double(s)
    } else {
        countdown = nil
    }
    guard let countdown else {
        return GrabWait(waitSeconds: 0, grabEpochMs: nil, reason: .missingFields)
    }
    let grabMs = reMs + Int(countdown * 1000)
    let wait = Double(grabMs) / 1000.0 - nowEpochSec
    if wait <= 0 {
        return GrabWait(waitSeconds: 0, grabEpochMs: grabMs, reason: .alreadyOpen)
    }
    return GrabWait(waitSeconds: wait, grabEpochMs: grabMs, reason: .ok)
}
