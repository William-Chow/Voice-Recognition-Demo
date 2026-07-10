package com.kotlin.voice

import android.net.Uri

/**
 * Supported search targets. Each builds a URL from a (URL-encoded) query.
 */
enum class SearchEngine(val label: String, private val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    YOUTUBE("YouTube", "https://www.youtube.com/results?search_query=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s");

    fun urlFor(query: String): String = template.format(Uri.encode(query.trim()))
}
