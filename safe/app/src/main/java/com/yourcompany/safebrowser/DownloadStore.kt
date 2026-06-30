package com.yourcompany.safebrowser

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class DownloadRecord(
    val downloadId: Long,
    val fileName: String,
    val mimeType: String,
    val folder: String,
    val timestamp: Long,
    val sourceUrl: String
)

/**
 * Tiny persisted list of downloads, backing the Downloads page (issue 2). Lives in its own
 * SharedPreferences file ("DownloadStore") -- deliberately separate from "SafeBrowserPrefs" so
 * the data-wipe-on-close logic (issue 3) can decide independently whether to keep it. We
 * currently DO wipe it together with browsing data, since a download record is "history" too;
 * change SafeBrowserApp.clearBrowsingData() if you want downloads to survive a full close.
 */
class DownloadStore(context: Context) {

    private val prefs = context.getSharedPreferences("DownloadStore", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<DownloadRecord>>() {}.type

    @Synchronized
    fun getAll(): List<DownloadRecord> {
        val json = prefs.getString("records", null) ?: return emptyList()
        return try {
            (gson.fromJson<MutableList<DownloadRecord>>(json, listType) ?: mutableListOf())
                .sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(record: DownloadRecord) {
        val current = (getAll()).toMutableList()
        current.add(0, record)
        prefs.edit().putString("records", gson.toJson(current)).apply()
    }

    @Synchronized
    fun remove(downloadId: Long) {
        val current = getAll().filterNot { it.downloadId == downloadId }
        prefs.edit().putString("records", gson.toJson(current)).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove("records").apply()
    }
}
