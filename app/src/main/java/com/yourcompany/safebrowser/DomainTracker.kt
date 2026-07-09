package com.yourcompany.safebrowser

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DomainTracker(context: Context) {

    companion object {
        private const val TAG = "DomainTracker"
        private const val UPLOAD_URL = "https://browser.proxybotkk.workers.dev/api/domains"
        private const val BATCH_SIZE = 10
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("DomainTracker", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val trackedDomains = mutableSetOf<String>()
    private val pendingUploads = mutableListOf<String>()

    init {
        val saved = prefs.getStringSet("tracked_domains", emptySet())
        trackedDomains.addAll(saved ?: emptySet())
        // issue: pendingUploads used to be in-memory only. If the app process was killed
        // (swiped away, OS memory pressure) before a batch of <10 reached the auto-upload
        // threshold, or while a failed batch was waiting to be retried, those domains were
        // lost forever -- trackDomain() guards on trackedDomains (already-seen), not
        // pendingUploads, so a domain that had already been recorded as "tracked" would
        // never get re-queued just because the process restarted. Persist the pending queue
        // too so nothing silently vanishes across a restart.
        val savedPending = prefs.getStringSet("pending_uploads", emptySet())
        pendingUploads.addAll(savedPending ?: emptySet())
    }

    fun trackDomain(fullUrl: String) {
        val domain = extractDomain(fullUrl) ?: return
        if (domain.isBlank()) return

        synchronized(trackedDomains) {
            if (!trackedDomains.contains(domain)) {
                trackedDomains.add(domain)
                synchronized(pendingUploads) {
                    pendingUploads.add(domain)
                    savePendingUploads()
                }
                saveDomains()
                Log.d(TAG, "New domain tracked: " + domain)
                if (pendingUploads.size >= BATCH_SIZE) {
                    uploadDomains()
                }
            }
        }
    }

    private fun extractDomain(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            host.removePrefix("www.").lowercase()
        } catch (e: Exception) {
            null
        }
    }

    private fun saveDomains() {
        prefs.edit().putStringSet("tracked_domains", trackedDomains.toSet()).apply()
    }

    private fun savePendingUploads() {
        prefs.edit().putStringSet("pending_uploads", pendingUploads.toSet()).apply()
    }

    fun uploadDomains() {
        val toUpload = synchronized(pendingUploads) {
            if (pendingUploads.isEmpty()) return
            val batch = pendingUploads.toList()
            pendingUploads.clear()
            savePendingUploads()
            batch
        }

        executor.execute {
            try {
                val url = URL(UPLOAD_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val json = buildJsonPayload(toUpload)
                connection.outputStream.use { it.write(json.toByteArray()) }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    Log.d(TAG, "Uploaded " + toUpload.size + " domains successfully")
                    // issue: a successfully uploaded domain used to stay in trackedDomains
                    // forever, which is the *de-dupe* set trackDomain() checks before
                    // queuing anything -- so once a domain was uploaded once, it could never
                    // be reported again even on a later, separate visit. Clear it out of the
                    // tracked set on success so a future visit is treated as new and queued
                    // again, while a *failed* upload deliberately leaves it in trackedDomains
                    // (it's already back in pendingUploads below) so it isn't duplicated.
                    synchronized(trackedDomains) {
                        trackedDomains.removeAll(toUpload.toSet())
                        saveDomains()
                    }
                } else {
                    Log.w(TAG, "Upload failed with code: " + responseCode)
                    synchronized(pendingUploads) {
                        pendingUploads.addAll(toUpload)
                        savePendingUploads()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                synchronized(pendingUploads) {
                    pendingUploads.addAll(toUpload)
                    savePendingUploads()
                }
            }
        }
    }

    private fun buildJsonPayload(domains: List<String>): String {
        val deviceId = prefs.getString("device_id", "unknown") ?: "unknown"
        val timestamp = System.currentTimeMillis()
        val payload = mapOf(
            "device_id" to deviceId,
            "timestamp" to timestamp,
            "domains" to domains
        )
        return com.google.gson.Gson().toJson(payload)
    }

    fun getTrackedCount(): Int = trackedDomains.size
    fun getTrackedDomains(): Set<String> = trackedDomains.toSet()
}
