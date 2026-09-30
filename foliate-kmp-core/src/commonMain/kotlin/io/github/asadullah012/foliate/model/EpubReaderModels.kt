package io.github.asadullah012.foliate.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Visual theme for rendering EPUB content.
 *
 * @property backgroundColor Hex color code for the background (e.g. "#FFFFFF").
 * @property textColor Hex color code for the text (e.g. "#121212").
 */
@Immutable
public enum class EpubReaderTheme(
    public val backgroundColor: String,
    public val textColor: String,
    public val displayName: String
) {
    LIGHT(backgroundColor = "#FFFFFF", textColor = "#1A1A1A", displayName = "Light"),
    SEPIA(backgroundColor = "#F4ECD8", textColor = "#4A3B2C", displayName = "Sepia"),
    SLATE(backgroundColor = "#2C3440", textColor = "#DCE1E8", displayName = "Slate"),
    DARK(backgroundColor = "#1E1E1E", textColor = "#E0E0E0", displayName = "Dark"),
    AMOLED(backgroundColor = "#000000", textColor = "#D4D4D8", displayName = "AMOLED")
}

/**
 * Reading flow presentation mode.
 */
@Immutable
public enum class EpubReaderFlow {
    /** Horizontal, paginated book view with discrete page turns. */
    PAGINATED,
    /** Continuous vertical scrolling. */
    SCROLLED
}

/**
 * Configuration options applied to the EPUB reader.
 *
 * @property fontSize Font size in pixels.
 * @property theme Active visual color scheme.
 * @property flow Layout flow mode (paginated vs scrolled).
 * @property fontFamily Font family stack used for reading.
 * @property lineHeight Line spacing multiplier (e.g. 1.4, 1.6, 2.0).
 * @property margin Page margin padding in pixels.
 * @property textAlign Text alignment ("justify" or "left").
 */
@Immutable
public data class EpubReaderConfig(
    public val fontSize: Int = 18,
    public val theme: EpubReaderTheme = EpubReaderTheme.LIGHT,
    public val flow: EpubReaderFlow = EpubReaderFlow.PAGINATED,
    public val fontFamily: String = "system-ui, -apple-system, sans-serif",
    public val lineHeight: Float = 1.6f,
    public val margin: Int = 32,
    public val textAlign: String = "justify"
)

/**
 * Represents the current reading position inside an EPUB publication.
 *
 * @property cfi Canonical Fragment Identifier (CFI) string representing exact location.
 * @property progressFraction Relative completion progress across the entire book (0.0 to 1.0).
 * @property chapterTitle Current chapter or section title if resolved from the Table of Contents.
 * @property sectionIndex Zero-based index of the active spine section.
 * @property totalSections Total number of spine sections in the book.
 */
@Immutable
@Serializable
public data class EpubReaderLocation(
    public val cfi: String? = null,
    public val progressFraction: Float = 0.0f,
    public val chapterTitle: String? = null,
    public val sectionIndex: Int = 0,
    public val totalSections: Int = 1
)

/**
 * Preserved reading progress within a book.
 *
 * @property fraction Proportional completion progress across the entire publication (0.0 to 1.0).
 * @property cfi Optional Canonical Fragment Identifier pointing to exact reading location.
 */
@Immutable
@Serializable
public data class EpubProgress(
    public val fraction: Float = 0.0f,
    public val cfi: String? = null
)

/**
 * Item in an EPUB Table of Contents tree.
 *
 * @property id Unique identifier of the TOC entry.
 * @property label Human-readable title of the chapter or section.
 * @property href Relative link or fragment inside the publication package.
 * @property subitems Nested TOC subsections.
 */
@Immutable
@Serializable
public data class EpubTocItem(
    public val id: String = "",
    public val label: String = "",
    public val href: String = "",
    public val subitems: List<EpubTocItem> = emptyList()
)

/**
 * Search result match within the publication text.
 *
 * @property cfi Canonical Fragment Identifier pointing to the match location.
 * @property chapterTitle Title of the chapter where match occurred.
 * @property excerpt Surrounding text context snippet with matched text.
 */
@Immutable
@Serializable
public data class EpubSearchResult(
    public val cfi: String = "",
    public val chapterTitle: String? = null,
    public val excerpt: String = ""
)

/**
 * Bookmark entry saved by user.
 *
 * @property cfi Exact location identifier.
 * @property title Chapter or excerpt title.
 * @property timestamp Epoch millis when bookmark was created.
 * @property progressFraction Book progress percentage at time of bookmark.
 */
@Immutable
@Serializable
public data class EpubBookmark(
    public val cfi: String = "",
    public val title: String = "",
    public val timestamp: Long = 0L,
    public val progressFraction: Float = 0.0f
)

/**
 * Text selection range and content in the publication.
 *
 * @property text The selected plain text.
 * @property cfi Canonical Fragment Identifier for the selected text range.
 * @property sectionIndex Index of the spine section where the selection occurred.
 */
@Immutable
@Serializable
public data class EpubTextSelection(
    public val text: String = "",
    public val cfi: String = "",
    public val sectionIndex: Int = 0
)

/**
 * Visual highlight or note annotation.
 *
 * @property id Unique identifier for the annotation.
 * @property cfi Canonical Fragment Identifier for the highlighted range.
 * @property text The highlighted text excerpt.
 * @property color Hex color string (e.g. "#FFEB3B").
 * @property note Optional user note attached to the highlight.
 * @property timestamp Epoch millis when created.
 */
@Immutable
@Serializable
public data class EpubAnnotation(
    public val id: String = "",
    public val cfi: String = "",
    public val text: String = "",
    public val color: String = "#FFEB3B",
    public val note: String? = null,
    public val timestamp: Long = 0L
)

/**
 * Footnote reference content.
 *
 * @property text The extracted body of the footnote.
 * @property href The original reference link href.
 * @property title Optional footnote title or identifier (e.g. "[1]").
 */
@Immutable
@Serializable
public data class EpubFootnote(
    public val text: String = "",
    public val href: String = "",
    public val title: String? = null
)

/**
 * Font option available for reading appearance customization.
 */
@Immutable
public data class EpubFontOption(
    public val id: String,
    public val displayName: String,
    public val fontFamilyStack: String
) {
    public companion object {
        public val DEFAULT_FONTS: List<EpubFontOption> = listOf(
            EpubFontOption("system", "System", "system-ui, -apple-system, sans-serif"),
            EpubFontOption("serif", "Serif", "Georgia, 'Times New Roman', serif"),
            EpubFontOption("monospace", "Monospace", "ui-monospace, monospace")
        )
    }
}

/**
 * Active reader sheet or overlay dialog.
 */
@Immutable
public enum class EpubReaderSheet {
    APPEARANCE,
    TOC,
    SEARCH
}

/**
 * One spoken unit of text-to-speech playback within the current section.
 *
 * The reader engine returns a block of speech as SSML with a mark before each word (or
 * sentence). A segment is the text between one mark and the next, paired with that
 * mark's name. The platform speech engine speaks segments in order and reports each
 * [mark] back to the engine as it starts, so the corresponding text can be highlighted.
 *
 * @property mark Name of the SSML mark that starts this segment.
 * @property text The text to speak for this segment.
 */
@Immutable
@Serializable
public data class EpubTtsSegment(
    public val mark: String = "",
    public val text: String = ""
)

/**
 * Playback state of text-to-speech read-aloud.
 */
@Immutable
public enum class EpubTtsPlaybackState {
    /** No text-to-speech session is active. */
    IDLE,
    /** Speech is actively playing. */
    PLAYING,
    /** A speech session exists but is paused. */
    PAUSED
}
