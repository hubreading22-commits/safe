package com.yourcompany.safebrowser

object SessionHistoryManager {
    data class HistoryEntry(val url: String, val title: String, val timestamp: Long)
    
    private val history = mutableListOf<HistoryEntry>()

    fun add(url: String, title: String) {
        if (url == "about:blank" || url.startsWith("safebrowser://")) return
        if (history.isNotEmpty() && history.last().url == url) return
        history.add(HistoryEntry(url, title, System.currentTimeMillis()))
    }

    fun getAll(): List<HistoryEntry> = history.toList().reversed()

    fun clear() {
        history.clear()
    }
}
