// DPGrabber iOS app - Voice announcements (AVSpeechSynthesizer).

import AVFoundation
import Foundation

final class VoiceAnnouncer {
    private let synthesizer = AVSpeechSynthesizer()
    var enabled: Bool = true

    func speak(_ text: String) {
        guard enabled, !text.isEmpty else { return }
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-CN")
        utterance.rate = 0.52
        synthesizer.speak(utterance)
    }

    func stop() {
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
    }
}
