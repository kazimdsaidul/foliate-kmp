package io.github.asadullah012.foliate.internal

import io.github.asadullah012.foliate.model.EpubTtsSegment

/**
 * Platform text-to-speech engine that speaks a queue of [EpubTtsSegment]s as separate
 * native utterances, reporting each one's start so the caller can drive highlighting.
 *
 * [io.github.asadullah012.foliate.EpubReaderController] drives this directly -- it is
 * never reachable from the engine page, and the text it speaks is already public
 * segment text the controller received from that page.
 */
internal expect class EpubTtsEngine() {

    /**
     * Speaks [segments] in order, one native utterance per segment, replacing any
     * queue already speaking.
     *
     * @param onSegmentStarted Called with a segment's [EpubTtsSegment.mark] right as
     *   that segment starts speaking.
     * @param onFinished Called once the last segment in [segments] finishes speaking.
     */
    fun speak(
        segments: List<EpubTtsSegment>,
        rate: Float,
        onSegmentStarted: (mark: String) -> Unit,
        onFinished: () -> Unit
    )

    /** Pauses playback. The engine remembers where it stopped for [resume]. */
    fun pause()

    /** Resumes playback after [pause]. */
    fun resume()

    /** Stops playback and clears the queue. */
    fun stop()

    /** Releases the underlying engine. Call once, when the host view is torn down. */
    fun release()
}
