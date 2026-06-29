package com.readinghub.safebrowser

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SafeBrowser"
        private const val REMOTE_CONFIG_URL = "https://a.proxybotkk.workers.dev"
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
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F3F4"))
        }

        // === TOP BAR (Chrome-like) ===
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 12)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            elevation = 4f
        }

        // Back button
        val backBtn = createNavButton(android.R.drawable.ic_media_previous, "Back")
        backBtn.setOnClickListener { if (webView.canGoBack()) webView.goBack() }

        // Forward button
        val forwardBtn = createNavButton(android.R.drawable.ic_media_next, "Forward")
        forwardBtn.setOnClickListener { if (webView.canGoForward()) webView.goForward() }

        // Refresh button
        val refreshBtn = createNavButton(android.R.drawable.ic_popup_sync, "Refresh")
        refreshBtn.setOnClickListener { webView.reload() }

        // Address bar container (rounded like Chrome)
        val addressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 6, 12, 6)
            setBackgroundColor(Color.parseColor("#F1F3F4"))
            val shape = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#FFFFFF"))
            }
            background = shape
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
        }

        // Security icon (lock)
        val lockIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_idle_lock)
            setColorFilter(Color.parseColor("#5F6368"))
            layoutParams = LinearLayout.LayoutParams(36, 36)
        }
        addressContainer.addView(lockIcon)

        // URL input field
        urlInput = EditText(this).apply {
            hint = "Search or type URL"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#202124"))
            setHintTextColor(Color.parseColor("#9AA0A6"))
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            setPadding(8, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                    loadUrl(text.toString())
                    true
                } else false
            }
        }
        addressContainer.addView(urlInput)

        // Go button (magnifying glass)
        val goBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.parseColor("#5F6368"))
            layoutParams = LinearLayout.LayoutParams(36, 36)
            setOnClickListener { loadUrl(urlInput.text.toString()) }
        }
        addressContainer.addView(goBtn)

        // Assemble top bar
        topBar.addView(backBtn)
        topBar.addView(forwardBtn)
        topBar.addView(refreshBtn)
        topBar.addView(addressContainer)

        // === PROGRESS BAR ===
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3)
            progressDrawable = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.progress_horizontal)
            visibility = View.GONE
        }
        (progressBar.progressDrawable as? android.graphics.drawable.LayerDrawable)?.getDrawable(1)?.setColorFilter(
            Color.parseColor("#1A73E8"), android.graphics.PorterDuff.Mode.SRC_IN
        )

        // === WEBVIEW ===
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = SafeWebViewClient(progressBar)
            webChromeClient = SafeWebChromeClient(progressBar)
        }

        // Assemble root layout
        rootLayout.addView(topBar)
        rootLayout.addView(progressBar)
        rootLayout.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        ))

        setContentView(rootLayout)
        webView.loadUrl("https://www.google.com")
    }

    private fun createNavButton(iconRes: Int, contentDesc: String): ImageView {
        return ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#5F6368"))
            layoutParams = LinearLayout.LayoutParams(44, 44).apply {
                setMargins(4, 0, 4, 0)
            }
            setPadding(8, 8, 8, 8)
            contentDescription = contentDesc
        }
    }

    // === FIXED: Check blocking when loading from address bar ===
    private fun loadUrl(input: String) {
        if (input.isBlank()) return

        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }

        // CHECK IF BLOCKED BEFORE LOADING
        if (isBlocked(url)) {
            webView.loadDataWithBaseURL(null, getBlockedHtml(), "text/html", "UTF-8", null)
            urlInput.setText(url)
            return
        }

        webView.loadUrl(url)
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
                    var videos = document.querySelectorAll("video, audio");
                    videos.forEach(function(v) {
                        v.pause(); v.src = ""; v.load(); v.remove();
                    });
                    var iframes = document.querySelectorAll("iframe");
                    iframes.forEach(function(f) {
                        var src = f.src.toLowerCase();
                        if(src.includes("youtube") || src.includes("youtu.be") ||
                           src.includes("vimeo") || src.includes("dailymotion") ||
                           src.includes("twitch") || src.includes("tiktok") ||
                           src.includes("netflix") || src.includes("primevideo") ||
                           src.includes("disney") || src.includes("hulu")) {
                            f.src = "about:blank"; f.remove();
                        }
                    });
                    var selectors = ["[class*=\"video\"]","[class*=\"player\"]","[class*=\"stream\"]","[id*=\"video\"]","[id*=\"player\"]","[id*=\"stream\"]",".ytp-player",".html5-video-player",".video-js",".jwplayer",".plyr",".mejs__container"];
                    selectors.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) { el.style.display = "none"; });
                    });
                }
                killVideos();
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if(node.tagName === "VIDEO" || node.tagName === "AUDIO" || node.tagName === "IFRAME") {
                                killVideos();
                            }
                            if(node.querySelectorAll) {
                                if(node.querySelectorAll("video, audio, iframe").length > 0) killVideos();
                            }
                        });
                    });
                });
                observer.observe(document.body, { childList: true, subtree: true });
                var originalFetch = window.fetch;
                window.fetch = function(url, options) {
                    var urlStr = url.toString().toLowerCase();
                    if(urlStr.includes(".mp4") || urlStr.includes(".webm") || urlStr.includes(".m3u8") || urlStr.includes(".ts") || urlStr.includes(".mkv") || urlStr.includes(".avi") || urlStr.includes(".mov") || urlStr.includes("video") || urlStr.includes("stream") || urlStr.includes("blob:")) {
                        return Promise.reject(new Error("Video blocked"));
                    }
                    return originalFetch(url, options);
                };
                var originalOpen = window.XMLHttpRequest.prototype.open;
                window.XMLHttpRequest.prototype.open = function(method, url) {
                    var urlStr = url.toString().toLowerCase();
                    if(urlStr.includes(".mp4") || urlStr.includes(".webm") || urlStr.includes(".m3u8") || urlStr.includes(".ts") || urlStr.includes(".mkv") || urlStr.includes(".avi") || urlStr.includes(".mov") || urlStr.includes("video") || urlStr.includes("stream")) {
                        return;
                    }
                    return originalOpen.apply(this, arguments);
                };
                var originalSetAttribute = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                    if(this.tagName === "VIDEO" || this.tagName === "AUDIO") {
                        if(name === "src" || name === "data-src") {
                            var valStr = value.toString().toLowerCase();
                            if(valStr.includes(".mp4") || valStr.includes(".webm") || valStr.includes(".m3u8") || valStr.includes(".ts")) {
                                return;
                            }
                        }
                    }
                    return originalSetAttribute.call(this, name, value);
                };
            })();
        """.trimIndent()
    }

    // === WebViewClient with progress bar ===
    inner class SafeWebViewClient(private val progressBar: ProgressBar) : WebViewClient() {

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
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            urlInput.setText(url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            progressBar.progress = 100
            if (videoBlocking) view?.evaluateJavascript(getVideoBlockScript(), null)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            progressBar.visibility = View.GONE
            Log.e(TAG, "WebView error: ${error?.description}")
        }
    }

    // === WebChromeClient with progress bar ===
    inner class SafeWebChromeClient(private val progressBar: ProgressBar) : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            progressBar.progress = newProgress
            if (newProgress > 50 && videoBlocking) {
                view?.evaluateJavascript(getVideoBlockScript(), null)
            }
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            Toast.makeText(this@MainActivity, "Fullscreen video is blocked", Toast.LENGTH_SHORT).show()
        }

        override fun onHideCustomView() {}
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
