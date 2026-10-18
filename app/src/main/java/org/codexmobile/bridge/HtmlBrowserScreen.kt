package org.codexmobile.bridge

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File

private const val LOCAL_HTML_HOST = "codex.local"
private const val CHROME_AUTO_HIDE_MS = 4_000L

private fun WebView.setDocumentChromeVisible(visible: Boolean) {
    val visibleLiteral = if (visible) "true" else "false"
    evaluateJavascript(
        """
        (function () {
          const hiddenClass = 'codex-mobile-browser-chrome-hidden';
          let style = document.getElementById('codex-mobile-browser-chrome-style');
          if (!style) {
            style = document.createElement('style');
            style.id = 'codex-mobile-browser-chrome-style';
            style.textContent = '.' + hiddenClass + '{display:none!important;}';
            document.head.appendChild(style);
          }
          const targets = new Set(document.querySelectorAll('.topbar, .panel'));
          Array.from(document.body.children).forEach(function (element) {
            const box = element.getBoundingClientRect();
            const position = getComputedStyle(element).position;
            if ((position === 'fixed' || position === 'sticky') && box.top <= 12 && box.height < innerHeight * 0.25) {
              targets.add(element);
            }
          });
          targets.forEach(function (element) {
            element.classList.toggle(hiddenClass, !$visibleLiteral);
          });
        })();
        """.trimIndent(),
        null,
    )
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HtmlBrowserScreen(preview: FilePreview) {
    var webView by remember(preview.localPath) { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var chromeVisible by remember(preview.localPath) { mutableStateOf(false) }
    var chromeInteraction by remember(preview.localPath) { mutableLongStateOf(0L) }
    val view = LocalView.current
    val window = (view.context as? Activity)?.window

    val revealChrome = {
        chromeVisible = true
        chromeInteraction += 1L
    }
    val keepChromeAlive = {
        if (chromeVisible) chromeInteraction += 1L
    }
    val chromeVisibleState by rememberUpdatedState(chromeVisible)
    val revealChromeState by rememberUpdatedState(revealChrome)
    val keepChromeAliveState by rememberUpdatedState(keepChromeAlive)

    DisposableEffect(window, view) {
        val originalCutoutMode = window?.attributes?.layoutInDisplayCutoutMode
        if (window != null) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
                if (originalCutoutMode != null) {
                    val attributes = window.attributes
                    attributes.layoutInDisplayCutoutMode = originalCutoutMode
                    window.attributes = attributes
                }
            }
        }
    }

    LaunchedEffect(chromeVisible, window, view) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (chromeVisible) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        webView?.setDocumentChromeVisible(chromeVisible)
    }

    LaunchedEffect(chromeVisible, chromeInteraction) {
        if (chromeVisible) {
            delay(CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    val leaveBrowser = {
        webView?.stopLoading()
        BridgeClient.closeFilePreview()
    }
    BackHandler {
        val browser = webView
        if (browser?.canGoBack() == true) browser.goBack() else leaveBrowser()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (chromeVisible) {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                preview.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = leaveBrowser) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回会话")
                            }
                        },
                        actions = {
                            IconButton(onClick = { keepChromeAlive(); webView?.goBack() }, enabled = canGoBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "网页后退")
                            }
                            IconButton(onClick = { keepChromeAlive(); webView?.goForward() }, enabled = canGoForward) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, "网页前进")
                            }
                            IconButton(onClick = { keepChromeAlive(); webView?.reload() }) {
                                Icon(Icons.Default.Refresh, "刷新网页")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    )
                    if (progress in 0..99) LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        key(preview.localPath) {
            AndroidView(
                modifier = Modifier.padding(padding).fillMaxSize(),
                factory = { appContext ->
                    WebView(appContext).apply {
                    val tapDetector = GestureDetector(appContext, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                            revealChromeState()
                            return false
                        }
                    })
                    setOnTouchListener { _, event ->
                        if (chromeVisibleState && event.actionMasked == MotionEvent.ACTION_DOWN) keepChromeAliveState()
                        tapDetector.onTouchEvent(event)
                        false
                    }
                    WebView.setWebContentsDebuggingEnabled(false)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = updateNavigation(view)
                        override fun onPageFinished(view: WebView?, url: String?) {
                            updateNavigation(view)
                            view?.setDocumentChromeVisible(chromeVisibleState)
                        }
                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = updateNavigation(view)

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame || request.url.host != LOCAL_HTML_HOST) return false
                            if (!request.url.fragment.isNullOrBlank() && request.url.encodedPath == Uri.parse(view.url).encodedPath) return false
                            val target = BridgeClient.resolveHtmlTarget(preview.sourcePath, request.url.encodedPath ?: "/", navigation = true)
                                ?: return true
                            BridgeClient.downloadFile(target)
                            return true
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                            if (request.url.host != LOCAL_HTML_HOST) return null
                            val resource = BridgeClient.fetchHtmlResourceBlocking(preview.sourcePath, request.url.encodedPath ?: "/") ?: return null
                            val encoding = if (resource.mimeType.startsWith("text/") || resource.mimeType.contains("javascript") || resource.mimeType.contains("json")) "UTF-8" else null
                            return WebResourceResponse(resource.mimeType, encoding, ByteArrayInputStream(resource.bytes))
                        }

                        private fun updateNavigation(view: WebView?) {
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }
                    }
                    val html = File(preview.localPath).readText(Charsets.UTF_8)
                    loadDataWithBaseURL("https://$LOCAL_HTML_HOST/", html, "text/html", "UTF-8", null)
                        webView = this
                    }
                },
                onRelease = { browser ->
                    browser.stopLoading()
                    browser.destroy()
                    if (webView === browser) webView = null
                },
            )
        }
    }
}
