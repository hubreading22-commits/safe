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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
    private lateinit var downloadStore: DownloadStore

    /** Convert dp to px so touch targets are a consistent physical size on every screen density. */
    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("SafeBrowserPrefs", Context.MODE_PRIVATE)
        domainTracker = DomainTracker(this)
        downloadStore = DownloadStore(this)

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

        val menuBtn = createIconButton(android.R.drawable.ic_menu_more) { }
        menuBtn.setOnClickListener { showMenu(menuBtn) }
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
            // Stronger video blocking: the old approach injected JS after onPageFinished /
            // mid-progress, by which point the page's own scripts (and the player they build)
            // had often already run. addDocumentStartJavaScript runs our script before any
            // page script, on every navigation including iframes, so sites like flyflix can't
            // win the race by starting playback before our blocking code exists.
            if (videoBlocking && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, getVideoBlockScript(), setOf("*"))
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
            // issue 5: everything used to land in DIRECTORY_DOWNLOADS regardless of type. Route
            // images/video/audio/docs into the matching public folder, like a normal browser does.
            val folder = publicFolderFor(mimeType, fileName)
            request.setDestinationInExternalPublicDir(folder, fileName)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            // issue 2: nothing was ever recorded anywhere, so there was no way to see past
            // downloads in-app. Persist it and let DownloadsActivity list/open/delete them.
            downloadStore.add(
                DownloadRecord(
                    downloadId = downloadId,
                    fileName = fileName,
                    mimeType = mimeType ?: "*/*",
                    folder = folder,
                    timestamp = System.currentTimeMillis(),
                    sourceUrl = url
                )
            )
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Toast.makeText(this, "Couldn't start download", Toast.LENGTH_SHORT).show()
        }
    }

    private fun publicFolderFor(mimeType: String?, fileName: String): String {
        val mime = (mimeType ?: "").lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic") ->
                Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp") ->
                Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") || ext in setOf("mp3", "wav", "ogg", "m4a", "flac") ->
                Environment.DIRECTORY_MUSIC
            mime == "application/pdf" || mime.startsWith("application/msword") ||
                mime.startsWith("application/vnd") || ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv") ->
                Environment.DIRECTORY_DOCUMENTS
            else -> Environment.DIRECTORY_DOWNLOADS
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
            // issue 4 follow-up: a tab switch used to leave the OLD tab's loading/progress state
            // showing even though we just navigated the UI onto a different tab entirely.
            setLoadingState(it.isLoading)
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

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Downloads").setOnMenuItemClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
            true
        }
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
            // issue: previously the whole update (including keywords) was gated behind
            // "domains is non-empty", so a config with keywords but no domains was silently
            // dropped. Domains and keywords are now applied independently.
            val domains = (config.blockedDomains ?: emptyList()).toMutableList()
            val keywords = (config.blockedKeywords ?: emptyList()).toMutableList()
            val video = config.videoBlocking ?: true
            if (domains.isNotEmpty() || keywords.isNotEmpty()) {
                handler.post {
                    if (domains.isNotEmpty()) blockedDomains = domains
                    if (keywords.isNotEmpty()) blockedKeywords = keywords
                    videoBlocking = video
                    saveBlocklist(
                        if (domains.isNotEmpty()) domains else blockedDomains,
                        if (keywords.isNotEmpty()) keywords else blockedKeywords,
                        video
                    )
                    Toast.makeText(
                        this,
                        "Blocklist updated: ${blockedDomains.size} domains, ${blockedKeywords.size} keywords",
                        Toast.LENGTH_SHORT
                    ).show()
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

    /**
     * Was doing a raw lowerUrl.contains(it) for both domains and keywords, which is a substring
     * match over the *entire* URL -- not a real domain check or a real word check. That's why
     * things outside the configured lists were getting blocked:
     *   - domain "x.com" as a substring matches the end of "netflix.com", "box.com",
     *     "xerox.com", etc. (any host that happens to end in those 5 characters).
     *   - keyword "ig" as a substring matches "log**ig**n", "des**ig**n", "or**ig**inal",
     *     "d**ig**ital" -- virtually any page, since "ig" appears inside countless ordinary
     *     words/paths/query strings.
     * Domains now match on the actual host (exact host or a subdomain of it), and keywords now
     * require word boundaries so "ig" only matches the standalone word "ig", not letters buried
     * inside another word.
     */
    private fun isBlocked(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val host = try { Uri.parse(url).host?.lowercase()?.removePrefix("www.") } catch (e: Exception) { null }

        if (host != null && blockedDomains.any { domain ->
                val d = domain.lowercase().removePrefix("www.")
                host == d || host.endsWith(".$d")
            }) return true

        if (blockedKeywords.any { keyword ->
                val k = Regex.escape(keyword.lowercase())
                Regex("(?:^|[^a-z0-9])$k(?:[^a-z0-9]|$)").containsMatchIn(lowerUrl)
            }) return true

        if (videoExtensions.any { lowerUrl.endsWith(it, ignoreCase = true) }) return true
        return false
    }

    private fun getBlockedHtml(): String {
        return "<html><head><title>Access Blocked</title><style>body{font-family:Arial,sans-serif;text-align:center;padding-top:100px;background:#f5f5f5;}.block-icon{font-size:80px;color:#d32f2f;}h1{color:#d32f2f;}p{color:#666;font-size:18px;}.back-link{display:inline-block;margin-top:30px;padding:12px 24px;background:#1976d2;color:white;text-decoration:none;border-radius:4px;}</style></head><body><div class=\"block-icon\">&#128683;</div><h1>Access Blocked</h1><p>This website or content has been blocked by your administrator.</p><a href=\"https://www.google.com\" class=\"back-link\">Go back to Google</a></body></html>"
    }

    /**
     * Chrome-style "this site can't be reached" page, shown when a real page load fails
     * (DNS failure, no connection, timeout, refused connection, etc.) instead of leaving the
     * WebView stuck on a blank/half-loaded screen with no way forward. The "Try Again" link
     * re-requests the same URL, which goes through the normal navigation path (and so will
     * correctly show the loading indicator again).
     */
    private fun getErrorHtml(failingUrl: String?, description: String?): String {
        val safeUrl = (failingUrl ?: "").replace("\"", "&quot;")
        val reason = description?.takeIf { it.isNotBlank() } ?: "The connection was interrupted"
        return """
            <html><head><title>Page unavailable</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body{font-family:Roboto,Arial,sans-serif;background:#fff;color:#202124;
                    margin:0;padding:48px 24px;}
                .icon{font-size:64px;color:#9aa0a6;text-align:center;}
                h1{font-size:20px;font-weight:400;}
                p{color:#5f6368;font-size:14px;line-height:1.5;}
                .url{word-break:break-all;color:#5f6368;}
                .retry{display:inline-block;margin-top:24px;padding:10px 24px;
                    background:#1a73e8;color:#fff;text-decoration:none;border-radius:4px;
                    font-size:14px;}
            </style></head>
            <body>
                <div class="icon">&#9888;</div>
                <h1>This page isn't available</h1>
                <p>$reason.</p>
                <p class="url">$safeUrl</p>
                <p>Check your internet connection, then try again.</p>
                <a class="retry" href="$safeUrl">Try Again</a>
            </body></html>
        """.trimIndent()
    }

    private fun getVideoBlockScript(): String {
        if (!videoBlocking) return "" ""
        return """
            (function() {
                function neuter(el) {
                    try { if (el.pause) el.pause(); } catch(e) {}
                    try { el.src = ""; el.removeAttribute("src"); if (el.load) el.load(); } catch(e) {}
                    try { el.remove(); } catch(e) {}
                }

                // Block playback at the prototype level: works no matter how the player got
                // built (innerHTML, Shadow DOM, custom player libs), not just elements we
                // happen to spot via MutationObserver after the fact.
                try {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        neuter(this);
                        return Promise.reject(new DOMException("Video blocked", "NotAllowedError"));
                    };
                } catch(e) {}

                // Block src / srcObject assignment so a neutered element can't be re-armed.
                try {
                    var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "src");
                    if (srcDesc && srcDesc.configurable) {
                        Object.defineProperty(HTMLMediaElement.prototype, "src", {
                            get: function() { return ""; },
                            set: function(v) {},
                            configurable: true
                        });
                    }
                } catch(e) {}
                try {
                    var srcObjDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "srcObject");
                    if (srcObjDesc && srcObjDesc.configurable) {
                        Object.defineProperty(HTMLMediaElement.prototype, "srcObject", {
                            get: function() { return null; },
                            set: function(v) {},
                            configurable: true
                        });
                    }
                } catch(e) {}

                // Intercept element creation so video/audio elements are dead on arrival,
                // before any page script gets a working reference to one.
                try {
                    var origCreateElement = document.createElement.bind(document);
                    document.createElement = function(tagName) {
                        var el = origCreateElement(tagName);
                        if (typeof tagName === "string" && /^(video|audio)$/i.test(tagName)) {
                            neuter(el);
                        }
                        return el;
                    };
                } catch(e) {}

                var blockedIframeHosts = ["youtube", "youtu.be", "vimeo", "dailymotion", "twitch", "tiktok", "netflix", "primevideo", "disney", "hulu"];
                function killVideos() {
                    document.querySelectorAll("video, audio").forEach(neuter);
                    document.querySelectorAll("iframe").forEach(function(f) {
                        var src = (f.src || "").toLowerCase();
                        if (blockedIframeHosts.some(function(h) { return src.includes(h); })) {
                            f.src = "about:blank"; f.remove();
                        }
                    });
                }

                function startObserving() {
                    if (document.body) killVideos();
                    var observer = new MutationObserver(function() { killVideos(); });
                    observer.observe(document.documentElement, { childList: true, subtree: true });
                }
                if (document.documentElement) startObserving();
                else document.addEventListener("DOMContentLoaded", startObserving);

                // Network-level block on direct media file / streaming-manifest requests.
                var blockedExt = [".mp4", ".webm", ".m3u8", ".ts", ".mkv", ".mov", ".flv", ".wmv", ".3gp"];
                try {
                    var origFetch = window.fetch;
                    window.fetch = function(url, options) {
                        var s = (url || "").toString().toLowerCase();
                        if (blockedExt.some(function(p) { return s.includes(p); })) {
                            return Promise.reject(new Error("Video blocked"));
                        }
                        return origFetch.apply(this, arguments);
                    };
                } catch(e) {}
                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        var s = (url || "").toString().toLowerCase();
                        if (blockedExt.some(function(p) { return s.includes(p); })) { return; }
                        return origOpen.apply(this, arguments);
                    };
                } catch(e) {}
            })();
        """.trimIndent()
    }

    /**
     * Bug fix (issue 4): every WebViewClient/WebChromeClient callback receives the WebView
     * (`view`) that the event actually happened on. The old code ignored that and always wrote
     * into getActiveTab() / the single shared urlInput / progressBar, regardless of which tab
     * fired the callback. That's exactly the "tab A and tab B swap url/title" bug: if you start
     * a search in a background tab, its onPageStarted/onPageFinished still landed on whatever
     * tab happened to be active at that moment. We now always resolve the *owning* tab from
     * `view` first, update that tab's own data unconditionally, and only touch the visible
     * chrome (urlInput/progressBar/refreshBtn) when that tab is the one currently on screen.
     */
    private fun findTabFor(view: WebView?): TabData? {
        if (view == null) return null
        return tabs.find { it.webView === view }
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
            val tab = findTabFor(view)
            if (tab != null) {
                tab.isLoading = true
                if (tab.id == activeTabId) setLoadingState(true)
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            val tab = findTabFor(view) ?: return
            tab.isLoading = true
            tab.url = url ?: ""
            tab.favicon = favicon
            if (tab.id == activeTabId) {
                setLoadingState(true)
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                urlInput.setText(url)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val tab = findTabFor(view) ?: return
            tab.isLoading = false
            view?.title?.let { title -> tab.title = title }
            updateTabUI()
            if (tab.id == activeTabId) setLoadingState(false)
            if (videoBlocking && url != null && url != lastVideoScriptInjectedForUrl) {
                lastVideoScriptInjectedForUrl = url
                view?.evaluateJavascript(getVideoBlockScript(), null)
            }
            url?.let { domainTracker.trackDomain(it) }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            val tab = findTabFor(view)
            tab?.isLoading = false
            if (tab == null || tab.id == activeTabId) setLoadingState(false)
            Log.e(TAG, "WebView error: ${error?.description}")
            // issue: a failed sub-resource (favicon, ad/tracker script, analytics beacon, etc.)
            // used to fire this same callback as a failed *page* load, so the WebView was left
            // stuck on a blank/half-rendered screen for something the user never even asked to
            // navigate to. Only treat it as a page failure -- and show an error page for it --
            // when it's the actual top-level navigation that failed.
            if (request != null && !request.isForMainFrame) return
            val failingUrl = request?.url?.toString() ?: view?.url
            view?.loadDataWithBaseURL(
                null,
                getErrorHtml(failingUrl, error?.description?.toString()),
                "text/html", "UTF-8", null
            )
            if (tab != null) {
                tab.url = failingUrl ?: tab.url
                if (tab.id == activeTabId) urlInput.setText(failingUrl)
            }
        }
    }

    inner class SafeWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            val tab = findTabFor(view)
            if (tab != null && tab.id == activeTabId && isLoading) {
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
