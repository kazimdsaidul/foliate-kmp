package io.github.asadullah012.foliate.compose.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.asadullah012.foliate.EpubReaderController
import io.github.asadullah012.foliate.ui.EpubReader
import io.github.asadullah012.foliate.model.EpubAnnotation
import io.github.asadullah012.foliate.model.EpubFontOption
import io.github.asadullah012.foliate.model.EpubReaderSheet
import io.github.asadullah012.foliate.model.EpubTtsPlaybackState
import io.github.asadullah012.foliate.compose.presentation.ReaderEffect
import io.github.asadullah012.foliate.compose.presentation.ReaderEvent
import io.github.asadullah012.foliate.compose.presentation.ReaderModel
import io.github.asadullah012.foliate.compose.presentation.ReaderState
import io.github.asadullah012.foliate.compose.ui.components.ReaderAnnotationDialog
import io.github.asadullah012.foliate.compose.ui.components.ReaderAppearanceSheet
import io.github.asadullah012.foliate.compose.ui.components.ReaderBottomBar
import io.github.asadullah012.foliate.compose.ui.components.ReaderBrightnessHud
import io.github.asadullah012.foliate.compose.ui.components.ReaderFootnoteSheet
import io.github.asadullah012.foliate.compose.ui.components.ReaderSearchDialog
import io.github.asadullah012.foliate.compose.ui.components.ReaderSelectionToolbar
import io.github.asadullah012.foliate.compose.ui.components.ReaderTocDrawer
import io.github.asadullah012.foliate.compose.ui.components.ReaderTopBar
import io.github.asadullah012.foliate.compose.ui.components.ReaderTtsBar
import kotlin.time.Clock

/**
 * Turnkey, predesigned EPUB reader screen.
 *
 * Embeds the complete reading experience: Foliate.js canvas, top navigation bar,
 * bottom progress bar, typography sheet, TOC drawer, in-book search, highlighting,
 * footnote sheet, annotation detail, and edge-swipe brightness HUD.
 */
@Composable
public fun ReaderScreen(
    filePath: String,
    bookTitle: String = "",
    bookId: String = filePath,
    initialCfi: String? = null,
    initialProgress: Float = 0f,
    readerModel: ReaderModel = viewModel { ReaderModel() },
    onNavigateBack: () -> Unit = {},
    onOpenExternal: ((filePath: String) -> Unit)? = null,
    onBrightnessChange: ((Float) -> Unit)? = null,
    onProgressChange: ((progress: Float, cfi: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    availableFonts: List<EpubFontOption> = EpubFontOption.DEFAULT_FONTS
) {
    val state by readerModel.state.collectAsStateWithLifecycle()
    val controller = remember { EpubReaderController() }

    BindControllerEvents(controller, readerModel)
    BindControllerState(controller, readerModel, onProgressChange)
    BindReaderEffects(readerModel, onNavigateBack, onOpenExternal, onBrightnessChange)

    LaunchedEffect(filePath, bookId) {
        readerModel.onEvent(
            ReaderEvent.Init(
                bookId = bookId,
                filePath = filePath,
                bookTitle = bookTitle,
                initialCfi = initialCfi,
                initialProgress = initialProgress
            )
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> ReaderLoadingView()
                state.errorMessage != null -> ReaderErrorView(
                    errorMessage = state.errorMessage ?: "Unknown error",
                    filePath = state.filePath,
                    onOpenExternal = onOpenExternal,
                    onBack = { readerModel.onEvent(ReaderEvent.OnNavigateBack) }
                )
                else -> ReaderMainLayout(
                    state = state,
                    controller = controller,
                    readerModel = readerModel,
                    availableFonts = availableFonts
                )
            }
        }
    }
}

@Composable
private fun BindControllerEvents(controller: EpubReaderController, model: ReaderModel) {
    LaunchedEffect(controller) {
        controller.toggleControlsEvents.collect { model.onEvent(ReaderEvent.ToggleControls) }
    }
    LaunchedEffect(controller) {
        controller.brightnessEvents.collect { model.onEvent(ReaderEvent.AdjustBrightness(it)) }
    }
    LaunchedEffect(controller) {
        controller.endBrightnessEvents.collect { model.onEvent(ReaderEvent.EndBrightness) }
    }
    LaunchedEffect(controller) {
        controller.footnoteEvents.collect { model.onEvent(ReaderEvent.ShowFootnote(it)) }
    }
    LaunchedEffect(controller) {
        controller.annotationClickEvents.collect { model.onEvent(ReaderEvent.OnAnnotationClicked(it)) }
    }
}

@Composable
private fun BindControllerState(
    controller: EpubReaderController,
    model: ReaderModel,
    onProgressChange: ((progress: Float, cfi: String) -> Unit)? = null
) {
    val location by controller.location.collectAsStateWithLifecycle()
    val tocItems by controller.toc.collectAsStateWithLifecycle()
    val searchResults by controller.searchResults.collectAsStateWithLifecycle()
    val textSelection by controller.textSelection.collectAsStateWithLifecycle()
    val isReady by controller.isReady.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(isReady) {
        if (isReady && state.annotations.isNotEmpty()) {
            state.annotations.forEach { controller.addAnnotation(it) }
        }
    }

    LaunchedEffect(location) {
        val cfi = location.cfi ?: ""
        model.onEvent(
            ReaderEvent.LocationUpdated(
                cfi = cfi,
                fraction = location.progressFraction,
                chapterTitle = location.chapterTitle,
                sectionIndex = location.sectionIndex,
                totalSections = location.totalSections
            )
        )
        if (location.progressFraction > 0f) {
            model.onEvent(ReaderEvent.UpdateProgress(location.progressFraction))
            onProgressChange?.invoke(location.progressFraction, cfi)
        }
    }

    LaunchedEffect(tocItems) {
        if (tocItems.isNotEmpty()) model.onEvent(ReaderEvent.TocLoaded(tocItems))
    }
    LaunchedEffect(searchResults) {
        model.onEvent(ReaderEvent.SearchResultsLoaded(searchResults))
    }
    LaunchedEffect(textSelection) {
        model.onEvent(ReaderEvent.TextSelectionChanged(textSelection))
    }
}

@Composable
private fun BindReaderEffects(
    model: ReaderModel,
    onNavigateBack: () -> Unit,
    onOpenExternal: ((String) -> Unit)?,
    onBrightnessChange: ((Float) -> Unit)?
) {
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            when (effect) {
                is ReaderEffect.OnNavigateBack -> onNavigateBack()
                is ReaderEffect.OpenExternalReader -> onOpenExternal?.invoke(effect.filePath)
                is ReaderEffect.ApplyWindowBrightness -> onBrightnessChange?.invoke(effect.brightness)
                is ReaderEffect.ShowMessage -> Unit
            }
        }
    }
}

