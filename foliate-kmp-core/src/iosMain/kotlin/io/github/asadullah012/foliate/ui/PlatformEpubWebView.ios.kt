package io.github.asadullah012.foliate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.github.asadullah012.foliate.EpubReaderController
import io.github.asadullah012.foliate.internal.EpubTtsEngine
import io.github.asadullah012.foliate.internal.FOLIATE_LOG_TAG
import io.github.asadullah012.foliate.internal.FoliateAssets
import io.github.asadullah012.foliate.internal.isVerboseLoggingEnabled
import io.github.asadullah012.foliate.model.EpubFootnote
import io.github.asadullah012.foliate.model.EpubReaderLocation
import io.github.asadullah012.foliate.model.EpubSearchResult
import io.github.asadullah012.foliate.model.EpubTextSelection
import io.github.asadullah012.foliate.model.EpubTocItem
import io.github.asadullah012.foliate.model.EpubTtsSegment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.cValue
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
import platform.CoreGraphics.CGRect
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val FOLIATE_SCHEME = "foliate"
private const val READER_HOST = "localhost"
private const val READER_ORIGIN = "$FOLIATE_SCHEME://$READER_HOST"

private const val ENGINE_PATH_PREFIX = "/foliate/"
private const val BOOK_PATH_PREFIX = "/book/"

private const val READER_URL = "$READER_ORIGIN$ENGINE_PATH_PREFIX${FoliateAssets.ENTRY_POINT}"
private const val BOOK_URL = "$READER_ORIGIN${BOOK_PATH_PREFIX}book.epub"

private const val BRIDGE_NAME = "epubBridge"

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
 * @return `true` for the reader origin and for the engine's own documents.
 */
private fun isAllowedNavigation(scheme: String?): Boolean {
    val value = scheme?.lowercase() ?: return false
    return value == FOLIATE_SCHEME || value in ENGINE_SCHEMES
}

