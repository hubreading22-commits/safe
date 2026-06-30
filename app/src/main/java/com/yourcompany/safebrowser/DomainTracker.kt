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
        private const val UPLOAD_URL = "https://yourserver.com/api/domains"
        private const val BATCH_SIZE = 10
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("DomainTracker", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val trackedDomains = mutableSetOf<String>()
    private val pendingUploads = mutableListOf<String>()

    init {
        val saved = prefs.getStringSet("tracked_domains", emptySet())
        trackedDomains.addAll(saved ?: emptySet())
    }

    fun trackDomain(fullUrl: String) {
        val domain = extractDomain(fullUrl) ?: return
        if (domain.isBlank()) return

        synchronized(trackedDomains) {
            if (!trackedDomains.contains(domain)) {
                trackedDomains.add(domain)
                pendingUploads.add(domain)
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

    fun uploadDomains() {
        val toUpload = synchronized(pendingUploads) {
            if (pendingUploads.isEmpty()) return
            val batch = pendingUploads.toList()
            pendingUploads.clear()
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
                } else {
                    Log.w(TAG, "Upload failed with code: " + responseCode)
                    synchronized(pendingUploads) {
                        pendingUploads.addAll(toUpload)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                synchronized(pendingUploads) {
                    pendingUploads.addAll(toUpload)
                }
            }
        }
    }

    private fun buildJsonPayload(domains: List<String>): String {
        val deviceId = prefs.getString("device_id", "unknown") ?: "unknown"
        val timestamp = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"device_id\": \"")
        sb.append(deviceId)
        sb.append("\",")
        sb.append("\"timestamp\": ")
        sb.append(timestamp)
        sb.append(",")
        sb.append("\"domains\": [")
        for (i in domains.indices) {
            sb.append("\"")
            sb.append(domains[i])
            sb.append("\"")
            if (i < domains.size - 1) sb.append(",")
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }

    fun getTrackedCount(): Int = trackedDomains.size
    fun getTrackedDomains(): Set<String> = trackedDomains.toSet()
}