@Composable
private fun ReaderLoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(text = "Loading book...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReaderErrorView(
    errorMessage: String,
    filePath: String,
    onOpenExternal: ((String) -> Unit)?,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
                if (onOpenExternal != null && filePath.isNotBlank()) {
                    Button(onClick = { onOpenExternal(filePath) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open in External App")
                    }
                }
                OutlinedButton(onClick = onBack) {
                    Text("Go Back")
                }
            }
        }
    }
}

@Composable
private fun ReaderMainLayout(
    state: ReaderState,
    controller: EpubReaderController,
    readerModel: ReaderModel,
    availableFonts: List<EpubFontOption>
) {
    Box(modifier = Modifier.fillMaxSize()) {
        EpubReader(
            bookPath = state.filePath,
            controller = controller,
            config = state.readerConfig,
            initialCfi = state.currentCfi,
            modifier = Modifier.fillMaxSize()
        )

        ReaderOverlays(state, controller, readerModel)
        ReaderModals(state, controller, readerModel, availableFonts)
        ReaderSelectionArea(state, controller, readerModel)
    }
}

@Composable
private fun ReaderOverlays(
    state: ReaderState,
    controller: EpubReaderController,
    model: ReaderModel
) {
    val ttsPlaybackState by controller.ttsPlaybackState.collectAsStateWithLifecycle()
    val ttsRate by controller.ttsRate.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.isControlsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                bookTitle = state.bookTitle,
                chapterTitle = state.currentChapterTitle,
                isBookmarked = state.isBookmarked,
                onBackClick = { model.onEvent(ReaderEvent.OnNavigateBack) },
                onSearchClick = { model.onEvent(ReaderEvent.OpenSheet(EpubReaderSheet.SEARCH)) },
                onBookmarkClick = { model.onEvent(ReaderEvent.ToggleBookmark) },
                onTocClick = { model.onEvent(ReaderEvent.OpenSheet(EpubReaderSheet.TOC)) },
                onAppearanceClick = { model.onEvent(ReaderEvent.OpenSheet(EpubReaderSheet.APPEARANCE)) },
                onReadAloudClick = { controller.startTts() }
            )
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = ttsPlaybackState != EpubTtsPlaybackState.IDLE,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                ReaderTtsBar(
                    playbackState = ttsPlaybackState,
                    rate = ttsRate,
                    onPlayPause = {
                        when (ttsPlaybackState) {
                            EpubTtsPlaybackState.PLAYING -> controller.pauseTts()
                            EpubTtsPlaybackState.PAUSED -> controller.resumeTts()
                            EpubTtsPlaybackState.IDLE -> controller.startTts()
                        }
                    },
                    onStop = { controller.stopTts() },
                    onRateChange = { controller.setTtsRate(it) }
                )
            }

            AnimatedVisibility(
                visible = state.isControlsVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                ReaderBottomBar(
                    progressFraction = state.progressFraction,
                    sectionIndex = state.currentSectionIndex,
                    totalSections = state.totalSections,
                    chapterTitle = state.currentChapterTitle,
                    onPrevPage = { controller.prevPage() },
                    onNextPage = { controller.nextPage() },
                    onProgressChange = { frac -> controller.goToFraction(frac) }
                )
            }
        }

        ReaderBrightnessHud(
            visible = state.isBrightnessOverlayVisible,
            brightness = state.brightness,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun ReaderModals(
    state: ReaderState,
    controller: EpubReaderController,
    model: ReaderModel,
    availableFonts: List<EpubFontOption>
) {
    when (state.activeSheet) {
        EpubReaderSheet.APPEARANCE -> ReaderAppearanceSheet(
            config = state.readerConfig,
            availableFonts = availableFonts,
            onThemeChange = { model.onEvent(ReaderEvent.ChangeTheme(it)) },
            onFlowChange = { model.onEvent(ReaderEvent.ChangeFlow(it)) },
            onFontSizeChange = { model.onEvent(ReaderEvent.SetFontSize(it)) },
            onFontFamilyChange = { model.onEvent(ReaderEvent.ChangeFontFamily(it)) },
            onLineHeightChange = { model.onEvent(ReaderEvent.ChangeLineHeight(it)) },
            onMarginChange = { model.onEvent(ReaderEvent.ChangeMargin(it)) },
            onTextAlignChange = { model.onEvent(ReaderEvent.ChangeTextAlign(it)) },
            onResetDefaults = { model.onEvent(ReaderEvent.ResetAppearance) },
            onDismissRequest = { model.onEvent(ReaderEvent.CloseSheet) }
        )
        EpubReaderSheet.TOC -> ReaderTocDrawer(
            tocItems = state.tocItems,
            bookmarks = state.bookmarks,
            currentChapterTitle = state.currentChapterTitle,
            onChapterClick = { href -> controller.goToHref(href) },
            onBookmarkClick = { cfi -> controller.goToCfi(cfi) },
            onDeleteBookmark = { cfi -> model.onEvent(ReaderEvent.DeleteBookmark(cfi)) },
            onDismissRequest = { model.onEvent(ReaderEvent.CloseSheet) }
        )
        EpubReaderSheet.SEARCH -> ReaderSearchDialog(
            searchQuery = state.searchQuery,
            searchResults = state.searchResults,
            isSearching = state.isSearching,
            onQueryChange = { model.onEvent(ReaderEvent.SearchQueryChanged(it)) },
            onSearch = { query ->
                model.onEvent(ReaderEvent.SearchQueryChanged(query))
                controller.search(query)
            },
            onClearSearch = {
                model.onEvent(ReaderEvent.SearchQueryChanged(""))
                controller.clearSearch()
            },
            onResultClick = { cfi -> controller.goToCfi(cfi) },
            onDismissRequest = { model.onEvent(ReaderEvent.CloseSheet) }
        )
        null -> Unit
    }

    state.activeFootnote?.let { footnote ->
        ReaderFootnoteSheet(
            footnote = footnote,
            onDismissRequest = { model.onEvent(ReaderEvent.DismissFootnote) }
        )
    }

    state.selectedAnnotation?.let { annotation ->
        ReaderAnnotationDialog(
            annotation = annotation,
            onDelete = { cfi ->
                controller.deleteAnnotation(cfi)
                model.onEvent(ReaderEvent.DeleteHighlight(cfi))
            },
            onDismissRequest = { model.onEvent(ReaderEvent.DismissAnnotationDetail) }
        )
    }
}

@Composable
private fun ReaderSelectionArea(
    state: ReaderState,
    controller: EpubReaderController,
    model: ReaderModel
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderSelectionToolbar(
            visible = state.textSelection != null && state.textSelection.text.isNotBlank(),
            onHighlight = { color ->
                val sel = state.textSelection
                if (sel != null && sel.cfi.isNotBlank()) {
                    val now = Clock.System.now().toEpochMilliseconds()
                    val annotation = EpubAnnotation(
                        id = "$now",
                        cfi = sel.cfi,
                        text = sel.text,
                        color = color,
                        timestamp = now
                    )
                    controller.addAnnotation(annotation)
                    model.onEvent(ReaderEvent.AddHighlight(color))
                    controller.clearSelection()
                }
            },
            onCopy = {
                state.textSelection?.text?.let { text ->
                    clipboardManager.setText(AnnotatedString(text))
                }
                controller.clearSelection()
                model.onEvent(ReaderEvent.ClearSelection)
            },
            onDismiss = {
                controller.clearSelection()
                model.onEvent(ReaderEvent.ClearSelection)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (state.isControlsVisible) 96.dp else 36.dp)
        )
    }
}
