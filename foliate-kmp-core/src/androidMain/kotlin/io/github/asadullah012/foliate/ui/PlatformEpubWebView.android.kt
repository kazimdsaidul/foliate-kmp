package io.github.asadullah012.foliate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.asadullah012.foliate.EpubReaderController
import io.github.asadullah012.foliate.internal.FOLIATE_LOG_TAG
import io.github.asadullah012.foliate.internal.FoliateAssets
import io.github.asadullah012.foliate.internal.isVerboseLoggingEnabled
import io.github.asadullah012.foliate.internal.EpubTtsEngine
import io.github.asadullah012.foliate.model.EpubFootnote
import io.github.asadullah012.foliate.model.EpubReaderLocation
import io.github.asadullah012.foliate.model.EpubSearchResult
import io.github.asadullah012.foliate.model.EpubTextSelection
import io.github.asadullah012.foliate.model.EpubTocItem
import io.github.asadullah012.foliate.model.EpubTtsSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

private const val TAG = "Foliate-Android"

/** The single origin that the reader web view is allowed to load. */
private const val READER_HOST = "appassets.androidplatform.net"
private const val READER_ORIGIN = "https://$READER_HOST"

private const val ENGINE_PATH_PREFIX = "/foliate/"
private const val BOOK_PATH_PREFIX = "/book/"

private const val READER_URL = "$READER_ORIGIN$ENGINE_PATH_PREFIX${FoliateAssets.ENTRY_POINT}"
private const val BOOK_URL = "$READER_ORIGIN${BOOK_PATH_PREFIX}book.epub"

/** The prefix that the Compose resource reader puts in front of an Android asset path. */
private const val ANDROID_ASSET_URI_PREFIX = "file:///android_asset/"

/**
 * The schemes that the engine uses for its own documents.
 *
 * foliate-js puts each section of the publication into an iframe and gives the iframe
 * a `blob:` URL. An empty iframe starts at `about:blank`. The navigation guard must
 * let these through, or the publication does not render.
 */
private val ENGINE_SCHEMES = setOf("blob", "data", "about")

/**
 * Tells whether the web view is allowed to navigate to this address.
 *
 * @param scheme The URL scheme.
 * @param host The URL host, or `null` for a scheme that has no host.
 * @return `true` for the reader origin and for the engine's own documents.
 */