/**
 * Holds every object that WebKit keeps only as a weak reference.
 *
 * `WKWebView.navigationDelegate` is a weak property. Without a strong reference here,
 * ARC frees the delegate at once and the navigation callbacks never run.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosReaderHost(
    val webView: WKWebView,
    val navigationDelegate: WKNavigationDelegateProtocol,
    val schemeHandler: FoliateSchemeHandler,
    val messageHandler: IosEpubScriptMessageHandler
)

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformEpubWebView(
    bookPath: String,
    initialCfi: String?,
    controller: EpubReaderController,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()

    val ttsEngine = remember(controller) { EpubTtsEngine() }

    val host = remember(bookPath, controller) {
        var webViewRef: WKWebView? = null

        val messageHandler = IosEpubScriptMessageHandler(controller, scope, initialCfi) { webViewRef }
        val schemeHandler = FoliateSchemeHandler(bookPath, controller)

        val contentController = WKUserContentController()
        contentController.addScriptMessageHandler(messageHandler, BRIDGE_NAME)

        val configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.setURLSchemeHandler(schemeHandler, forURLScheme = FOLIATE_SCHEME)

        val wv = WKWebView(frame = cValue<CGRect>(), configuration = configuration)
        webViewRef = wv

        val navigationDelegate = FoliateNavigationDelegate(controller, initialCfi)
        wv.navigationDelegate = navigationDelegate

        IosReaderHost(
            webView = wv,
            navigationDelegate = navigationDelegate,
            schemeHandler = schemeHandler,
            messageHandler = messageHandler
        )
    }

    DisposableEffect(host, controller, ttsEngine) {
        val webView = host.webView

        controller.jsEvaluator = { script ->
            // WKWebView accepts calls on the main thread only.
            onMainThread { webView.evaluateJavaScript(script, null) }
        }
        controller.ttsEngine = ttsEngine

        val request = NSURLRequest.requestWithURL(NSURL.URLWithString(READER_URL)!!)
        webView.loadRequest(request)

        onDispose {
            controller.jsEvaluator = null
            controller.ttsEngine = null
            controller.onDetached()
            ttsEngine.release()
            webView.navigationDelegate = null
            webView.configuration.userContentController.removeScriptMessageHandlerForName(BRIDGE_NAME)
            webView.stopLoading()
        }
    }

    UIKitView(
        factory = { host.webView },
        modifier = modifier
    )
}

/** Runs [block] on the main queue. Runs it at once when the caller is already there. */
private fun onMainThread(block: () -> Unit) {
    if (platform.Foundation.NSThread.isMainThread) {
        block()
    } else {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

/**
 * Opens the publication when the reader page finishes, and reports load failures.
 *
 * It also keeps the web view on the reader origin. The web view holds a JavaScript
 * bridge. A link inside an untrusted publication must not move the web view to a
 * remote page, because that page then reaches the bridge.
 */
@OptIn(ExperimentalForeignApi::class)
private class FoliateNavigationDelegate(
    private val controller: EpubReaderController,
    private val initialCfi: String?
) : NSObject(), WKNavigationDelegateProtocol {

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit
    ) {
        val url = decidePolicyForNavigationAction.request.URL
        if (url == null || isAllowedNavigation(url.scheme)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            println("[$FOLIATE_LOG_TAG] Blocked navigation to an external origin: ${url.scheme}://${url.host}")
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        val cfiArg = if (!initialCfi.isNullOrBlank()) Json.encodeToString(initialCfi) else "null"
        val js = "if (window.readerController && !window._bookOpened) " +
            "{ window.readerController.openBook('$BOOK_URL', $cfiArg); }"
        webView.evaluateJavaScript(js, null)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError
    ) {
        controller.onError("Failed to load reader: ${withError.localizedDescription}")
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError
    ) {
        controller.onError("Navigation error: ${withError.localizedDescription}")
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FoliateSchemeHandler(
    private val bookPath: String,
    private val controller: EpubReaderController
) : NSObject(), WKURLSchemeHandlerProtocol {

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val url = startURLSchemeTask.request.URL ?: run {
            startURLSchemeTask.didFailWithError(NSError.errorWithDomain("FoliateScheme", 400, null))
            return
        }

        val path = url.path ?: ""

        if (path.startsWith(BOOK_PATH_PREFIX)) {
            serveBook(url, startURLSchemeTask)
            return
        }

        serveEngineFile(url, path, startURLSchemeTask)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        // Every response completes inside startURLSchemeTask, so there is nothing to abort.
    }

    private fun serveBook(url: NSURL, task: WKURLSchemeTaskProtocol) {
        val resolvedPath = resolveBookFilePath(bookPath)
        val fileManager = NSFileManager.defaultManager

        if (!fileManager.fileExistsAtPath(resolvedPath)) {
            task.didFailWithError(NSError.errorWithDomain("FoliateScheme", 404, null))
            controller.onError("Book file not found: $resolvedPath")
            return
        }

        val data = NSData.dataWithContentsOfFile(resolvedPath)
        if (data == null) {
            task.didFailWithError(NSError.errorWithDomain("FoliateScheme", 500, null))
            controller.onError("Could not read book file: $resolvedPath")
            return
        }

        respond(task, url, data, "application/epub+zip")
    }

    private fun serveEngineFile(url: NSURL, path: String, task: WKURLSchemeTaskProtocol) {
        val name = path.removePrefix(ENGINE_PATH_PREFIX).trimStart('/')
            .ifEmpty { FoliateAssets.ENTRY_POINT }

        if (name.contains("..")) {
            task.didFailWithError(NSError.errorWithDomain("FoliateScheme", 403, null))
            return
        }

        val data = readEngineFile(name)
        if (data == null) {
            println("[$FOLIATE_LOG_TAG] Engine file not found in the bundle: $name")
            task.didFailWithError(NSError.errorWithDomain("FoliateScheme", 404, null))
            return
        }

        respond(task, url, data, FoliateAssets.contentTypeFor(name))
    }

    /**
     * Reads one engine file through the Compose resource reader.
     *
     * The reader knows where the resources are, both in an application bundle and in
     * an embedded framework. Never build this path by hand.
     */
    private fun readEngineFile(name: String): NSData? {
        val uri = try {
            FoliateAssets.uriFor(name)
        } catch (e: Exception) {
            println("[$FOLIATE_LOG_TAG] Could not resolve the engine resource $name: ${e.message}")
            return null
        }
        val filePath = NSURL.URLWithString(uri)?.path ?: return null
        return NSData.dataWithContentsOfFile(filePath)
    }

    private fun respond(task: WKURLSchemeTaskProtocol, url: NSURL, data: NSData, contentType: String) {
        val response = NSHTTPURLResponse(
            uRL = url,
            statusCode = 200,
            HTTPVersion = "HTTP/1.1",
            headerFields = mapOf(
                "Content-Type" to contentType,
                "Content-Length" to "${data.length}",
                "Access-Control-Allow-Origin" to READER_ORIGIN,
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "*"
            )
        )
        task.didReceiveResponse(response)
        task.didReceiveData(data)
        task.didFinish()
    }

    private fun resolveBookFilePath(rawPath: String): String {
        val clean = rawPath.removePrefix("file://")
        val decoded = NSURL.URLWithString("file://$clean")?.path ?: clean
        val fileManager = NSFileManager.defaultManager

        if (fileManager.fileExistsAtPath(decoded)) {
            return decoded
        }

        if (fileManager.fileExistsAtPath(clean)) {
            return clean
        }

        val relative = clean.trimStart('/')
        val decodedRelative = NSURL.URLWithString("file:///$relative")?.path?.removePrefix("/") ?: relative

        val searchDomains = listOf(NSApplicationSupportDirectory, NSDocumentDirectory, NSCachesDirectory)
        for (domain in searchDomains) {
            val dirPaths = NSSearchPathForDirectoriesInDomains(domain, NSUserDomainMask, true)
            val dir = dirPaths.firstOrNull() as? String ?: continue
            for (rel in listOf(decodedRelative, relative)) {
                val candidateDirect = "$dir/$rel"
                if (fileManager.fileExistsAtPath(candidateDirect)) return candidateDirect
            }
        }

        val tempCandidate = NSTemporaryDirectory() + relative
        if (fileManager.fileExistsAtPath(tempCandidate)) return tempCandidate

        return decoded
    }
}

private class IosEpubScriptMessageHandler(
    private val controller: EpubReaderController,
    private val scope: CoroutineScope,
    private val initialCfi: String?,
    private val getWebView: () -> WKWebView?
) : NSObject(), WKScriptMessageHandlerProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage
    ) {
        val body = didReceiveScriptMessage.body as? String ?: return
        scope.launch(Dispatchers.Main) {
            try {
                val element = json.parseToJsonElement(body).jsonObject
                val type = element["type"]?.jsonPrimitive?.contentOrNull
                when (type) {
                    "initialized" -> {
                        val cfiArg = if (!initialCfi.isNullOrBlank()) json.encodeToString(initialCfi) else "null"
                        val js = "window.readerController && window.readerController.openBook('$BOOK_URL', $cfiArg)"
                        getWebView()?.evaluateJavaScript(js, null)
                    }
                    "log" -> {
                        val level = element["level"]?.jsonPrimitive?.contentOrNull ?: "log"
                        val msg = element["message"]?.jsonPrimitive?.contentOrNull ?: ""
                        // An engine message can quote an untrusted publication, so a
                        // release build must not write it to the device log.
                        val isProblem = level.lowercase() == "error" || level.lowercase() == "warn"
                        if (isProblem || isVerboseLoggingEnabled()) {
                            println("[$FOLIATE_LOG_TAG][$level] $msg")
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
                        println("[$FOLIATE_LOG_TAG] Unknown bridge message type: $type")
                    }
                }
            } catch (e: Exception) {
                println("[$FOLIATE_LOG_TAG] Error parsing script message: ${e.message}")
            }
        }
    }
}
