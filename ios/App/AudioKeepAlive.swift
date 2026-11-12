// DPGrabber iOS app - Silent-audio keep-alive.
//
// iOS only lets an app keep running in the background for the reasons
// it declares in Info.plist. This app declares UIBackgroundModes=audio
// and plays a loop of digital silence, the same approach as DPGrabber
// Keeper's silent audio. That keeps the process alive while the app is
// backgrounded so polling can continue.
//
// HONEST LIMITS (also stated in README.md):
// - iOS may still suspend or terminate the app (memory pressure, a
//   system update, the user swiping it away, or the audio session being
//   taken over). This is NOT equivalent to Android's foreground Service.
// - The audio is real silence (zero PCM samples) at a low sample rate;
//   it makes no sound and mixes with the user's music instead of
//   stopping it.
// - If the audio session is interrupted (e.g. a phone call), the loop
//   restarts automatically when the interruption ends.

import AVFoundation
import Foundation

final class AudioKeepAlive {
    enum State: String {
        case stopped = "已停止"
        case running = "运行中"
        case failed = "启动失败"
    }

    private(set) var state: State = .stopped
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    var onStateChange: ((State) -> Void)?

    private func setState(_ newState: State) {
        state = newState
        onStateChange?(newState)
    }

    /// Start the silent loop. Safe to call repeatedly (no-op if running).
    func start() {
        guard engine == nil else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            engine.attach(player)
            let format = AVAudioFormat(standardFormatWithSampleRate: 8000, channels: 1)!
            engine.connect(player, to: engine.mainMixerNode, format: format)
            // One second of zero samples, looped forever.
            let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8000)!
            buffer.frameLength = 8000
            player.scheduleBuffer(buffer, at: nil, options: .loops)

            try engine.start()
            player.play()
            self.engine = engine
            self.player = player
            registerForInterruptions()
            setState(.running)
        } catch {
            engine = nil
            player = nil
            setState(.failed)
        }
    }

    /// Stop the loop and deactivate the audio session.
    func stop() {
        player?.stop()
        engine?.stop()
        engine = nil
        player = nil
        unregisterForInterruptions()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        setState(.stopped)
    }

    // MARK: - Interruption handling

    private func registerForInterruptions() {
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(handleInterruption(_:)),
                           name: AVAudioSession.interruptionNotification, object: nil)
        center.addObserver(self, selector: #selector(handleRouteChange(_:)),
                           name: AVAudioSession.routeChangeNotification, object: nil)
    }

    private func unregisterForInterruptions() {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let info = notification.userInfo,
              let rawType = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: rawType) else { return }
        if type == .ended {
            // The call ended: resume the silent loop.
            player?.play()
            try? engine?.start()
            setState(engine == nil ? .failed : .running)
        }
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        guard let info = notification.userInfo,
              let rawReason = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: rawReason) else { return }
        // Only a media-services reset is fatal; other route changes
        // (headphones, Bluetooth) just need the player resumed.
        if reason == .categoryChange || reason == .oldDeviceUnavailable {
            player?.play()
            try? engine?.start()
        }
    }
}
