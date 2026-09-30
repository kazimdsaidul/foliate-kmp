package io.github.asadullah012.foliate.internal

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.asadullah012.foliate.model.EpubTtsSegment

/**
 * Wraps [android.speech.tts.TextToSpeech]. Each segment is queued as its own utterance,
 * with the segment's index (within the current queue) as the utterance id, so
 * [UtteranceProgressListener.onStart] tells this class exactly which segment started.
 *
 * `TextToSpeech` has no native pause/resume: [pause] stops audio and remembers the
 * segment last started; [resume] re-queues from that segment.
 */
internal actual class EpubTtsEngine actual constructor() {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingActions = ArrayDeque<() -> Unit>()

    private var activeSegments: List<EpubTtsSegment> = emptyList()
    private var activeRate: Float = 1.0f
    private var activeOnSegmentStarted: ((String) -> Unit)? = null
    private var activeOnFinished: (() -> Unit)? = null
    private var lastStartedIndex: Int = -1

    /**
     * Connects this engine to a real `TextToSpeech` instance. Android-only; call once,
     * right after construction, from the platform view that owns this engine.
     */
    fun attach(context: Context) {
        if (tts != null) return
        val engine = TextToSpeech(context.applicationContext) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                val queued = pendingActions.toList()
                pendingActions.clear()
                queued.forEach { it() }
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val index = utteranceId?.toIntOrNull() ?: return
                lastStartedIndex = index
                activeSegments.getOrNull(index)?.let { activeOnSegmentStarted?.invoke(it.mark) }
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.toIntOrNull() == activeSegments.lastIndex) activeOnFinished?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.toIntOrNull() == activeSegments.lastIndex) activeOnFinished?.invoke()
            }
        })
        tts = engine
    }

    actual fun speak(
        segments: List<EpubTtsSegment>,
        rate: Float,
        onSegmentStarted: (mark: String) -> Unit,
        onFinished: () -> Unit
    ) {
        val action = {
            val engine = tts
            if (engine != null && segments.isNotEmpty()) {
                activeSegments = segments
                activeRate = rate
                activeOnSegmentStarted = onSegmentStarted
                activeOnFinished = onFinished
                lastStartedIndex = -1
                engine.setSpeechRate(rate)
                engine.stop()
                segments.forEachIndexed { index, segment ->
                    engine.speak(segment.text, TextToSpeech.QUEUE_ADD, null, index.toString())
                }
            }
        }
        if (isInitialized) action() else pendingActions.addLast(action)
    }

    actual fun pause() {
        tts?.stop()
    }

    actual fun resume() {
        val onSegmentStarted = activeOnSegmentStarted
        val onFinished = activeOnFinished
        val remaining = activeSegments.drop(lastStartedIndex.coerceAtLeast(0))
        if (remaining.isNotEmpty() && onSegmentStarted != null && onFinished != null) {
            speak(remaining, activeRate, onSegmentStarted, onFinished)
        }
    }

    actual fun stop() {
        tts?.stop()
        activeSegments = emptyList()
        lastStartedIndex = -1
    }

    actual fun release() {
        tts?.shutdown()
        tts = null
        pendingActions.clear()
        activeSegments = emptyList()
    }
}
