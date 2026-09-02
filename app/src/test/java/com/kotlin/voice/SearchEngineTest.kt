package com.kotlin.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Uri.encode is an Android API, so these run under Robolectric rather than plain JVM JUnit. */
@RunWith(RobolectricTestRunner::class)
class SearchEngineTest {

    @Test
    fun `each engine builds its own search URL`() {
        assertEquals(
            "https://www.google.com/search?q=hello%20world",
            SearchEngine.GOOGLE.urlFor("hello world"),
        )
        assertEquals(
            "https://www.youtube.com/results?search_query=hello%20world",
            SearchEngine.YOUTUBE.urlFor("hello world"),
        )
        assertEquals(
            "https://www.bing.com/search?q=hello%20world",
            SearchEngine.BING.urlFor("hello world"),
        )
        assertEquals(
            "https://duckduckgo.com/?q=hello%20world",
            SearchEngine.DUCKDUCKGO.urlFor("hello world"),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed before encoding`() {
        assertEquals(
            "https://www.google.com/search?q=hello%20world",
            SearchEngine.GOOGLE.urlFor("   hello world   "),
        )
    }

    @Test
    fun `query separators are encoded so they cannot inject extra parameters`() {
        assertEquals(
            "https://www.google.com/search?q=a%26tbm%3Disch%23x",
            SearchEngine.GOOGLE.urlFor("a&tbm=isch#x"),
        )
    }

    @Test
    fun `non-latin speech results survive encoding`() {
        assertEquals(
            "https://www.google.com/search?q=%E4%BD%A0%E5%A5%BD",
            SearchEngine.GOOGLE.urlFor("你好"),
        )
    }
}
