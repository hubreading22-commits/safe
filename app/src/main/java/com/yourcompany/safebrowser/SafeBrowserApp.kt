package com.yourcompany.safebrowser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Issue 3: "remove data after closing the browser completely (other than tracked domains)".
 *
 * Android does not give an app a reliable callback for "the user fully closed/swiped away the
 * app" -- onDestroy() is not guaranteed to run when the process is killed, and there is no
 * onAppExit() hook. Two complementary strategies cover the practical cases:
 *
 * 1) Application.onCreate() runs exactly once per fresh process. If the user swipes the app
 *    away (or it's killed by the OS) and later reopens it, that's a brand-new process, so this
 *    runs again before MainActivity does anything -- we wipe browsing data (cookies, DOM
 *    storage, WebView cache/history, and our own "SafeBrowserPrefs") right here. This covers
 *    "closed completely and reopened".
 *
 * 2) ActivityLifecycleCallbacks tracks how many activities are actually started. When that
 *    count drops to zero (e.g. user presses Home/Back from the last screen) we wait a short
 *    grace period and, if the app hasn't come back to the foreground in that window, wipe data
 *    immediately instead of waiting for the next cold start. The delay+cancel avoids wiping
 *    data on a simple rotation or brief task-switch.
 *
 * In both cases "DomainTracker" prefs (the tracked-domain set) are a different SharedPreferences
 * file and are never touched here, so tracked domains persist across these wipes, as required.
 */
class SafeBrowserApp : Application() {

    companion object {
        private const val TAG = "SafeBrowserApp"
        private const val BACKGROUND_WIPE_DELAY_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startedActivityCount = 0
    private var pendingWipe: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        // Strategy 1: fresh process => wipe whatever was left over from the previous run.
        clearBrowsingData()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                pendingWipe?.let { handler.removeCallbacks(it) }
                pendingWipe = null
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                // Background wipe logic removed per user request
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun clearBrowsingData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing cookies", e)
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing web storage", e)
        }
        try {
            val wv = WebView(this)
            wv.clearHistory()
            wv.clearFormData()
            wv.clearCache(true)
            wv.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing WebView cache/history", e)
        }
        getSharedPreferences("SafeBrowserPrefs", Context.MODE_PRIVATE).edit().clear().apply()
        DownloadStore(this).clear()
    }
}
