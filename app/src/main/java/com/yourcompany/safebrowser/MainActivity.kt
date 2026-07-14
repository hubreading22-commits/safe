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
import android.os.Message
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
        private const val REMOTE_CONFIG_URL = "https://browser.proxybotkk.workers.dev/api/config"
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
        // If onPageFinished doesn't arrive in this long, stop spinning and let the user act.
        private const val LOAD_WATCHDOG_MS = 20_000L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var blocklistPrefs: SharedPreferences
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var refreshRunnable: Runnable? = null
    private var loadWatchdogRunnable: Runnable? = null
    private val tabCounter = AtomicInteger(0)

    private val tabs = mutableListOf<TabData>()
    private var activeTabId: Int = -1
    private val allowedSslDomains = mutableSetOf<String>()
    private lateinit var tabContainer: HorizontalScrollView
    private lateinit var tabLayout: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var refreshBtn: ImageView
    private lateinit var downloadPopupBar: LinearLayout
    private lateinit var downloadPopupText: TextView
    private var downloadPopupDismissRunnable: Runnable? = null
    private var isLoading = false
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var progressAnimator: android.animation.ObjectAnimator? = null
    private var progressShowRunnable: Runnable? = null
    private lateinit var toolbar: LinearLayout
    private lateinit var addressRow: LinearLayout
        
    // Per-page-load guard so the video-blocking script isn't re-injected on every progress tick.
    private var lastVideoScriptInjectedForUrl: String? = null

    // File upload (issue 7)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private var blockedDomains = mutableListOf<String>()
    private var blockedKeywords = mutableListOf<String>()
    private var videoBlocking = true
    private var audioBlocking = true
    private val videoExtensions = listOf(".mp4", ".webm", ".m3u8", ".ts", ".mkv", ".avi", ".mov", ".flv", ".wmv", ".3gp", ".mpd", ".m4v", ".f4v")
    private val audioExtensions = listOf(".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".wma", ".mka", ".opus")
    private val fallbackBlockedDomains = listOf(
        "youtube.com", "youtu.be", "facebook.com", "fb.com",
        "tiktok.com", "netflix.com", "primevideo.com", "disneyplus.com",
        "hulu.com", "twitch.tv", "vimeo.com", "dailymotion.com",
        "twitter.com", "x.com", "instagram.com", "snapchat.com",
        "reddit.com", "9gag.com", "bilibili.com", "tiktokcdn.com"
    )

    private lateinit var domainTracker: DomainTracker
    private lateinit var downloadStore: DownloadStore

    inner class ThemeColorInterface {
        

        @android.webkit.JavascriptInterface
        fun requestUnblock(domain: String) {
            executor.execute {
                try {
                    val url = java.net.URL(REMOTE_CONFIG_URL.replace("/api/config", "/api/unblock"))
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    val json = "{\"domain\":\"$domain\"}"
                    connection.outputStream.use { it.write(json.toByteArray()) }
                    
                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        handler.post {
                            android.widget.Toast.makeText(this@MainActivity, "Unblock request sent for $domain", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    handler.post {
                        android.widget.Toast.makeText(this@MainActivity, "Failed to send request", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun proceedSsl(domain: String) {
            handler.post {
                allowedSslDomains.add(domain)
                // Reload the current active tab which is showing the SSL error page
                val currentTab = tabs.find { it.id == activeTabId }
                currentTab?.webView?.let { webView ->
                    // Extract domain to reload the original HTTPS URL that failed
                    val url = "https://$domain"
                    webView.loadUrl(url)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun isVideoBlocked(): Boolean = videoBlocking

        @android.webkit.JavascriptInterface
        fun isAudioBlocked(): Boolean = audioBlocking
    }

    

    private fun getErrorHtml(errorCode: Int, description: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="theme-color" content="#ffffff">
                <style>
                    body { display: flex; flex-direction: column; padding: 10%; margin: 0; font-family: sans-serif; background: #fff; color: #5F6368; }
                    h2 { color: #202124; margin-top: 40px; }
                    .icon { font-size: 64px; }
                </style>
            </head>
            <body>
                <div class="icon">🦖</div>
                <h2>This site can't be reached</h2>
                <p>ERR_NAME_NOT_RESOLVED ($description)</p>
            </body>
            </html>
        """.trimIndent()
    }



    private var cachedLogoBase64: String? = null
    private fun getAppLogoBase64(): String {
        if (cachedLogoBase64 != null) return cachedLogoBase64!!
        try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else {
                val bmp = android.graphics.Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            cachedLogoBase64 = "data:image/png;base64," + android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            return cachedLogoBase64!!
        } catch (e: Exception) {
            return ""
        }
    }

    private fun getNtpHtml(): String {
        var shortcutsHtml = ""
        try {
            val cachedShortcutsJson = blocklistPrefs.getString("cached_shortcuts", null)
            val shortcuts: List<Shortcut> = if (cachedShortcutsJson != null) {
                val type = object : com.google.gson.reflect.TypeToken<List<Shortcut>>() {}.type
                gson.fromJson(cachedShortcutsJson, type)
            } else {
                val jsonString = assets.open("shortcuts.json").bufferedReader().use { it.readText() }
                val type = object : com.google.gson.reflect.TypeToken<List<Shortcut>>() {}.type
                gson.fromJson(jsonString, type)
            }
            for (shortcut in shortcuts) {
                val host = try { android.net.Uri.parse(shortcut.url).host } catch (e: Exception) { "" }
                val fallbackText = if (shortcut.icon.isNotEmpty()) shortcut.icon else if (shortcut.title.isNotEmpty()) shortcut.title.substring(0, 1).uppercase() else "?"
                
                shortcutsHtml += """
                    <a href="${shortcut.url}" class="shortcut">
                        <div class="shortcut-icon">
                            <img src="https://www.google.com/s2/favicons?domain=$host&sz=64" style="width: 24px; height: 24px; border-radius: 4px;" onerror="this.outerHTML='${fallbackText}'" />
                        </div>
                        <span>${shortcut.title}</span>
                    </a>
                """.trimIndent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load shortcuts.json", e)
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>New Tab</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { 
                        display: flex; flex-direction: column; align-items: center; 
                        min-height: 100vh; overflow: hidden; margin: 0; padding-top: 48px;
                        font-family: 'Google Sans', Roboto, Arial, sans-serif; 
                        background: #F0F0F8; /* Lightened airy background */
                    }
                    .logo-container { margin-bottom: 24px; }
                    .search-box { 
                        display: flex; align-items: center; width: 85%; max-width: 600px; 
                        padding: 14px 24px; border-radius: 30px; 
                        background: #FFFFFF; border: none;
                        box-shadow: 0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.04);
                    }
                    input { 
                        flex: 1; border: none; outline: none; font-size: 16px; 
                        margin-left: 12px; color: #202124; background: transparent;
                    }
                    input::placeholder { color: #5F6368; }
                    .shortcuts-card {
                        display: flex; gap: 24px; padding: 24px 32px; margin-top: 32px;
                        background: #FFFFFF; border-radius: 24px;
                        box-shadow: 0 1px 3px rgba(0,0,0,0.04);
                        overflow-x: auto; max-width: 90%;
                    }
                    .shortcut { 
                        display: flex; flex-direction: column; align-items: center; 
                        text-decoration: none; color: #5F6368; font-size: 12px; font-weight: 500;
                        min-width: 64px;
                    }
                    .shortcut-icon { 
                        width: 48px; height: 48px; border-radius: 16px; 
                        background: #F0F0F8; display: flex; align-items: center; justify-content: center; 
                        font-size: 20px; margin-bottom: 8px; color: #202124;
                    }
                    .shortcut:hover .shortcut-icon { background: #E8E7EF; }
                </style>
            </head>
            <body>
                <div class="logo-container">
                    <img src="${getAppLogoBase64()}" style="width: 150px; height: 150px; border-radius: 24px;" alt="Chromia">
                </div>
                <div class="search-box">
                    <svg focusable="false" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="20" height="20" fill="#5F6368"><path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"></path></svg>
                    <form action="https://www.google.com/search" method="GET" style="flex:1; display:flex;">
                        <input type="text" name="q" placeholder="Search the web" autocomplete="off" autofocus>
                    </form>
                </div>
                ${if (shortcutsHtml.isNotBlank()) "<div class=\"shortcuts-card\">\n$shortcutsHtml\n</div>" else ""}
            </body>
            </html>
        """.trimIndent()
    }

    /** Convert dp to px so touch targets are a consistent physical size on every screen density. */
    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Match the system status bar to the tab strip background for a cohesive top look
        window.statusBarColor = Color.parseColor("#E8E7EF")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        prefs = getSharedPreferences("SafeBrowserPrefs", Context.MODE_PRIVATE)
        // issue: the blocklist cache used to live in "SafeBrowserPrefs", which
        // SafeBrowserApp.clearBrowsingData() wipes on every full app close (by design, for
        // the data-wipe feature). That meant the real, server-pushed blocklist was discarded
        // every time the app was closed, silently falling back to the small hardcoded list
        // until the next successful network fetch -- so closing the app with no internet on
        // the next launch effectively unblocked everything custom. Its own file, parallel to
        // how DomainTracker keeps tracked domains in a separate file, is never touched by the
        // close-wipe and survives indefinitely with no internet required.
        blocklistPrefs = getSharedPreferences("BlocklistCache", Context.MODE_PRIVATE)
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
        
        handleIntent(intent)
        
        startPeriodicRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val loadUrlExtra = intent?.getStringExtra("loadUrl")
        if (loadUrlExtra != null) {
            loadUrlInActiveTab(loadUrlExtra)
            return
        }
        val action = intent?.action
        if (action == Intent.ACTION_WEB_SEARCH) {
            val query = intent.getStringExtra(android.app.SearchManager.QUERY) ?: ""
            createNewTab("https://www.google.com/search?q=" + android.net.Uri.encode(query))
            return
        }

        val intentUrl = intent?.dataString
        if (intentUrl != null) {
            createNewTab(intentUrl)
        } else {
            createNewTab("safebrowser://ntp")
        }
    }

    private fun setupChromeUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF")) // special white for toolbar
            elevation = dp(4).toFloat()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E8E7EF")) // lightened gray for top
            setPadding(dp(8), dp(8), dp(8), 0)
        }

        tabContainer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabContainer.addView(tabLayout)
        topRow.addView(tabContainer)

        toolbar.addView(topRow)

        addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            layoutTransition = android.animation.LayoutTransition()
        }

        val backBtn = createIconButton(R.drawable.ic_back) {
            getActiveWebView()?.goBack()
        }

        val forwardBtn = createIconButton(R.drawable.ic_forward) {
            getActiveWebView()?.goForward()
        }

        // Doubles as Stop while a page is loading (issue 6: don't get trapped on a stalled page).
        refreshBtn = createIconButton(R.drawable.ic_refresh) {
            if (isLoading) {
                getActiveWebView()?.stopLoading()
                setLoadingState(false)
            } else {
                val webView = getActiveWebView()
                // On a slow/dead connection the WebView can sit on "about:blank" forever
                // without ever committing the intended URL, in which case reload() is a
                // no-op and the refresh button visibly does nothing. Fall back to re-issuing
                // the tab's last known URL through the normal (blocklist-checked) load path.
                if (webView != null && (webView.url == null || webView.url == "about:blank")) {
                    getActiveTab()?.url?.takeIf { it.isNotBlank() }?.let { loadUrlInWebView(webView, it) }
                        ?: webView.reload()
                } else {
                    webView?.reload()
                }
            }
        }

        val homeBtn = createIconButton(R.drawable.ic_home) {
            getActiveWebView()?.let { loadUrlInWebView(it, "safebrowser://ntp") }
        }

        val addressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
            val shape = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F0F0F8")) // lightened pill inside white toolbar
            }
            background = shape
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        }

        val lockIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(Color.parseColor("#5F6368"))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0, 0, dp(8), 0) }
        }
        addressBar.addView(lockIcon)

        val menuBtn = createIconButton(R.drawable.ic_menu) { }
        menuBtn.setOnClickListener { showMenu(menuBtn) }

        urlInput = EditText(this).apply {
            hint = "Search or type URL"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#202124"))
            setHintTextColor(Color.parseColor("#5F6368"))
            textSize = 16f
            maxLines = 1
            isSingleLine = true
            setPadding(dp(4), 0, dp(4), 0)
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
                    urlInput.clearFocus()
                    hideKeyboard()
                    true
                } else false
            }
            setOnFocusChangeListener { _, hasFocus ->
                updateAddressBarDisplay()
                val visibility = if (hasFocus) View.GONE else View.VISIBLE
                backBtn.visibility = visibility
                forwardBtn.visibility = visibility
                refreshBtn.visibility = visibility
                homeBtn.visibility = visibility
                menuBtn.visibility = visibility

                if (hasFocus) {
                    post { selectAll() }
                } else {
                    hideKeyboard()
                }
            }
            setOnClickListener {
                if (!hasFocus()) requestFocus()
            }
        }
        addressBar.addView(urlInput)

        addressRow.addView(backBtn)
        addressRow.addView(forwardBtn)
        addressRow.addView(refreshBtn)
        addressRow.addView(homeBtn)
        addressRow.addView(addressBar)
        addressRow.addView(menuBtn)

        toolbar.addView(addressRow)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
            visibility = View.GONE
            isIndeterminate = false
        }
        toolbar.addView(progressBar)

        rootLayout.addView(toolbar)

        swipeRefreshLayout = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            isEnabled = false // Disabled to prevent blocking scroll to top
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            setOnRefreshListener {
                getActiveWebView()?.reload()
            }
        }

        webViewContainer = FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        swipeRefreshLayout.addView(webViewContainer)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }
        contentFrame.addView(swipeRefreshLayout)

        downloadPopupBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(12), dp(12), dp(12))
            val shape = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#323232"))
            }
            background = shape
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) }
            elevation = dp(6).toFloat()
        }
        downloadPopupText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        downloadPopupBar.addView(downloadPopupText)
        contentFrame.addView(downloadPopupBar)

        rootLayout.addView(contentFrame)

        setContentView(rootLayout)
    }

    /**
     * issue 5: downloads only ever surfaced as a brief Toast (gone in ~2s) plus whatever the
     * system download notification looked like -- nothing resembling Chrome's persistent
     * "File downloaded" bottom bar with an action button. This shows an in-app bar over the
     * page that stays until tapped or auto-dismissed, with an "Open" action that jumps
     * straight to the file, mirroring Chrome's download-complete snackbar.
     */
    private fun showDownloadStartedPopup(record: DownloadRecord) {
        downloadPopupDismissRunnable?.let { handler.removeCallbacks(it) }
        downloadPopupText.text = "Downloading ${record.fileName}"
        downloadPopupBar.removeAllViews()
        downloadPopupBar.addView(downloadPopupText)
        downloadPopupBar.addView(TextView(this).apply {
            text = "VIEW"
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
                downloadPopupBar.visibility = View.GONE
            }
        })
        downloadPopupBar.addView(TextView(this).apply {
            text = "\u2715"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 14f
            setPadding(dp(8), 0, 0, 0)
            isClickable = true
            setOnClickListener { downloadPopupBar.visibility = View.GONE }
        })
        downloadPopupBar.visibility = View.VISIBLE
        downloadPopupBar.bringToFront()
        val dismiss = Runnable { downloadPopupBar.visibility = View.GONE }
        downloadPopupDismissRunnable = dismiss
        handler.postDelayed(dismiss, 6000L)
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
        
        tabLayout.post {
            val newTabView = tabLayout.getChildAt(tabLayout.childCount - 1)
            if (newTabView != null) {
                newTabView.scaleX = 0.8f
                newTabView.scaleY = 0.8f
                newTabView.alpha = 0f
                newTabView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            }
        }
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
                
                // Trick Google OAuth into thinking this is a standard Chrome browser
                userAgentString = userAgentString.replace("; wv", "").replace(" wv", "").replace("Version/4.0 ", "")
            }
            addJavascriptInterface(ThemeColorInterface(), "AndroidTheme")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = SafeWebViewClient()
            webChromeClient = SafeWebChromeClient()
            // issue 3: downloads silently did nothing because no DownloadListener was ever attached.
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val tab = findTabFor(this)
                val isGhostTab = tab != null && copyBackForwardList().size == 0 && (this.url.isNullOrBlank() || this.url == "about:blank" || this.url == url)
                
                if (isGhostTab && tab != null) {
                    closeTab(tab.id)
                }
                startDownload(url, contentDisposition, mimeType)
            }
            // Stronger video blocking: the old approach injected JS after onPageFinished /
            // mid-progress, by which point the page's own scripts (and the player they build)
            // had often already run. addDocumentStartJavaScript runs our script before any
            // page script, on every navigation including iframes, so sites like flyflix can't
            // win the race by starting playback before our blocking code exists.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, getMediaBlockScript(), setOf("*"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val folder = publicFolderFor(mimeType, fileName)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(folder)
            val originalFile = java.io.File(downloadsDir, fileName)

            val doDownload = { finalName: String ->
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", getActiveWebView()?.settings?.userAgentString ?: "")
                    setDescription("Downloading file...")
                    setTitle(finalName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(folder, finalName)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = dm.enqueue(request)
                downloadStore.add(
                    DownloadRecord(downloadId, finalName, mimeType ?: "*/*", folder, System.currentTimeMillis(), url)
                )
                Toast.makeText(this, "Downloading $finalName", Toast.LENGTH_SHORT).show()
                showDownloadStartedPopup(
                    DownloadRecord(downloadId, finalName, mimeType ?: "*/*", folder, System.currentTimeMillis(), url)
                )
            }

            if (originalFile.exists()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("File already exists")
                    .setMessage("You have already downloaded '$fileName'. Do you want to download it again?")
                    .setPositiveButton("Download Again") { _, _ ->
                        var counter = 1
                        var destFile = originalFile
                        var newFileName = fileName
                        while (destFile.exists()) {
                            val dotIndex = fileName.lastIndexOf(".")
                            val name = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
                            val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""
                            newFileName = "$name ($counter)$ext"
                            destFile = java.io.File(downloadsDir, newFileName)
                            counter++
                        }
                        doDownload(newFileName)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                doDownload(fileName)
            }
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
            updateAddressBarDisplay()
            setLoadingState(it.isLoading)
        }
        updateTabUI()
    }

    private fun closeTab(tabId: Int) {
        val tabIndex = tabs.indexOfFirst { it.id == tabId }
        if (tabIndex == -1) return
        val tab = tabs[tabIndex]
        val wasActive = tab.id == activeTabId
        
        tab.webView?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            webViewContainer.removeView(it)
            it.destroy()
        }
        tabs.removeAt(tabIndex)
        
        if (tabs.isEmpty()) {
            createNewTab("https://www.google.com")
        } else if (wasActive) {
            val nextIndex = if (tabIndex < tabs.size) tabIndex else tabs.size - 1
            switchToTab(tabs[nextIndex].id)
        } else {
            updateTabUI()
        }
    }

    private fun updateTabUI() {
        tabLayout.removeAllViews()
        tabs.forEach { tab ->
            val isActive = tab.id == activeTabId
            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                val shape = GradientDrawable().apply {
                    val radius = dp(12).toFloat()
                    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    setColor(if (isActive) Color.parseColor("#FFFFFF") else Color.TRANSPARENT)
                }
                background = shape
                layoutParams = LinearLayout.LayoutParams(
                    dp(220), // Wider for tablets
                    dp(44)
                ).apply { setMargins(0, 0, dp(4), 0) }
                
                if (isActive) {
                    elevation = dp(3).toFloat()
                }

                isClickable = true
                setOnClickListener { switchToTab(tab.id) }
            }

            val faviconView = ImageView(this).apply {
                val size = dp(16)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, dp(6), 0)
                }
                if (tab.favicon != null) {
                    setImageBitmap(tab.favicon)
                    visibility = View.VISIBLE
                } else if (tab.title == "New Tab") {
                    // Chrome typically hides the icon or uses a faint globe/search for the NTP
                    // Hiding it entirely looks much cleaner than a generic house icon.
                    visibility = View.GONE
                } else {
                    setImageResource(android.R.drawable.ic_menu_search)
                    setColorFilter(Color.parseColor("#9AA0A6"))
                    visibility = View.VISIBLE
                }
            }
            tabView.addView(faviconView)

            val titleView = TextView(this).apply {
                text = tab.title
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 13f
                includeFontPadding = false
                setTextColor(Color.parseColor(if (isActive) "#202124" else "#5F6368"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            }
            tabView.addView(titleView)

            val closeBtn = TextView(this).apply {
                text = "\u2715"
                textSize = 14f
                setTextColor(Color.parseColor(if (isActive) "#5F6368" else "#9AA0A6"))
                // A real touch target rather than relying on the text glyph's tiny hitbox.
                minWidth = dp(32)
                minHeight = dp(32)
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { closeTab(tab.id) }
            }
            tabView.addView(closeBtn)

            tabLayout.addView(tabView)
        }

        val newTabBtn = createIconButton(R.drawable.ic_add, sizeDp = 36) { createNewTab("safebrowser://ntp") }
        tabLayout.addView(newTabBtn)

        tabLayout.post {
            val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
            if (activeIndex != -1 && activeIndex < tabLayout.childCount) {
                val child = tabLayout.getChildAt(activeIndex)
                val scrollX = child.left - (tabContainer.width - child.width) / 2
                tabContainer.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
            }
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
            input.startsWith("safebrowser://") -> input
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(input)
        }
        val webView = getActiveWebView() ?: return
        loadUrlInWebView(webView, url)
    }

    private fun loadUrlInWebView(webView: WebView, url: String) {
        if (url == "safebrowser://ntp") {
            webView.loadDataWithBaseURL("safebrowser://ntp", getNtpHtml(), "text/html", "UTF-8", null)
            urlInput.setText("")
            return
        }
        if (isBlocked(url)) {
            webView.loadDataWithBaseURL(url, getBlockedHtml(url), "text/html", "UTF-8", null)
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
        getActiveTab()?.isLoading = loading
        loadWatchdogRunnable?.let { handler.removeCallbacks(it) }
        progressShowRunnable?.let { handler.removeCallbacks(it) }

        if (loading) {
            progressShowRunnable = Runnable {
                progressBar.visibility = View.VISIBLE
                progressBar.alpha = 1f
                progressBar.progress = 0
                progressAnimator?.cancel()
                progressAnimator = android.animation.ObjectAnimator.ofInt(progressBar, "progress", 0, 80).apply {
                    duration = 800
                    interpolator = android.view.animation.DecelerateInterpolator()
                    start()
                }
            }
            handler.postDelayed(progressShowRunnable!!, 150)
            
            refreshBtn.setImageResource(R.drawable.ic_close)
            loadWatchdogRunnable = Runnable {
                if (isLoading) {
                    setLoadingState(false)
                }
            }
            handler.postDelayed(loadWatchdogRunnable!!, LOAD_WATCHDOG_MS)
        } else {
            if (::swipeRefreshLayout.isInitialized) {
                swipeRefreshLayout.isRefreshing = false
            }
            progressAnimator?.cancel()
            progressAnimator = android.animation.ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, 100).apply {
                duration = 200
                start()
            }
            progressBar.animate().alpha(0f).setDuration(200).setStartDelay(200).withEndAction {
                progressBar.visibility = View.GONE
                progressBar.progress = 0
            }.start()
            
            refreshBtn.setImageResource(R.drawable.ic_refresh)
        }
    }

    private fun updateAddressBarDisplay() {
        val tab = getActiveTab() ?: return
        val url = tab.url
        if (urlInput.hasFocus()) {
            val editUrl = if (url == "safebrowser://ntp") "" else url
            if (urlInput.text.toString() != editUrl) {
                urlInput.setText(editUrl)
            }
        } else {
            if (url.startsWith("http")) {
                try {
                    val displayUrl = url
                    if (urlInput.text.toString() != displayUrl) {
                        urlInput.setText(displayUrl)
                        urlInput.setSelection(0)
                        urlInput.ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                } catch (e: Exception) {
                    if (urlInput.text.toString() != url) {
                        urlInput.setText(url)
                        urlInput.setSelection(0)
                    }
                }
            } else {
                val displayStr = if (url == "safebrowser://ntp") "" else url
                if (urlInput.text.toString() != displayStr) urlInput.setText(displayStr)
            }
        }
    }

    private fun showMenu(anchor: View) {
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            background = shape
            setPadding(0, dp(8), 0, dp(8))
        }

        val popupWindow = android.widget.PopupWindow(popupView, dp(240), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) // Needed for shadow and outside click
        }

        val addMenuItem = { text: String, onClick: () -> Unit ->
            val itemView = TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#202124"))
                textSize = 16f
                setPadding(dp(20), dp(14), dp(20), dp(14))
                isClickable = true
                
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                
                setOnClickListener {
                    onClick()
                    popupWindow.dismiss()
                }
            }
            popupView.addView(itemView)
        }

        addMenuItem("New Tab") { createNewTab("https://www.google.com") }
        addMenuItem("Reload") { getActiveWebView()?.reload() }
        addMenuItem("Downloads") { startActivity(Intent(this, DownloadsActivity::class.java)) }
        addMenuItem("History") { startActivity(Intent(this, HistoryActivity::class.java)) }
        addMenuItem("Tracked Domains: " + domainTracker.getTrackedCount()) {}
        addMenuItem("Upload Domains Now") {
            domainTracker.uploadDomains()
            Toast.makeText(this, "Uploading domains...", Toast.LENGTH_SHORT).show()
        }
        addMenuItem("Refresh Blocklist") {
            fetchBlocklist()
            Toast.makeText(this, "Refreshing blocklist...", Toast.LENGTH_SHORT).show()
        }

        popupWindow.showAsDropDown(anchor, 0, dp(8))
    }

    private fun loadBlocklist() {
        val cachedDomains = blocklistPrefs.getStringSet("cached_domains", null)
        val cachedKeywords = blocklistPrefs.getStringSet("cached_keywords", null)
        val cachedVideo = blocklistPrefs.getBoolean("cached_video_blocking", true)
        val cachedAudio = blocklistPrefs.getBoolean("cached_audio_blocking", false)
        blockedDomains = if (cachedDomains != null) cachedDomains.toMutableList() else fallbackBlockedDomains.toMutableList()
        blockedKeywords = if (cachedKeywords != null) cachedKeywords.toMutableList() else mutableListOf()
        videoBlocking = cachedVideo
        audioBlocking = cachedAudio
        // Shortcuts are read directly in getNtpHtml from blocklistPrefs
    }

    private fun saveBlocklist(domains: List<String>, keywords: List<String>, video: Boolean, audio: Boolean, shortcuts: List<Shortcut>?) {
        val editor = blocklistPrefs.edit()
            .putStringSet("cached_domains", domains.toSet())
            .putStringSet("cached_keywords", keywords.toSet())
            .putBoolean("cached_video_blocking", video)
            .putBoolean("cached_audio_blocking", audio)
            
        if (shortcuts != null) {
            editor.putString("cached_shortcuts", gson.toJson(shortcuts))
        }
        
        editor.apply()
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
            val audio = config.audioBlocking ?: false
            handler.post {
                if (domains.isNotEmpty()) blockedDomains = domains
                if (keywords.isNotEmpty()) blockedKeywords = keywords
                videoBlocking = video
                audioBlocking = audio
                saveBlocklist(
                    if (domains.isNotEmpty()) domains else blockedDomains,
                    if (keywords.isNotEmpty()) keywords else blockedKeywords,
                    video,
                    audio,
                    config.shortcuts
                )
                Toast.makeText(
                    this,
                    "Blocklist updated: ${blockedDomains.size} domains, ${blockedKeywords.size} keywords",
                    Toast.LENGTH_SHORT
                ).show()
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
        val host = try { Uri.parse(url).host?.lowercase()?.removePrefix("www.") } catch (e: Exception) { null }

        // issue: "x.com" in blocked_domains was matching netflix.com, box.com, xerox.com
        // etc. because the old check was lowerUrl.contains(domain) -- a plain substring
        // search over the *whole URL*. A blocked domain should only match itself or a real
        // subdomain of itself (host == domain, or host ends with ".domain"), never an
        // unrelated domain that merely shares trailing characters.
        if (host != null && blockedDomains.any { raw ->
                val d = raw.lowercase().removePrefix("www.")
                host == d || host.endsWith(".$d")
            }) return true

        // Keywords are intentionally still substring-matched (e.g. "vpn" should catch
        // freevpnapp.com), but that means very short keywords (1-2 chars) can overblock
        // unrelated sites. Keep an eye on what gets pushed as a keyword from the dashboard.
        if (blockedKeywords.any { lowerUrl.contains(it.lowercase()) }) return true
        if (videoBlocking && videoExtensions.any { lowerUrl.endsWith(it, ignoreCase = true) }) return true
        if (audioBlocking && audioExtensions.any { lowerUrl.endsWith(it, ignoreCase = true) }) return true
        return false
    }

    private fun getBlockedHtml(url: String): String {
        val domain = try { android.net.Uri.parse(url).host ?: url } catch (e: Exception) { url }
        return "<html><head><title>Access Blocked</title><style>body{font-family:Arial,sans-serif;text-align:center;padding-top:100px;background:#f5f5f5;}.block-icon{font-size:80px;color:#d32f2f;}h1{color:#d32f2f;}p{color:#666;font-size:18px;}.back-link, .unblock-btn{display:inline-block;margin:10px;padding:12px 24px;background:#1976d2;color:white;text-decoration:none;border-radius:4px;border:none;font-size:16px;cursor:pointer;}.unblock-btn{background:#f57c00;}</style></head><body><div class=\"block-icon\">&#128683;</div><h1>Access Blocked</h1><p>This website or content has been blocked by your administrator.</p><br><a href=\"https://www.google.com\" class=\"back-link\">Go back to Google</a><button class=\"unblock-btn\" onclick=\"AndroidTheme.requestUnblock('${domain.replace("'", "\\'")}'); this.disabled=true; this.innerText='Request Sent';\">Request Unblock</button></body></html>"
    }

    private fun getMediaBlockScript(): String {
        return """
            (function() {
                function isVideoBlocked() { return window.AndroidTheme ? window.AndroidTheme.isVideoBlocked() : false; }
                function isAudioBlocked() { return window.AndroidTheme ? window.AndroidTheme.isAudioBlocked() : false; }
                
                function neuter(el) {
                    try { if (el.pause) el.pause(); } catch(e) {}
                    try { el.src = ""; el.removeAttribute("src"); if (el.load) el.load(); } catch(e) {}
                    try { el.remove(); } catch(e) {}
                }

                try {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        var isVid = this instanceof HTMLVideoElement;
                        var isAud = this instanceof HTMLAudioElement;
                        if ((isVid && isVideoBlocked()) || (isAud && isAudioBlocked())) {
                            neuter(this);
                            return Promise.reject(new DOMException("Media blocked", "NotAllowedError"));
                        }
                        return origPlay.apply(this, arguments);
                    };
                } catch(e) {}

                try {
                    var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "src");
                    if (srcDesc && srcDesc.configurable) {
                        var origSet = srcDesc.set;
                        Object.defineProperty(HTMLMediaElement.prototype, "src", {
                            get: function() { return ""; },
                            set: function(v) {
                                var isVid = this instanceof HTMLVideoElement;
                                var isAud = this instanceof HTMLAudioElement;
                                if ((isVid && isVideoBlocked()) || (isAud && isAudioBlocked())) return;
                                if (origSet) origSet.call(this, v);
                            },
                            configurable: true
                        });
                    }
                } catch(e) {}

                try {
                    var srcObjDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "srcObject");
                    if (srcObjDesc && srcObjDesc.configurable) {
                        var origObjSet = srcObjDesc.set;
                        Object.defineProperty(HTMLMediaElement.prototype, "srcObject", {
                            get: function() { return null; },
                            set: function(v) {
                                var isVid = this instanceof HTMLVideoElement;
                                var isAud = this instanceof HTMLAudioElement;
                                if ((isVid && isVideoBlocked()) || (isAud && isAudioBlocked())) return;
                                if (origObjSet) origObjSet.call(this, v);
                            },
                            configurable: true
                        });
                    }
                } catch(e) {}

                try {
                    var origCreateElement = document.createElement.bind(document);
                    document.createElement = function(tagName) {
                        var el = origCreateElement(tagName);
                        if (typeof tagName === "string") {
                            if ((tagName.toLowerCase() === "video" && isVideoBlocked()) || (tagName.toLowerCase() === "audio" && isAudioBlocked())) {
                                neuter(el);
                            }
                        }
                        return el;
                    };
                } catch(e) {}
                
                try {
                    var OrigAudioContext = window.AudioContext;
                    window.AudioContext = function() { 
                        if (isAudioBlocked()) throw new Error("Audio blocked"); 
                        return new OrigAudioContext();
                    };
                } catch(e) {}
                
                try {
                    var OrigWebkitAudioContext = window.webkitAudioContext;
                    window.webkitAudioContext = function() { 
                        if (isAudioBlocked()) throw new Error("Audio blocked"); 
                        return new OrigWebkitAudioContext();
                    };
                } catch(e) {}

                try {
                    var OrigAudio = window.Audio;
                    window.Audio = function() { 
                        if (isAudioBlocked()) {
                            var fakeAudio = document.createElement("audio");
                            neuter(fakeAudio);
                            return fakeAudio; 
                        }
                        return new OrigAudio();
                    };
                } catch(e) {}
                
                try {
                    var OrigMediaSource = window.MediaSource;
                    window.MediaSource = function() {
                        if (isVideoBlocked() || isAudioBlocked()) throw new Error("MediaSource blocked");
                        return new OrigMediaSource();
                    };
                } catch(e) {}

                try {
                    var OrigRTC = window.RTCPeerConnection;
                    window.RTCPeerConnection = function() {
                        if (isVideoBlocked() || isAudioBlocked()) throw new Error("WebRTC blocked");
                        return new OrigRTC();
                    };
                } catch(e) {}

                var blockedIframeHosts = ["youtube", "youtu.be", "vimeo", "dailymotion", "twitch", "tiktok", "netflix", "primevideo", "disney", "hulu", "spotify", "soundcloud", "mixcloud"];
                function killMedia() {
                    if (isVideoBlocked()) document.querySelectorAll("video").forEach(neuter);
                    if (isAudioBlocked()) document.querySelectorAll("audio").forEach(neuter);
                    if (isVideoBlocked() || isAudioBlocked()) {
                        document.querySelectorAll("iframe").forEach(function(f) {
                            var src = (f.src || "").toLowerCase();
                            if (blockedIframeHosts.some(function(h) { return src.includes(h); })) {
                                f.src = "about:blank"; f.remove();
                            }
                        });
                    }
                }

                function startObserving() {
                    var observer = new MutationObserver(killMedia);
                    observer.observe(document.documentElement || document.body, { childList: true, subtree: true });
                }
                
                if (document.readyState === "loading") {
                    document.addEventListener("DOMContentLoaded", function() { killMedia(); startObserving(); });
                } else {
                    killMedia(); startObserving();
                }
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
        private var lastFallbackUrl: String? = null

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
            val url = error?.url ?: return
            val host = Uri.parse(url).host ?: ""

            // 1. Auto-proceed for IP addresses (e.g. local routers)
            val isIp = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            if (isIp) {
                handler?.proceed()
                return
            }

            // 2. Auto-proceed if user explicitly allowed this domain previously
            if (allowedSslDomains.contains(host)) {
                handler?.proceed()
                return
            }

            // 3. Show Chrome-style HTML warning screen
            handler?.cancel()
            val html = """
                <html><head><title>Privacy Error</title>
                <style>
                    body { font-family: 'Google Sans', Roboto, Arial, sans-serif; text-align: center; padding-top: 100px; background: #F0F4F9; color: #1F1F1F; }
                    .icon { font-size: 80px; color: #D93025; }
                    h1 { color: #1F1F1F; margin-bottom: 16px; }
                    p { color: #5F6368; font-size: 16px; max-width: 600px; margin: 0 auto 32px auto; line-height: 1.5; }
                    .advanced { color: #1A73E8; cursor: pointer; font-weight: 500; text-decoration: underline; margin-top: 32px; display: inline-block; }
                    .hidden { display: none; }
                    .proceed-btn { display: inline-block; margin-top: 24px; padding: 12px 24px; background: transparent; color: #D93025; border: 1px solid #D93025; border-radius: 4px; font-weight: 500; cursor: pointer; text-decoration: none; }
                </style>
                <script>
                    function toggleAdvanced() {
                        var div = document.getElementById('advanced-div');
                        div.className = div.className === 'hidden' ? '' : 'hidden';
                    }
                </script>
                </head><body>
                <div class="icon">⚠️</div>
                <h1>Your connection is not private</h1>
                <p>Attackers might be trying to steal your information from <strong>$host</strong> (for example, passwords, messages, or credit cards).</p>
                <div class="advanced" onclick="toggleAdvanced()">Advanced</div>
                <div id="advanced-div" class="hidden">
                    <p style="margin-top: 24px;">The server could not prove that it is <strong>$host</strong>. Its security certificate is not trusted.</p>
                    <button class="proceed-btn" onclick="AndroidTheme.proceedSsl('$host')">Proceed to $host (unsafe)</button>
                </div>
                </body></html>
            """.trimIndent()
            
            view?.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
            
            val tab = findTabFor(view)
            tab?.isLoading = false
            if (tab == null || tab.id == activeTabId) setLoadingState(false)
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString()?.lowercase() ?: return super.shouldInterceptRequest(view, request)
            if (videoBlocking && videoExtensions.any { url.endsWith(it) }) {
                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            if (audioBlocking && audioExtensions.any { url.endsWith(it) }) {
                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url.toString()
            
            if (url.startsWith("safebrowser://search?q=")) {
                val query = Uri.parse(url).getQueryParameter("q") ?: ""
                view?.loadUrl("https://www.google.com/search?q=" + android.net.Uri.encode(query))
                return true
            }
            if (isBlocked(url)) {
                view?.loadDataWithBaseURL(url, getBlockedHtml(url), "text/html", "UTF-8", null)
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
                updateAddressBarDisplay()
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val tab = findTabFor(view) ?: return
            
            if (url != null && view != null) {
                SessionHistoryManager.add(url, view.title ?: "")
            }
            
            tab.isLoading = false
            view?.title?.let { title -> tab.title = title }
            updateTabUI()
            if (tab.id == activeTabId) setLoadingState(false)
            if ((videoBlocking || audioBlocking) && url != null && url != lastVideoScriptInjectedForUrl) {
                lastVideoScriptInjectedForUrl = url
                view?.evaluateJavascript(getMediaBlockScript(), null)
            }
            url?.let { trackedUrl ->
                view?.evaluateJavascript(
                    "(function() { var desc = document.querySelector('meta[name=\"description\"]'); return desc ? desc.getAttribute('content') : ''; })()"
                ) { descriptionValue ->
                    val desc = descriptionValue?.trim('"') ?: ""
                    val title = view.title ?: ""
                    domainTracker.trackDomain(trackedUrl, title, desc)
                }
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val url = request?.url?.toString()
            val errorCode = error?.errorCode ?: 0

            // Fallback to HTTP if an HTTPS connection completely fails (connection refused or timeout)
            if (request?.isForMainFrame == true && url != null && url.startsWith("https://")) {
                if (errorCode == WebViewClient.ERROR_CONNECT || errorCode == WebViewClient.ERROR_TIMEOUT) {
                    if (url != lastFallbackUrl) {
                        lastFallbackUrl = url
                        val httpUrl = url.replaceFirst("https://", "http://")
                        view?.loadUrl(httpUrl)
                        return
                    }
                }
            }

            if (request?.isForMainFrame == true) {
                view?.loadDataWithBaseURL(url, getErrorHtml(errorCode, error?.description?.toString() ?: ""), "text/html", "UTF-8", null)
            }

            super.onReceivedError(view, request, error)
            // issue 6: a failed/cancelled load used to leave the progress bar spinning forever
            // with no way out except killing the app.
            val tab = findTabFor(view)
            tab?.isLoading = false
            if (tab == null || tab.id == activeTabId) setLoadingState(false)
            Log.e(TAG, "WebView error: ${error?.description}")
        }
    }

    inner class SafeWebChromeClient : WebChromeClient() {
        // The `favicon` param on WebViewClient.onPageStarted is frequently null or a stale
        // cached bitmap. The actual favicon for the current page reliably arrives here once
        // the browser engine has fetched it, so capture it here and repaint the tab strip.
        override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
            super.onReceivedIcon(view, icon)
            val tab = findTabFor(view) ?: return
            tab.favicon = icon
            updateTabUI()
        }

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
            if (newProgress in 50..55 && (videoBlocking || audioBlocking)) {
                val currentUrl = view?.url
                if (currentUrl != null && currentUrl != lastVideoScriptInjectedForUrl) {
                    view?.evaluateJavascript(getMediaBlockScript(), null)
                }
            }
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (videoBlocking) {
                Toast.makeText(this@MainActivity, "Fullscreen video is blocked", Toast.LENGTH_SHORT).show()
                callback?.onCustomViewHidden()
            } else {
                // If video blocking is off, gracefully decline to handle custom view, 
                // so the WebView falls back to inline play or handles it itself.
                callback?.onCustomViewHidden()
            }
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val newWebView = createWebView()
            val transport = resultMsg?.obj as? WebView.WebViewTransport
            if (transport != null) {
                transport.webView = newWebView
                resultMsg.sendToTarget()
                val newTabId = tabCounter.incrementAndGet()
                val newTab = TabData(id = newTabId, url = "about:blank", title = "New Tab", webView = newWebView)
                tabs.add(newTab)
                webViewContainer.addView(newWebView)
                switchToTab(newTabId)
                return true
            }
            return false
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
        for (tab in tabs) {
            tab.webView?.destroy()
        }
        tabs.clear()
    }
}
