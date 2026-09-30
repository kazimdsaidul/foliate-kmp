package io.github.asadullah012.foliate

import androidx.compose.runtime.Stable
import io.github.asadullah012.foliate.internal.EpubTtsEngine
import io.github.asadullah012.foliate.model.EpubAnnotation
import io.github.asadullah012.foliate.model.EpubFootnote
import io.github.asadullah012.foliate.model.EpubReaderConfig
import io.github.asadullah012.foliate.model.EpubReaderFlow
import io.github.asadullah012.foliate.model.EpubReaderLocation
import io.github.asadullah012.foliate.model.EpubReaderTheme
import io.github.asadullah012.foliate.model.EpubSearchResult
import io.github.asadullah012.foliate.model.EpubTextSelection
import io.github.asadullah012.foliate.model.EpubTocItem
import io.github.asadullah012.foliate.model.EpubTtsPlaybackState
import io.github.asadullah012.foliate.model.EpubTtsSegment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Controller providing programmatic control and state observation for an embedded EPUB reader.
 */
@Stable
public class EpubReaderController {

    private val json = Json { encodeDefaults = true }

    /**
     * Commands that arrive before the reader is ready wait in this queue.
     * The queue drops the oldest command when it reaches [MAX_PENDING_COMMANDS].
     * A reader that never becomes ready therefore uses a fixed amount of memory.
     */
    private val pendingCommands = ArrayDeque<String>()

    private val _location = MutableStateFlow(EpubReaderLocation())
    public val location: StateFlow<EpubReaderLocation> = _location.asStateFlow()

    private val _toc = MutableStateFlow<List<EpubTocItem>>(emptyList())
    public val toc: StateFlow<List<EpubTocItem>> = _toc.asStateFlow()

    private val _searchResults = MutableStateFlow<List<EpubSearchResult>>(emptyList())
    public val searchResults: StateFlow<List<EpubSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    public val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    public val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    public val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _toggleControlsEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    public val toggleControlsEvents: SharedFlow<Unit> = _toggleControlsEvents.asSharedFlow()

    private val _brightnessEvents = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    public val brightnessEvents: SharedFlow<Float> = _brightnessEvents.asSharedFlow()

    private val _endBrightnessEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    public val endBrightnessEvents: SharedFlow<Unit> = _endBrightnessEvents.asSharedFlow()

    private val _textSelection = MutableStateFlow<EpubTextSelection?>(null)
    public val textSelection: StateFlow<EpubTextSelection?> = _textSelection.asStateFlow()

    private val _footnoteEvents = MutableSharedFlow<EpubFootnote>(extraBufferCapacity = 1)
    public val footnoteEvents: SharedFlow<EpubFootnote> = _footnoteEvents.asSharedFlow()

    private val _annotationClickEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    public val annotationClickEvents: SharedFlow<String> = _annotationClickEvents.asSharedFlow()

    private val _ttsPlaybackState = MutableStateFlow(EpubTtsPlaybackState.IDLE)
    public val ttsPlaybackState: StateFlow<EpubTtsPlaybackState> = _ttsPlaybackState.asStateFlow()

    private val _ttsSegments = MutableStateFlow<List<EpubTtsSegment>>(emptyList())
    public val ttsSegments: StateFlow<List<EpubTtsSegment>> = _ttsSegments.asStateFlow()

    private val _ttsActiveSegmentIndex = MutableStateFlow(-1)
    public val ttsActiveSegmentIndex: StateFlow<Int> = _ttsActiveSegmentIndex.asStateFlow()

    private val _ttsRate = MutableStateFlow(1.0f)
    public val ttsRate: StateFlow<Float> = _ttsRate.asStateFlow()

    private val _ttsHighlightEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    public val ttsHighlightEvents: SharedFlow<String> = _ttsHighlightEvents.asSharedFlow()

    /**
     * The platform text-to-speech engine that turns queued [EpubTtsSegment]s into audio.
     * A platform view sets this alongside [jsEvaluator] while it is attached, and clears
     * it on teardown. The controller drives it directly; it never queues TTS commands
     * the way it queues JS commands, because a TTS session only exists while a reader
     * is already attached and ready.
     */
    internal var ttsEngine: EpubTtsEngine? = null

