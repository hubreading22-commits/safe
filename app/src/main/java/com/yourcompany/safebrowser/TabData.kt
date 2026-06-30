package com.yourcompany.safebrowser

import android.webkit.WebView

data class TabData(
    val id: Int,
    var url: String = "https://www.google.com",
    var title: String = "New Tab",
    var favicon: android.graphics.Bitmap? = null,
    var webView: WebView? = null,
    var isLoading: Boolean = false
)
