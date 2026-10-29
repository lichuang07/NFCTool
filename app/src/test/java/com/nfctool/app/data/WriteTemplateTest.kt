package com.nfctool.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 写入模板的数据模型测试。
 *
 * 只覆盖纯逻辑（`summary` 的取值优先级），不涉及 DataStore 与 Android API。
 */
class WriteTemplateTest {

    @Test
    fun geoSummaryShowsCoordinates() {
        val t = WriteTemplate(
            name = "家",
            kind = "GEO",
            text = "geo:31.2,121.4",
            latitude = 31.2,
            longitude = 121.4,
        )
        // 坐标优先：地理位置看经纬度比看原始文本有用
        assertEquals("31.2, 121.4", t.summary)
    }

    @Test
    fun uriSummaryPrefersTitle() {
        val t = WriteTemplate(
            kind = "URI",
            text = "https://example.com/a/very/long/path",
            title = "某个网页",
        )
        assertEquals("某个网页", t.summary)
    }

    @Test
    fun textSummaryFallsBackToText() {
        val t = WriteTemplate(kind = "TEXT", text = "hello")
        assertEquals("hello", t.summary)
    }

    @Test
    fun blankTitleFallsBackToText() {
        // 标题是空白字符串时不能当成有标题
        val t = WriteTemplate(kind = "URI", text = "https://example.com", title = "   ")
        assertEquals("https://example.com", t.summary)
    }

    @Test
    fun partialCoordinatesFallBackToText() {
        // 只有纬度、没有经度时不能拼出半截坐标
        val t = WriteTemplate(kind = "GEO", text = "geo:31.2", latitude = 31.2, longitude = null)
        assertEquals("geo:31.2", t.summary)
    }
}
