package com.yourcompany.safebrowser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.yourcompany.safebrowser.BlocklistConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SafeBrowser"
        private const val REMOTE_CONFIG_URL = "https://yourserver.com/blocklist.json"
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
        // If onPageFinished doesn't arrive in this long, stop spinning and let the user act.
        private const val LOAD_WATCHDOG_MS = 20_000L
    }

    private lateinit var prefs: SharedPreferences
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var refreshRunnable: Runnable? = null
    private var loadWatchdogRunnable: Runnable? = null
    private val tabCounter = AtomicInteger(0)

    private val tabs = mutableListOf<TabData>()
    private var activeTabId: Int = -1
    private lateinit var tabContainer: HorizontalScrollView
    private lateinit var tabLayout: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var refreshBtn: ImageView
    private var isLoading = false

    // Per-page-load guard so the video-blocking script isn't re-injected on every progress tick.
    private var lastVideoScriptInjectedForUrl: String? = null

    // File upload (issue 7)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private var blockedDomains = mutableListOf<String>()
    private var blockedKeywords = mutableListOf<String>()
    private var videoBlocking = true
    private val videoExtensions = listOf(".mp4", ".webm", ".m3u8", ".ts", ".mkv", ".avi", ".mov", ".flv", ".wmv", ".3gp")
    private val fallbackBlockedDomains = listOf(
        "youtube.com", "youtu.be", "facebook.com", "fb.com",
        "tiktok.com", "netflix.com", "primevideo.com", "disneyplus.com",
        "hulu.com", "twitch.tv", "vimeo.com", "dailymotion.com",
        "twitter.com", "x.com", "instagram.com", "snapchat.com",
        "reddit.com", "9gag.com", "bilibili.com", "tiktokcdn.com"
    )

    private lateinit var domainTracker: DomainTracker

    /** Convert dp to px so touch targets are a consistent physical size on every screen density. */
    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("SafeBrowserPrefs", Context.MODE_PRIVATE)
        domainTracker = DomainTracker(this)

        // Must be registered before STARTED state, so do it in onCreate (issue 7: uploads/file chooser).
        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            callback.onReceiveValue(results)
        }

        loadBlocklist()
        fetchBlocklist()
        setupChromeUI()
        createNewTab("https://www.google.com")
        startPeriodicRefresh()
    }

    private fun setupChromeUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            elevation = dp(2).toFloat()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        tabContainer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabContainer.addView(tabLayout)
        topRow.addView(tabContainer)

        val newTabBtn = createIconButton(android.R.drawable.ic_input_add) { createNewTab("https://www.google.com") }
        topRow.addView(newTabBtn)

        val menuBtn = createIconButton(android.R.drawable.ic_menu_more) { showMenu() }
        topRow.addView(menuBtn)

        toolbar.addView(topRow)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }

        val backBtn = createIconButton(android.R.drawable.ic_media_previous) {
            getActiveWebView()?.goBack()
        }

        val forwardBtn = createIconButton(android.R.drawable.ic_media_next) {
            getActiveWebView()?.goForward()
        }

        // Doubles as Stop while a page is loading (issue 6: don't get trapped on a stalled page).
        refreshBtn = createIconButton(android.R.drawable.ic_popup_sync) {
            if (isLoading) {
                getActiveWebView()?.stopLoading()
                setLoadingState(false)
            } else {
                getActiveWebView()?.reload()
            }
        }

        val homeBtn = createIconButton(android.R.drawable.ic_menu_compass) {
            loadUrlInActiveTab("https://www.google.com")
        }

        val addressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(6), dp(2))
            val shape = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F1F3F4"))
            }
            background = shape
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }

        val lockIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_idle_lock)
            setColorFilter(Color.parseColor("#5F6368"))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(0, 0, dp(6), 0) }
        }
        addressBar.addView(lockIcon)

        urlInput = EditText(this).apply {
            hint = "Search or type URL"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#202124"))
            setHintTextColor(Color.parseColor("#9AA0A6"))
            textSize = 15f
            maxLines = 1
            isSingleLine = true
            setPadding(dp(4), 0, dp(4), 0)
            // issue 5: explicitly request a GO action key so the keyboard's Enter/Search button works.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnEditorActionListener { _, actionId, event ->
                val isGoPress = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
                if (isGoPress) {
                    loadUrlInActiveTab(text.toString())
                    hideKeyboard()
                    true
                } else false
            }
            // issue 2: select-all on first tap. Doing this synchronously inside the click/focus
            // callback can lose the race with the cursor-placement the EditText itself performs
            // on the same tap, so we push it to the next UI loop tick instead.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    post { selectAll() }
                } else {
                    hideKeyboard()
                }
            }
            setOnClickListener {
                if (!hasFocus()) requestFocus()
                post { selectAll() }
            }
        }
        addressBar.addView(urlInput)

        val goBtn = createIconButton(android.R.drawable.ic_menu_search, sizeDp = 36) {
            loadUrlInActiveTab(urlInput.text.toString())
            hideKeyboard()
        }
        addressBar.addView(goBtn)

        addressRow.addView(backBtn)
        addressRow.addView(forwardBtn)
        addressRow.addView(refreshBtn)
        addressRow.addView(homeBtn)
        addressRow.addView(addressBar)

        toolbar.addView(addressRow)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
            visibility = View.GONE
            isIndeterminate = false
        }
        toolbar.addView(progressBar)

        rootLayout.addView(toolbar)

        webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }
        rootLayout.addView(webViewContainer)

        setContentView(rootLayout)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    /**
     * issue 1: buttons were sized in raw pixels (40px/36px), which on high-density screens
     * renders well under Android's 48dp minimum touch target -- that's why they were so hard
     * to tap. Everything now goes through dp() and gets a visible ripple so taps register and
     * give feedback.
     */
    private fun createIconButton(iconRes: Int, sizeDp: Int = 48, onClick: () -> Unit): ImageView {
        val touchSize = dp(maxOf(sizeDp, 48)) // never shrink below the accessibility minimum
        val iconInset = dp((maxOf(sizeDp, 48) - 24) / 2)
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#5F6368"))
            layoutParams = LinearLayout.LayoutParams(touchSize, touchSize).apply { setMargins(dp(2), 0, dp(2), 0) }
            setPadding(iconInset, iconInset, iconInset, iconInset)
            isClickable = true
            isFocusable = true
            if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
    }

    private fun createNewTab(url: String) {
        val tabId = tabCounter.incrementAndGet()
        val webView = createWebView()
        val tabData = TabData(tabId, url, "New Tab", null, webView)
        tabs.add(tabData)
        webViewContainer.addView(webView)
        switchToTab(tabId)
        loadUrlInWebView(webView, url)
        updateTabUI()
    }

    private fun createWebView(): WebView {
        return WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                databaseEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                // issue 3: allow file:// access so saved downloads can be opened back in-page if needed.
                allowFileAccess = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = SafeWebViewClient()
            webChromeClient = SafeWebChromeClient()
            // issue 3: downloads silently did nothing because no DownloadListener was ever attached.
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                startDownload(url, contentDisposition, mimeType)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            request.setMimeType(mimeType)
            request.addRequestHeader("User-Agent", getActiveWebView()?.settings?.userAgentString ?: "")
            request.setDescription("Downloading file...")
            request.setTitle(fileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Toast.makeText(this, "Couldn't start download", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToTab(tabId: Int) {
        activeTabId = tabId
        tabs.forEach { tab ->
            tab.webView?.visibility = if (tab.id == tabId) View.VISIBLE else View.GONE
        }
        val activeTab = tabs.find { it.id == tabId }
        activeTab?.let {
            urlInput.setText(it.url)
        }
        updateTabUI()
    }

    private fun closeTab(tabId: Int) {
        val tab = tabs.find { it.id == tabId } ?: return
        tab.webView?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            webViewContainer.removeView(it)
            it.destroy()
        }
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            createNewTab("https://www.google.com")
        } else {
            switchToTab(tabs.last().id)
        }
        updateTabUI()
    }

    private fun updateTabUI() {
        tabLayout.removeAllViews()
        tabs.forEach { tab ->
            val isActive = tab.id == activeTabId
            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(6))
                val shape = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (isActive) Color.parseColor("#D2E3FC") else Color.parseColor("#F1F3F4"))
                }
                background = shape
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply { setMargins(dp(4), 0, dp(4), 0) }
                isClickable = true
                setOnClickListener { switchToTab(tab.id) }
            }

            val titleView = TextView(this).apply {
                text = if (tab.title.length > 15) tab.title.substring(0, 15) + "..." else tab.title
                textSize = 13f
                setTextColor(Color.parseColor("#202124"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            }
            tabView.addView(titleView)

            if (tabs.size > 1) {
                val closeBtn = TextView(this).apply {
                    text = "\u2715"
                    textSize = 14f
                    setTextColor(Color.parseColor("#5F6368"))
                    // A real touch target rather than relying on the text glyph's tiny hitbox.
                    minWidth = dp(32)
                    minHeight = dp(32)
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { closeTab(tab.id) }
                }
                tabView.addView(closeBtn)
            }

            tabLayout.addView(tabView)
        }
    }

    private fun getActiveWebView(): WebView? {
        return tabs.find { it.id == activeTabId }?.webView
    }

    private fun getActiveTab(): TabData? {
        return tabs.find { it.id == activeTabId }
    }

    private fun loadUrlInActiveTab(input: String) {
        if (input.isBlank()) return
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(input)
        }
        val webView = getActiveWebView() ?: return
        loadUrlInWebView(webView, url)
    }

    private fun loadUrlInWebView(webView: WebView, url: String) {
        if (isBlocked(url)) {
            webView.loadDataWithBaseURL(null, getBlockedHtml(), "text/html", "UTF-8", null)
            urlInput.setText(url)
            return
        }
        // issue 4: show the loading indicator the instant the user acts, instead of waiting
        // for onPageStarted, which can lag behind a tap by several seconds on a slow link
        // (DNS lookup / TCP connect happen before that callback fires).
        setLoadingState(true)
        webView.loadUrl(url)
    }

    private fun setLoadingState(loading: Boolean) {
        isLoading = loading
        loadWatchdogRunnable?.let { handler.removeCallbacks(it) }
        if (loading) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            refreshBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            // issue 6: if a page never finishes (dead link, stalled connection), don't leave
            // the user stuck -- auto-recover the UI so other taps work again after a timeout.
            loadWatchdogRunnable = Runnable {
                if (isLoading) {
                    setLoadingState(false)
                    Toast.makeText(this, "Taking a while - tap reload or try another link", Toast.LENGTH_SHORT).show()
                }
            }
            handler.postDelayed(loadWatchdogRunnable!!, LOAD_WATCHDOG_MS)
        } else {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            progressBar.visibility = View.GONE
            refreshBtn.setImageResource(android.R.drawable.ic_popup_sync)
        }
    }

    private fun showMenu() {
        val popup = PopupMenu(this, findViewById(android.R.id.content))
        popup.menu.add("Tracked Domains: " + domainTracker.getTrackedCount())
        popup.menu.add("Upload Domains Now").setOnMenuItemClickListener {
            domainTracker.uploadDomains()
            Toast.makeText(this, "Uploading domains...", Toast.LENGTH_SHORT).show()
            true
        }
        popup.menu.add("Refresh Blocklist").setOnMenuItemClickListener {
            fetchBlocklist()
            Toast.makeText(this, "Refreshing blocklist...", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun loadBlocklist() {
        val cachedDomains = prefs.getStringSet("cached_domains", null)
        val cachedKeywords = prefs.getStringSet("cached_keywords", null)
        val cachedVideo = prefs.getBoolean("cached_video_blocking", true)
        blockedDomains = if (cachedDomains != null) cachedDomains.toMutableList() else fallbackBlockedDomains.toMutableList()
        blockedKeywords = if (cachedKeywords != null) cachedKeywords.toMutableList() else mutableListOf()
        videoBlocking = cachedVideo
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
                    Toast.makeText(this, "Blocklist updated: " + domains.size + " domains", Toast.LENGTH_SHORT).show()
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
                    videos.forEach(function(v) { v.pause(); v.src = ""; v.load(); v.remove(); });
                    var iframes = document.querySelectorAll("iframe");
                    iframes.forEach(function(f) {
                        var src = f.src.toLowerCase();
                        if(src.includes("youtube") || src.includes("youtu.be") || src.includes("vimeo") || src.includes("dailymotion") || src.includes("twitch") || src.includes("tiktok") || src.includes("netflix") || src.includes("primevideo") || src.includes("disney") || src.includes("hulu")) {
                            f.src = "about:blank"; f.remove();
                        }
                    });
                    var selectors = ["[class*=\"video\"]","[class*=\"player\"]","[class*=\"stream\"]","[id*=\"video\"]","[id*=\"player\"]","[id*=\"stream\"]",".ytp-player",".html5-video-player",".video-js",".jwplayer",".plyr",".mejs__container"];
                    selectors.forEach(function(sel) { document.querySelectorAll(sel).forEach(function(el) { el.style.display = "none"; }); });
                }
                killVideos();
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if(node.tagName === "VIDEO" || node.tagName === "AUDIO" || node.tagName === "IFRAME") killVideos();
                            if(node.querySelectorAll && node.querySelectorAll("video, audio, iframe").length > 0) killVideos();
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
                    if(urlStr.includes(".mp4") || urlStr.includes(".webm") || urlStr.includes(".m3u8") || urlStr.includes(".ts") || urlStr.includes(".mkv") || urlStr.includes(".avi") || urlStr.includes(".mov") || urlStr.includes("video") || urlStr.includes("stream")) return;
                    return originalOpen.apply(this, arguments);
                };
                var originalSetAttribute = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                    if(this.tagName === "VIDEO" || this.tagName === "AUDIO") {
                        if(name === "src" || name === "data-src") {
                            var valStr = value.toString().toLowerCase();
                            if(valStr.includes(".mp4") || valStr.includes(".webm") || valStr.includes(".m3u8") || valStr.includes(".ts")) return;
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
            // issue 4: tapping a link inside a page doesn't go through loadUrlInWebView(),
            // so show the indicator here too instead of waiting on onPageStarted.
            setLoadingState(true)
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            setLoadingState(true)
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            urlInput.setText(url)
            getActiveTab()?.let {
                it.url = url ?: ""
                it.favicon = favicon
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            setLoadingState(false)
            view?.title?.let { title ->
                getActiveTab()?.title = title
                updateTabUI()
            }
            if (videoBlocking && url != null && url != lastVideoScriptInjectedForUrl) {
                lastVideoScriptInjectedForUrl = url
                view?.evaluateJavascript(getVideoBlockScript(), null)
            }
            url?.let { domainTracker.trackDomain(it) }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            // issue 6: a failed/cancelled load used to leave the progress bar spinning forever
            // with no way out except killing the app.
            setLoadingState(false)
            Log.e(TAG, "WebView error: ${error?.description}")
        }
    }

    inner class SafeWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (isLoading) {
                progressBar.isIndeterminate = false
                progressBar.progress = newProgress
            }
            // Video-block injection now happens once in onPageFinished (and once eagerly here at
            // the halfway point to catch sites that play media before finishing load) instead of
            // on every single progress tick, which was spamming evaluateJavascript and causing
            // jank on slow connections (part of issue 6).
            if (newProgress in 50..55 && videoBlocking) {
                val currentUrl = view?.url
                if (currentUrl != null && currentUrl != lastVideoScriptInjectedForUrl) {
                    view?.evaluateJavascript(getVideoBlockScript(), null)
                }
            }
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            Toast.makeText(this@MainActivity, "Fullscreen video is blocked", Toast.LENGTH_SHORT).show()
        }

        override fun onHideCustomView() {}

        // issue 7: file uploads (e.g. "Choose File" inputs) did nothing because this was
        // never overridden, so the WebView had no way to hand control back after a pick.
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Could not open file chooser", e)
                fileChooserCallback = null
                false
            }
        }
    }

    override fun onBackPressed() {
        val activeWebView = getActiveWebView()
        if (activeWebView?.canGoBack() == true) {
            activeWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshRunnable?.let { handler.removeCallbacks(it) }
        loadWatchdogRunnable?.let { handler.removeCallbacks(it) }
        executor.shutdown()
    }
}
