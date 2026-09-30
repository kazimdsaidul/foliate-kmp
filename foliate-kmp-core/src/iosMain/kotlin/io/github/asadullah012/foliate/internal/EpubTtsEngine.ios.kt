package io.github.asadullah012.foliate.internal

import io.github.asadullah012.foliate.model.EpubTtsSegment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.darwin.NSObject

/**
 * Wraps [AVSpeechSynthesizer]. Each segment becomes its own [AVSpeechUtterance];
 * [AVSpeechSynthesizerDelegateProtocol] callbacks map an utterance instance back to
 * its segment's mark through an identity-keyed map, since an utterance carries no
 * free-form user-data slot of its own.
 *
 * Unlike Android's `TextToSpeech`, `AVSpeechSynthesizer` has real pause/resume, so
 * [pause]/[resume] use it directly instead of re-queuing.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class EpubTtsEngine actual constructor() {
    private val synthesizer = AVSpeechSynthesizer()
    private val marksByUtterance = mutableMapOf<AVSpeechUtterance, String>()
    private var lastUtterance: AVSpeechUtterance? = null
    private var onSegmentStartedCallback: ((String) -> Unit)? = null
    private var onFinishedCallback: (() -> Unit)? = null

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didStartSpeechUtterance: AVSpeechUtterance
        ) {
            marksByUtterance[didStartSpeechUtterance]?.let { onSegmentStartedCallback?.invoke(it) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance
        ) {
            if (didFinishSpeechUtterance == lastUtterance) onFinishedCallback?.invoke()
        }
    }

    init {
        synthesizer.delegate = delegate
    }

    actual fun speak(
        segments: List<EpubTtsSegment>,
        rate: Float,
        onSegmentStarted: (mark: String) -> Unit,
        onFinished: () -> Unit
    ) {
        if (segments.isEmpty()) return
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        marksByUtterance.clear()
        onSegmentStartedCallback = onSegmentStarted
        onFinishedCallback = onFinished
        val rateValue = (AVSpeechUtteranceDefaultSpeechRate * rate)
            .coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
        var last: AVSpeechUtterance? = null
        for (segment in segments) {
            val utterance = AVSpeechUtterance(string = segment.text)
            utterance.rate = rateValue
            marksByUtterance[utterance] = segment.mark
            last = utterance
            synthesizer.speakUtterance(utterance)
        }
        lastUtterance = last
    }

    actual fun pause() {
        synthesizer.pauseSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryWord)
    }

    actual fun resume() {
        synthesizer.continueSpeaking()
    }

    actual fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        marksByUtterance.clear()
        lastUtterance = null
    }

    actual fun release() {
        stop()
        synthesizer.delegate = null
    }
}
