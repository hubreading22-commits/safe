package com.readinghub.safebrowser

import com.google.gson.annotations.SerializedName

data class BlocklistConfig(
    @SerializedName("version")
    val version: String? = null,

    @SerializedName("updated")
    val updated: String? = null,

    @SerializedName("blocked_domains")
    val blockedDomains: List<String>? = null,

    @SerializedName("blocked_keywords")
    val blockedKeywords: List<String>? = null,

    @SerializedName("video_blocking")
    val videoBlocking: Boolean? = true
)