private fun isAllowedNavigation(scheme: String?, host: String?): Boolean {
    if (scheme == null) return false
    if (scheme.lowercase() in ENGINE_SCHEMES) return true
    return scheme == "https" && host == READER_HOST
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun PlatformEpubWebView(
    bookPath: String,
    initialCfi: String?,
    controller: EpubReaderController,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .setDomain(READER_HOST)
            .addPathHandler(ENGINE_PATH_PREFIX, FoliateEnginePathHandler(context))
            .build()
    }

    val ttsEngine = remember(context) { EpubTtsEngine().also { it.attach(context) } }

    val webView = remember(context, bookPath, controller) {
        var webViewRef: WebView? = null
        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // The engine and the publication both arrive through the asset loader.
                // The web view never needs direct access to the file system.
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val bridge = AndroidEpubBridge(controller, scope, initialCfi) { webViewRef }
            addJavascriptInterface(bridge, "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    // A console message holds text from an untrusted publication, so a
                    // release build must not write it to the device log.
                    if (consoleMessage != null && isVerboseLoggingEnabled()) {
                        Log.d(
                            FOLIATE_LOG_TAG,
                            "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {

                /**
                 * Keeps the web view on the reader origin.
                 *
                 * The web view holds a JavaScript bridge. A link inside an untrusted
                 * publication must not move the web view to a remote page, because that
                 * page then reaches the bridge. This method blocks every other origin.
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return true
                    if (isAllowedNavigation(url.scheme, url.host)) {
                        return false
                    }
                    Log.w(TAG, "Blocked navigation to an external origin: ${url.scheme}://${url.host}")
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null

                    // 1. Serve the foliate-js engine from the Compose resources.
                    val assetResponse = assetLoader.shouldInterceptRequest(url)
                    if (assetResponse != null) return assetResponse

                    // 2. Stream the publication from local storage.
                    if (url.host == READER_HOST && url.path?.startsWith(BOOK_PATH_PREFIX) == true) {
                        val (stream, resolvedPath) = resolveAndroidBookInputStream(context, bookPath)
                        if (stream != null) {
                            return WebResourceResponse(
                                "application/epub+zip",
                                null,
                                200,
                                "OK",
                                mapOf(
                                    "Access-Control-Allow-Origin" to READER_ORIGIN,
                                    "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                                    "Access-Control-Allow-Headers" to "*"
                                ),
                                stream
                            )
                        }
                        Log.e(TAG, "Book file not found or unreadable: $resolvedPath")
                        scope.launch(Dispatchers.Main) {
                            controller.onError("Book file not found or unreadable: $resolvedPath")
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val cfiArg = if (!initialCfi.isNullOrBlank()) Json.encodeToString(initialCfi) else "null"
                    view?.evaluateJavascript(
                        "if (window.readerController && !window._bookOpened) " +
                            "{ window.readerController.openBook('$BOOK_URL', $cfiArg); }",
                        null
                    )
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        controller.onError("Failed to load reader: ${error?.description}")
                    }
                }
            }

            loadUrl(READER_URL)
        }
        webViewRef = wv
        wv
    }

    DisposableEffect(webView, controller, ttsEngine) {
        controller.jsEvaluator = { script ->
            webView.post {
                webView.evaluateJavascript(script, null)
            }
        }
        controller.ttsEngine = ttsEngine
        onDispose {
            controller.jsEvaluator = null
            controller.ttsEngine = null
            controller.onDetached()
            ttsEngine.release()
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )
}

/**
 * Serves the bundled foliate-js engine from the Compose resource assets.
 *
 * The engine files ship inside the AAR under `assets/composeResources/...`. This handler
 * asks the Compose resource reader for the real asset path, so the path stays correct
 * when the Maven coordinates or the packaging change.
 */
internal class FoliateEnginePathHandler(
    private val context: Context
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val name = path.trimStart('/').ifEmpty { FoliateAssets.ENTRY_POINT }
        if (name.contains("..")) {
            Log.w(TAG, "Rejected an engine path that leaves the resource root: $path")
            return notFound()
        }

        val assetPath = assetPathFor(name) ?: return notFound()
        return try {
            WebResourceResponse(
                // Android reads the MIME type and the character set separately.
                // A charset inside the MIME type argument stops the page from rendering.
                FoliateAssets.mimeTypeFor(name),
                FoliateAssets.charsetFor(name),
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to READER_ORIGIN),
                context.assets.open(assetPath)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Engine file not found in the assets: $assetPath", e)
            notFound()
        }
    }

    /**
     * Answers with an empty 404.
     *
     * A `null` result makes the web view fall back to the network for a host that
     * does not exist. An empty response ends the request at once.
     */
    private fun notFound(): WebResourceResponse = WebResourceResponse(null, null, null)

    private fun assetPathFor(name: String): String? {
        val uri = try {
            FoliateAssets.uriFor(name)
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve the engine resource: $name", e)
            return null
        }
        if (!uri.startsWith(ANDROID_ASSET_URI_PREFIX)) {
            Log.e(TAG, "Unexpected engine resource URI: $uri")
            return null
        }
        return Uri.decode(uri.removePrefix(ANDROID_ASSET_URI_PREFIX))
    }
}

private fun resolveAndroidBookInputStream(context: Context, rawPath: String): Pair<InputStream?, String> {
    val clean = rawPath.removePrefix("file://")
    val direct = File(clean)
    if (direct.exists() && direct.canRead()) {
        return try {
            FileInputStream(direct) to direct.absolutePath
        } catch (e: Exception) {
            null to direct.absolutePath
        }
    }

    if (rawPath.startsWith("content://")) {
        return try {
            val uri = Uri.parse(rawPath)
            context.contentResolver.openInputStream(uri) to rawPath
        } catch (e: Exception) {
            null to rawPath
        }
    }

    val candidateFiles = File(context.filesDir, clean.trimStart('/'))
    if (candidateFiles.exists() && candidateFiles.canRead()) {
        return try {
            FileInputStream(candidateFiles) to candidateFiles.absolutePath
        } catch (e: Exception) {
            null to candidateFiles.absolutePath
        }
    }

    val candidateCache = File(context.cacheDir, clean.trimStart('/'))
    if (candidateCache.exists() && candidateCache.canRead()) {
        return try {
            FileInputStream(candidateCache) to candidateCache.absolutePath
        } catch (e: Exception) {
            null to candidateCache.absolutePath
        }
    }

    return null to direct.absolutePath
}

internal class AndroidEpubBridge(
    private val controller: EpubReaderController,
    private val scope: CoroutineScope,
    private val initialCfi: String?,
    private val getWebView: () -> WebView?
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @JavascriptInterface
    fun postMessage(message: String) {
        scope.launch(Dispatchers.Main) {
            try {
                val element = json.parseToJsonElement(message).jsonObject
                val type = element["type"]?.jsonPrimitive?.contentOrNull
                when (type) {
                    "initialized" -> {
                        val cfiArg = if (!initialCfi.isNullOrBlank()) json.encodeToString(initialCfi) else "null"
                        val js = "window.readerController && window.readerController.openBook('$BOOK_URL', $cfiArg)"
                        getWebView()?.evaluateJavascript(js, null)
                    }
                    "log" -> {
                        val level = element["level"]?.jsonPrimitive?.contentOrNull ?: "log"
                        val msg = element["message"]?.jsonPrimitive?.contentOrNull ?: ""
                        when (level.lowercase()) {
                            "error" -> Log.e(FOLIATE_LOG_TAG, msg)
                            "warn" -> Log.w(FOLIATE_LOG_TAG, msg)
                            // An informational engine message can quote the publication.
                            "info" -> if (isVerboseLoggingEnabled()) Log.i(FOLIATE_LOG_TAG, msg)
                            else -> if (isVerboseLoggingEnabled()) Log.d(FOLIATE_LOG_TAG, msg)
                        }
                    }
                    "relocate" -> {
                        val cfi = element["cfi"]?.jsonPrimitive?.contentOrNull
                        val fraction = element["fraction"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val chapterTitle = element["chapterTitle"]?.jsonPrimitive?.contentOrNull
                        val sectionIndex = element["sectionIndex"]?.jsonPrimitive?.intOrNull ?: 0
                        val totalSections = element["totalSections"]?.jsonPrimitive?.intOrNull ?: 1
                        controller.onRelocate(
                            EpubReaderLocation(
                                cfi = cfi,
                                progressFraction = fraction,
                                chapterTitle = chapterTitle,
                                sectionIndex = sectionIndex,
                                totalSections = totalSections
                            )
                        )
                    }
                    "toc" -> {
                        val items = element["items"]?.jsonArray
                        if (items != null) {
                            val tocList = json.decodeFromJsonElement<List<EpubTocItem>>(items)
                            controller.onTocLoaded(tocList)
                        }
                    }
                    "ready" -> {
                        controller.onReady()
                    }
                    "error" -> {
                        val msg = element["message"]?.jsonPrimitive?.contentOrNull ?: "Error reading EPUB"
                        controller.onError(msg)
                    }
                    "toggle_controls" -> {
                        controller.onToggleControls()
                    }
                    "search_results" -> {
                        val items = element["items"]?.jsonArray
                        if (items != null) {
                            val results = json.decodeFromJsonElement<List<EpubSearchResult>>(items)
                            controller.onSearchResults(results)
                        } else {
                            controller.onSearchResults(emptyList())
                        }
                    }
                    "adjust_brightness" -> {
                        val delta = element["delta"]?.jsonPrimitive?.floatOrNull ?: 0f
                        controller.onAdjustBrightness(delta)
                    }
                    "end_brightness" -> {
                        controller.onEndBrightness()
                    }
                    "selection" -> {
                        val text = element["text"]?.jsonPrimitive?.contentOrNull
                        val cfi = element["cfi"]?.jsonPrimitive?.contentOrNull
                        val sectionIndex = element["sectionIndex"]?.jsonPrimitive?.intOrNull ?: 0
                        if (!text.isNullOrBlank() && !cfi.isNullOrBlank()) {
                            controller.onSelection(EpubTextSelection(text = text, cfi = cfi, sectionIndex = sectionIndex))
                        } else {
                            controller.onSelection(null)
                        }
                    }
                    "footnote" -> {
                        val text = element["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val href = element["href"]?.jsonPrimitive?.contentOrNull ?: ""
                        val title = element["title"]?.jsonPrimitive?.contentOrNull
                        if (text.isNotBlank()) {
                            controller.onFootnote(EpubFootnote(text = text, href = href, title = title))
                        }
                    }
                    "annotation_click" -> {
                        val cfi = element["cfi"]?.jsonPrimitive?.contentOrNull
                        if (!cfi.isNullOrBlank()) {
                            controller.onAnnotationClick(cfi)
                        }
                    }
                    "tts_block" -> {
                        val items = element["segments"]?.jsonArray
                        val segments = if (items != null) {
                            json.decodeFromJsonElement<List<EpubTtsSegment>>(items)
                        } else {
                            emptyList()
                        }
                        controller.onTtsBlock(segments)
                    }
                    "tts_highlight" -> {
                        val cfi = element["cfi"]?.jsonPrimitive?.contentOrNull
                        if (!cfi.isNullOrBlank()) {
                            controller.onTtsHighlight(cfi)
                        }
                    }
                    "tts_section_end" -> {
                        controller.onTtsSectionEnd()
                    }
                    else -> {
                        Log.w(TAG, "Unknown bridge message type: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing script message: ${e.message}", e)
            }
        }
    }
}