    internal var jsEvaluator: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (value != null && _isReady.value) {
                drainPendingCommands()
            }
        }

    /**
     * Advances to the next page or section.
     */
    public fun nextPage() {
        evaluateJs("window.readerController && window.readerController.nextPage()")
    }

    /**
     * Returns to the preceding page or section.
     */
    public fun prevPage() {
        evaluateJs("window.readerController && window.readerController.prevPage()")
    }

    /**
     * Navigates to a specific Canonical Fragment Identifier (CFI).
     */
    public fun goToCfi(cfi: String) {
        val safeCfi = json.encodeToString(cfi)
        evaluateJs("window.readerController && window.readerController.goToCfi($safeCfi)")
    }

    /**
     * Navigates to a specific Table of Contents HREF target.
     */
    public fun goToHref(href: String) {
        val safeHref = json.encodeToString(href)
        evaluateJs("window.readerController && window.readerController.goToHref($safeHref)")
    }

    /**
     * Navigates to a proportional completion fraction (0.0 to 1.0).
     */
    public fun goToFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0.0f, 1.0f)
        evaluateJs("window.readerController && window.readerController.goToFraction($clamped)")
    }

    /**
     * Updates the reader theme.
     */
    public fun setTheme(theme: EpubReaderTheme) {
        val safeBg = json.encodeToString(theme.backgroundColor)
        val safeFg = json.encodeToString(theme.textColor)
        evaluateJs("window.readerController && window.readerController.setTheme($safeBg, $safeFg)")
    }

    /**
     * Updates the typography font size in pixels.
     */
    public fun setFontSize(sizePx: Int) {
        val clamped = sizePx.coerceIn(10, 48)
        evaluateJs("window.readerController && window.readerController.setFontSize($clamped)")
    }

    /**
     * Updates the font family stack.
     */
    public fun setFontFamily(fontFamily: String) {
        val safeFont = json.encodeToString(fontFamily)
        evaluateJs("window.readerController && window.readerController.setFontFamily($safeFont)")
    }

    /**
     * Updates the reading flow mode (paginated vs continuous scroll).
     */
    public fun setLayout(flow: EpubReaderFlow) {
        val flowStr = when (flow) {
            EpubReaderFlow.PAGINATED -> "paginated"
            EpubReaderFlow.SCROLLED -> "scrolled"
        }
        val safeFlow = json.encodeToString(flowStr)
        evaluateJs("window.readerController && window.readerController.setLayout($safeFlow)")
    }

    /**
     * Updates line height multiplier.
     */
    public fun setLineHeight(lineHeight: Float) {
        val clamped = lineHeight.coerceIn(1.0f, 3.0f)
        evaluateJs("window.readerController && window.readerController.setLineHeight($clamped)")
    }

    /**
     * Updates reader page margin in pixels.
     */
    public fun setMargin(marginPx: Int) {
        val clamped = marginPx.coerceIn(0, 120)
        evaluateJs("window.readerController && window.readerController.setMargin($clamped)")
    }

    /**
     * Updates text alignment ("justify" or "left").
     */
    public fun setTextAlign(textAlign: String) {
        val safeAlign = json.encodeToString(textAlign)
        evaluateJs("window.readerController && window.readerController.setTextAlign($safeAlign)")
    }

    /**
     * Initiates in-book text search.
     */
    public fun search(query: String) {
        if (query.isBlank()) {
            clearSearch()
            return
        }
        _isSearching.value = true
        val safeQuery = json.encodeToString(query)
        evaluateJs("window.readerController && window.readerController.search($safeQuery)")
    }

    /**
     * Clears search highlights and results.
     */
    public fun clearSearch() {
        _isSearching.value = false
        _searchResults.value = emptyList()
        evaluateJs("window.readerController && window.readerController.clearSearch()")
    }

    /**
     * Applies full configuration settings to reader.
     */
    public fun applyConfig(config: EpubReaderConfig) {
        setTheme(config.theme)
        setFontSize(config.fontSize)
        setFontFamily(config.fontFamily)
        setLayout(config.flow)
        setLineHeight(config.lineHeight)
        setMargin(config.margin)
        setTextAlign(config.textAlign)
    }

    internal fun onSearchResults(results: List<EpubSearchResult>) {
        _searchResults.value = results
        _isSearching.value = false
    }

    internal fun onRelocate(location: EpubReaderLocation) {
        _location.value = location
    }

    internal fun onTocLoaded(items: List<EpubTocItem>) {
        _toc.value = items
    }

    internal fun onReady() {
        _isReady.value = true
        _errorMessage.value = null
        drainPendingCommands()
    }

    /**
     * Marks the reader as not ready, because its web view went away.
     *
     * A new web view starts with an empty page. The reader becomes ready again only
     * after that page reports it. Until then every command waits in the queue.
     */
    internal fun onDetached() {
        _isReady.value = false
        _ttsPlaybackState.value = EpubTtsPlaybackState.IDLE
        _ttsSegments.value = emptyList()
        _ttsActiveSegmentIndex.value = -1
    }

    internal fun onError(message: String) {
        _errorMessage.value = message
    }

    internal fun onToggleControls() {
        _toggleControlsEvents.tryEmit(Unit)
    }

    internal fun onAdjustBrightness(delta: Float) {
        _brightnessEvents.tryEmit(delta)
    }

    internal fun onEndBrightness() {
        _endBrightnessEvents.tryEmit(Unit)
    }

    /**
     * Adds a persistent visual highlight annotation on the given CFI range.
     */
    public fun addAnnotation(annotation: EpubAnnotation) {
        val safeCfi = json.encodeToString(annotation.cfi)
        val safeColor = json.encodeToString(annotation.color)
        val safeNote = json.encodeToString(annotation.note ?: "")
        evaluateJs("window.readerController && window.readerController.addAnnotation($safeCfi, $safeColor, $safeNote)")
    }

    /**
     * Removes an annotation at the specified CFI.
     */
    public fun deleteAnnotation(cfi: String) {
        val safeCfi = json.encodeToString(cfi)
        evaluateJs("window.readerController && window.readerController.deleteAnnotation($safeCfi)")
    }

    /**
     * Clears any active text selection in the reader view.
     */
    public fun clearSelection() {
        _textSelection.value = null
        evaluateJs("window.readerController && window.readerController.clearSelection()")
    }

    internal fun onSelection(selection: EpubTextSelection?) {
        _textSelection.value = selection
    }

    internal fun onFootnote(footnote: EpubFootnote) {
        _footnoteEvents.tryEmit(footnote)
    }

    internal fun onAnnotationClick(cfi: String) {
        _annotationClickEvents.tryEmit(cfi)
    }

    /**
     * Starts reading the current section aloud from its beginning.
     *
     * Restarting from the exact reading position (rather than the section start) is not
     * supported yet -- it needs the current viewport's location as a DOM range, which
     * the engine does not expose to this command today.
     */
    public fun startTts() {
        _ttsPlaybackState.value = EpubTtsPlaybackState.PLAYING
        evaluateJs("window.readerController && window.readerController.startTts()")
    }

    /**
     * Pauses text-to-speech playback. The current segment queue is kept, so [resumeTts]
     * continues from where playback left off.
     */
    public fun pauseTts() {
        ttsEngine?.pause()
        _ttsPlaybackState.value = EpubTtsPlaybackState.PAUSED
    }

    /**
     * Resumes text-to-speech playback after [pauseTts].
     */
    public fun resumeTts() {
        ttsEngine?.resume()
        _ttsPlaybackState.value = EpubTtsPlaybackState.PLAYING
    }

    /**
     * Stops text-to-speech playback and clears the segment queue.
     */
    public fun stopTts() {
        ttsEngine?.stop()
        evaluateJs("window.readerController && window.readerController.stopTts()")
        _ttsPlaybackState.value = EpubTtsPlaybackState.IDLE
        _ttsSegments.value = emptyList()
        _ttsActiveSegmentIndex.value = -1
    }

    /**
     * Updates the text-to-speech playback rate.
     *
     * This never reaches the engine page -- the platform speech engine reads it
     * directly the next time it speaks a segment.
     */
    public fun setTtsRate(rate: Float) {
        _ttsRate.value = rate.coerceIn(0.5f, 2.0f)
    }

    private fun requestNextTtsBlock() {
        evaluateJs("window.readerController && window.readerController.nextTtsBlock()")
    }

    /**
     * Reports that the platform speech engine has started speaking the segment marked
     * [mark], so the engine page can highlight/scroll to the matching text.
     */
    internal fun reportTtsMarkReached(mark: String) {
        val index = _ttsSegments.value.indexOfFirst { it.mark == mark }
        if (index >= 0) _ttsActiveSegmentIndex.value = index
        val safeMark = json.encodeToString(mark)
        evaluateJs("window.readerController && window.readerController.reportTtsMarkReached($safeMark)")
    }

    /**
     * Receives one block of text-to-speech segments and hands it to the platform speech
     * engine. An empty block (the engine found nothing to speak) ends the session the
     * same way [onTtsSectionEnd] does.
     */
    internal fun onTtsBlock(segments: List<EpubTtsSegment>) {
        _ttsSegments.value = segments
        _ttsActiveSegmentIndex.value = -1
        if (segments.isEmpty()) {
            onTtsSectionEnd()
            return
        }
        ttsEngine?.speak(
            segments = segments,
            rate = _ttsRate.value,
            onSegmentStarted = { mark -> reportTtsMarkReached(mark) },
            onFinished = { requestNextTtsBlock() }
        )
    }

    internal fun onTtsHighlight(cfi: String) {
        _ttsHighlightEvents.tryEmit(cfi)
    }

    /** The current section has no more text to speak. */
    internal fun onTtsSectionEnd() {
        stopTts()
    }

    private fun evaluateJs(script: String) {
        val evaluator = jsEvaluator
        if (evaluator != null && _isReady.value) {
            evaluator(script)
        } else {
            while (pendingCommands.size >= MAX_PENDING_COMMANDS) {
                pendingCommands.removeFirst()
            }
            pendingCommands.addLast(script)
        }
    }

    private fun drainPendingCommands() {
        val evaluator = jsEvaluator ?: return
        while (pendingCommands.isNotEmpty()) {
            val cmd = pendingCommands.removeFirst()
            evaluator(cmd)
        }
    }

    private companion object {
        /** The largest number of commands that wait for the reader to become ready. */
        const val MAX_PENDING_COMMANDS = 64
    }
}
