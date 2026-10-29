package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 浏览器分享网页的文本形态处理。
 *
 * **背景事实**（已核对 Chrome 源码与官方文档）：
 * Chrome 分享网页时发的是
 * `ACTION_SEND` + `EXTRA_TEXT` = 页面 URL + `EXTRA_SUBJECT` = 页面标题。
 *
 * 但**不是所有浏览器都这样**：
 *  - Chrome / Edge / Firefox：`EXTRA_TEXT` 纯 URL，标题在 `EXTRA_SUBJECT`
 *  - 部分国产浏览器、微信内置浏览器、邮件客户端：`EXTRA_TEXT` 是
 *    「标题 + 换行 + URL」或「标题 空格 URL」的**组合文本**
 *  - 有的还会在末尾追加一句分享语
 *
 * 所以解析器必须能从**混合文本里把 URL 摘出来**，
 * 否则用户分享出来的是一坨带标题的垃圾文本，碰一下完全没法用。
 */
class BrowserShareTest {

    @Test
    fun `纯 URL 直接识别为链接`() {
        val parsed = SharedContentParser.parse("https://example.com/page")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.URI, parsed!!.kind)
        assertEquals("https://example.com/page", parsed.uri)
    }

    @Test
    fun `标题加换行加 URL 能摘出 URL`() {
        val parsed = SharedContentParser.parse("示例网站 - 最好的示例\nhttps://example.com/page")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.URI, parsed!!.kind)
        assertEquals("https://example.com/page", parsed.uri)
    }

    @Test
    fun `标题加空格加 URL 能摘出 URL`() {
        val parsed = SharedContentParser.parse("示例网站 https://example.com/page")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.URI, parsed!!.kind)
        assertEquals("https://example.com/page", parsed.uri)
    }

    @Test
    fun `URL 后面跟分享语仍能摘出 URL`() {
        val parsed = SharedContentParser.parse(
            "https://example.com/page\n\n来自 Chrome 分享"
        )
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
        assertEquals("https://example.com/page", parsed?.uri)
    }

    @Test
    fun `混合文本中的链接会带上标题`() {
        val parsed = SharedContentParser.parse("示例网站 - 最好的示例\nhttps://example.com/page")
        assertEquals("示例网站 - 最好的示例", parsed?.title)
    }

    @Test
    fun `纯 URL 时标题为空`() {
        val parsed = SharedContentParser.parse("https://example.com/page")
        assertEquals(null, parsed?.title)
    }

    @Test
    fun `带标题的链接仍然走链接类型而不是文本类型`() {
        // 带标题说明这明显是网页分享，应该按链接处理
        val parsed = SharedContentParser.parse("示例网站\nhttps://example.com/page")
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
    }

    @Test
    fun `纯文本中不含链接时保持文本类型`() {
        val parsed = SharedContentParser.parse("这是一段普通文字，没有链接")
        assertEquals(SharedContentParser.Kind.TEXT, parsed?.kind)
    }

    @Test
    fun `文本中的链接不会被当成地图链接`() {
        val parsed = SharedContentParser.parse("看看这个 https://example.com/page 很有意思")
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
        assertEquals("https://example.com/page", parsed?.uri)
    }

    @Test
    fun `地图 URL 里的坐标逗号不会被截断`() {
        // ⚠️ 关键：URL 正则不能排除 ASCII 逗号，
        // 否则 ?q=39.9042,116.4074 会被截成 ?q=39.9042，坐标就丢了
        val parsed = SharedContentParser.parse("https://maps.google.com/?q=39.9042,116.4074")
        assertEquals(SharedContentParser.Kind.GEO, parsed?.kind)
        assertEquals(39.9042, parsed?.latitude!!, 0.0001)
        assertEquals(116.4074, parsed.longitude!!, 0.0001)
    }

    @Test
    fun `混合文本里的地图 URL 坐标同样完整`() {
        val parsed = SharedContentParser.parse(
            "北京天安门\nhttps://api.map.baidu.com/marker?location=39.9042,116.4074&title=天安门"
        )
        assertEquals(SharedContentParser.Kind.GEO, parsed?.kind)
        assertEquals(39.9042, parsed?.latitude!!, 0.0001)
        assertEquals(116.4074, parsed.longitude!!, 0.0001)
    }

    @Test
    fun `URL 末尾的中文标点被剥离`() {
        val parsed = SharedContentParser.parse("看看 https://example.com/page。")
        assertEquals("https://example.com/page", parsed?.uri)
    }

    @Test
    fun `URL 末尾的英文逗号被剥离但坐标逗号保留`() {
        val parsed = SharedContentParser.parse("https://example.com/page,")
        assertEquals("https://example.com/page", parsed?.uri)
    }
}
