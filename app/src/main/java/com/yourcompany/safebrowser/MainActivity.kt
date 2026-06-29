package com.yourcompany.safebrowser

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SafeBrowser"
        private const val REMOTE_CONFIG_URL = "https://yourserver.com/blocklist.json"
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var prefs: SharedPreferences
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private val gson = Gson()

    private var blockedDomains = mutableListOf<String>()
    private var blockedKeywords = mutableListOf<String>()
    private var videoBlocking = true

    private val videoExtensions = listOf(
        ".mp4", ".webm", ".m3u8", ".ts", ".mkv",
        ".avi", ".mov", ".flv", ".wmv", ".3gp"
    )

    private val fallbackBlockedDomains = listOf(
        "youtube.com", "youtu.be", "facebook.com", "fb.com",
        "tiktok.com", "netflix.com", "primevideo.com", "disneyplus.com",
        "hulu.com", "twitch.tv", "vimeo.com", "dailymotion.com",
        "twitter.com", "x.com", "instagram.com", "snapchat.com",
        "reddit.com", "9gag.com", "bilibili.com", "tiktokcdn.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("SafeBrowserPrefs", Context.MODE_PRIVATE)
        loadBlocklist()
        fetchBlocklist()
        setupUI()
        startPeriodicRefresh()
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val addressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        val backButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        }

        val forwardButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        }

        val refreshButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            setOnClickListener { webView.reload() }
        }

        urlInput = EditText(this).apply {
            hint = "Enter URL or search..."
            maxLines = 1
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    loadUrl(text.toString())
                    true
                } else false
            }
        }

        val goButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setOnClickListener { loadUrl(urlInput.text.toString()) }
        }

        addressBar.addView(backButton)
        addressBar.addView(forwardButton)
        addressBar.addView(refreshButton)
        addressBar.addView(urlInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addressBar.addView(goButton)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            webViewClient = SafeWebViewClient()
            webChromeClient = SafeWebChromeClient()
        }

        layout.addView(addressBar)
        layout.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        ))

        setContentView(layout)

        if (savedInstanceState == null) {
            webView.loadUrl("https://www.google.com")
        }
    }

    private fun loadBlocklist() {
        val cachedDomains = prefs.getStringSet("cached_domains", null)
        val cachedKeywords = prefs.getStringSet("cached_keywords", null)
        val cachedVideo = prefs.getBoolean("cached_video_blocking", true)

        blockedDomains = if (cachedDomains != null) {
            cachedDomains.toMutableList()
        } else {
            fallbackBlockedDomains.toMutableList()
        }

        blockedKeywords = if (cachedKeywords != null) {
            cachedKeywords.toMutableList()
        } else {
            mutableListOf()
        }

        videoBlocking = cachedVideo
        Log.d(TAG, "Loaded ${blockedDomains.size} blocked domains")
    }

    private fun saveBlocklist(domains: List<String>, keywords: List<String>, video: Boolean) {
        prefs.edit()
            .putStringSet("cached_domains", domains.toSet())
            .putStringSet("cached_keywords", keywords.toSet())
            .putBoolean("cached_video_blocking", video)
            .apply()
    }

    private fun fetchBlocklist() {
        executor.execute {
            try {
                val url = URL(REMOTE_CONFIG_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    parseBlocklist(response)
                } else {
                    Log.w(TAG, "Server returned $responseCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch blocklist", e)
            }
        }
    }

    private fun parseBlocklist(jsonResponse: String) {
        try {
            val config = gson.fromJson(jsonResponse, BlocklistConfig::class.java)
            if (config.blockedDomains != null && config.blockedDomains.isNotEmpty()) {
                val domains = config.blockedDomains.toMutableList()
                val keywords = (config.blockedKeywords ?: emptyList()).toMutableList()
                val video = config.videoBlocking ?: true

                handler.post {
                    blockedDomains = domains
                    blockedKeywords = keywords
                    videoBlocking = video
                    saveBlocklist(domains, keywords, video)
                    Toast.makeText(this, "Blocklist updated: ${domains.size} domains", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse blocklist", e)
        }
    }

    private fun startPeriodicRefresh() {
        refreshRunnable = Runnable {
            fetchBlocklist()
            handler.postDelayed(refreshRunnable!!, REFRESH_INTERVAL_MS)
        }
        handler.postDelayed(refreshRunnable!!, REFRESH_INTERVAL_MS)
    }

    private fun loadUrl(input: String) {
        val url = when {
            input.isBlank() -> return
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }
        webView.loadUrl(url)
    }

    private fun isBlocked(url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (blockedDomains.any { lowerUrl.contains(it.lowercase()) }) return true
        if (blockedKeywords.any { lowerUrl.contains(it.lowercase()) }) return true
        if (videoExtensions.any { lowerUrl.endsWith(it, ignoreCase = true) }) return true
        return false
    }

    private fun getBlockedHtml(): String {
        return "<html><head><title>Access Blocked</title><style>body{font-family:Arial,sans-serif;text-align:center;padding-top:100px;background:#f5f5f5;}.block-icon{font-size:80px;color:#d32f2f;}h1{color:#d32f2f;}p{color:#666;font-size:18px;}.back-link{display:inline-block;margin-top:30px;padding:12px 24px;background:#1976d2;color:white;text-decoration:none;border-radius:4px;}</style></head><body><div class=\"block-icon\">&#128683;</div><h1>Access Blocked</h1><p>This website or content has been blocked by your administrator.</p><a href=\"https://www.google.com\" class=\"back-link\">Go back to Google</a></body></html>"
    }

    private fun getVideoBlockScript(): String {
        if (!videoBlocking) return ""
        return """
            (function() {
                function killVideos() {
                    var videos = document.querySelectorAll('video, audio');
                    videos.forEach(function(v) {
                        v.pause(); v.src = ''; v.load(); v.remove();
                    });
                    var iframes = document.querySelectorAll('iframe');
                    iframes.forEach(function(f) {
                        var src = f.src.toLowerCase();
                        if(src.includes('youtube') || src.includes('youtu.be') ||
                           src.includes('vimeo') || src.includes('dailymotion') ||
                           src.includes('twitch') || src.includes('tiktok') ||
                           src.includes('netflix') || src.includes('primevideo') ||
                           src.includes('disney') || src.includes('hulu')) {
                            f.src = 'about:blank'; f.remove();
                        }
                    });
                    var selectors = ['[class*="video"]','[class*="player"]','[class*="stream"]','[id*="video"]','[id*="player"]','[id*="stream"]','.ytp-player','.html5-video-player','.video-js','.jwplayer','.plyr','.mejs__container'];
                    selectors.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) { el.style.display = 'none'; });
                    });
                }
                killVideos();
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if(node.tagName === 'VIDEO' || node.tagName === 'AUDIO' || node.tagName === 'IFRAME') {
                                killVideos();
                            }
                            if(node.querySelectorAll) {
                                if(node.querySelectorAll('video, audio, iframe').length > 0) killVideos();
                            }
                        });
                    });
                });
                observer.observe(document.body, { childList: true, subtree: true });
                var originalFetch = window.fetch;
                window.fetch = function(url, options) {
                    var urlStr = url.toString().toLowerCase();
                    if(urlStr.includes('.mp4') || urlStr.includes('.webm') || urlStr.includes('.m3u8') || urlStr.includes('.ts') || urlStr.includes('.mkv') || urlStr.includes('.avi') || urlStr.includes('.mov') || urlStr.includes('video') || urlStr.includes('stream') || urlStr.includes('blob:')) {
                        return Promise.reject(new Error('Video blocked'));
                    }
                    return originalFetch(url, options);
                };
                var originalOpen = window.XMLHttpRequest.prototype.open;
                window.XMLHttpRequest.prototype.open = function(method, url) {
                    var urlStr = url.toString().toLowerCase();
                    if(urlStr.includes('.mp4') || urlStr.includes('.webm') || urlStr.includes('.m3u8') || urlStr.includes('.ts') || urlStr.includes('.mkv') || urlStr.includes('.avi') || urlStr.includes('.mov') || urlStr.includes('video') || urlStr.includes('stream')) {
                        return;
                    }
                    return originalOpen.apply(this, arguments);
                };
                var originalSetAttribute = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                    if(this.tagName === 'VIDEO' || this.tagName === 'AUDIO') {
                        if(name === 'src' || name === 'data-src') {
                            var valStr = value.toString().toLowerCase();
                            if(valStr.includes('.mp4') || valStr.includes('.webm') || valStr.includes('.m3u8') || valStr.includes('.ts')) {
                                return;
                            }
                        }
                    }
                    return originalSetAttribute.call(this, name, value);
                };
            })();
        """.trimIndent()
    }

    inner class SafeWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url.toString()
            if (isBlocked(url)) {
                view?.loadDataWithBaseURL(null, getBlockedHtml(), "text/html", "UTF-8", null)
                return true
            }
            urlInput.setText(url)
            return false
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            urlInput.setText(url)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (videoBlocking) view?.evaluateJavascript(getVideoBlockScript(), null)
        }
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            Log.e(TAG, "WebView error: ${'$'}{error?.description}")
        }
    }

    inner class SafeWebChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            Toast.makeText(this@MainActivity, "Fullscreen video is blocked", Toast.LENGTH_SHORT).show()
        }
        override fun onHideCustomView() {}
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (videoBlocking && newProgress > 50) view?.evaluateJavascript(getVideoBlockScript(), null)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshRunnable?.let { handler.removeCallbacks(it) }
        executor.shutdown()
    }
}
