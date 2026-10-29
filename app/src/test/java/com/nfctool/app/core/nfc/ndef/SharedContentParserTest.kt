package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SharedContentParser] 单元测试。
 *
 * **重点是经纬度顺序** —— 高德用「经度,纬度」，百度/腾讯用「纬度,经度」，
 * 搞反会写到地球另一半，所以每种来源都要单独断言。
 */
class SharedContentParserTest {

    private val delta = 0.0001

    // ---------- geo: URI ----------

    @Test
    fun `geo URI 解析出坐标与名称`() {
        val parsed = SharedContentParser.parse("geo:39.9042,116.4074?q=天安门")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.GEO, parsed!!.kind)
        assertEquals(39.9042, parsed.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
        assertEquals("天安门", parsed.label)
        assertEquals("WGS-84", parsed.coordinateSystem)
    }

    @Test
    fun `geo URI 带中文名称正确解码`() {
        val parsed = SharedContentParser.parse("geo:39.9,116.4?q=%E5%A4%A9%E5%AE%89%E9%97%A8")
        assertEquals("天安门", parsed?.label)
    }

    @Test
    fun `geo URI 越界坐标不降级为文本而是报错误`() {
        // 纬度 999 非法，GeoUriCodec 会拒绝。
        // 关键：不能静默降级成纯文本 —— 否则用户会写出一个
        // 坐标无效的标签，碰一下毫无反应却不知原因。
        val parsed = SharedContentParser.parse("geo:999,116.4")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.GEO, parsed!!.kind)
        // 错误文案经 AppStrings 本地化，单测环境无 Context 时为空串，
        // 因此这里只断言「识别到格式但判无效」，不断言具体文案
        assertNotNull(parsed.error)
        assertTrue(!parsed.isValid)
    }

    // ---------- 高德（经度在前！） ----------

    @Test
    fun `高德 marker 的 position 是经度在前`() {
        val parsed = SharedContentParser.parse(
            "https://uri.amap.com/marker?position=116.4074,39.9042&name=天安门"
        )
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.GEO, parsed!!.kind)
        // 第一个数是经度，第二个是纬度 —— 顺序不能搞反
        assertEquals(39.9042, parsed.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
        assertEquals("天安门", parsed.label)
        assertEquals("amap", parsed.sourceKey)
        assertEquals("GCJ-02", parsed.coordinateSystem)
    }

    @Test
    fun `高德 scheme 的 lat 与 lon 参数`() {
        val parsed = SharedContentParser.parse(
            "amap://view?sourceApplication=test&lat=39.9042&lon=116.4074&name=天安门"
        )
        assertEquals(39.9042, parsed?.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
        assertEquals("amap", parsed.sourceKey)
    }

    // ---------- 百度（纬度在前） ----------

    @Test
    fun `百度 marker 的 location 是纬度在前`() {
        val parsed = SharedContentParser.parse(
            "https://api.map.baidu.com/marker?location=39.9042,116.4074&title=天安门&content=北京市"
        )
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.GEO, parsed!!.kind)
        assertEquals(39.9042, parsed.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
        assertEquals("天安门", parsed.label)
        assertEquals("baidu", parsed.sourceKey)
        assertEquals("BD-09", parsed.coordinateSystem)
    }

    @Test
    fun `百度 scheme 解析`() {
        val parsed = SharedContentParser.parse(
            "baidumap://map/marker?location=39.9042,116.4074&title=天安门"
        )
        assertEquals(39.9042, parsed?.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
        assertEquals("baidu", parsed.sourceKey)
    }

    // ---------- 腾讯（纬度在前） ----------

    @Test
    fun `腾讯 marker 的 coord 是纬度在前`() {
        val parsed = SharedContentParser.parse(
            "https://apis.map.qq.com/uri/v1/marker?marker=coord:39.9042,116.4074;title:天安门;addr:北京市"
        )
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.GEO, parsed!!.kind)
        assertEquals(39.9042, parsed.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
        assertEquals("天安门", parsed.label)
        assertEquals("qq", parsed.sourceKey)
    }

    @Test
    fun `腾讯 scheme 解析`() {
        val parsed = SharedContentParser.parse(
            "qqmap://map/marker?marker=coord:39.9042,116.4074;title:天安门"
        )
        assertEquals(39.9042, parsed?.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
    }

    // ---------- Google ----------

    @Test
    fun `Google 地图 place 链接从路径提取坐标`() {
        val parsed = SharedContentParser.parse(
            "https://www.google.com/maps/place/%E5%A4%A9%E5%AE%89%E9%97%A8/@39.9042,116.4074,15z/data=!3m1"
        )
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.GEO, parsed!!.kind)
        assertEquals(39.9042, parsed.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
        assertEquals("天安门", parsed.label)
    }

    @Test
    fun `Google 地图 q 参数解析`() {
        val parsed = SharedContentParser.parse("https://maps.google.com/?q=39.9042,116.4074")
        assertEquals(39.9042, parsed?.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
    }

    @Test
    fun `Google scheme 解析`() {
        val parsed = SharedContentParser.parse("comgooglemaps://?q=39.9042,116.4074")
        assertEquals(39.9042, parsed?.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
    }

    // ---------- 普通链接与文本 ----------

    @Test
    fun `普通网址识别为链接而不是坐标`() {
        val parsed = SharedContentParser.parse("https://example.com/page?id=123")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.URI, parsed!!.kind)
        assertEquals("https://example.com/page?id=123", parsed.uri)
    }

    @Test
    fun `普通网址即使带坐标样参数也不误判`() {
        // 坐标不是顶层 query 的 position/location/q，不应被当成地图链接
        val parsed = SharedContentParser.parse("https://example.com/?x=39.9,116.4")
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
    }

    @Test
    fun `纯文本识别为文本`() {
        val parsed = SharedContentParser.parse("会议室 B-203，周三 10:00")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.TEXT, parsed!!.kind)
        assertEquals("会议室 B-203，周三 10:00", parsed.text)
    }

    @Test
    fun `空内容返回 null`() {
        assertNull(SharedContentParser.parse(null))
        assertNull(SharedContentParser.parse(""))
        assertNull(SharedContentParser.parse("   "))
    }

    @Test
    fun `分享内容两端空白会被裁掉`() {
        val parsed = SharedContentParser.parse("  https://example.com  ")
        assertEquals("https://example.com", parsed?.uri)
    }

    // ---------- host 校验（非地图域名不得误判） ----------

    @Test
    fun `非高德域名带 position 参数时按普通链接处理`() {
        // position 是极常见的 query 参数名，不校验 host 会把任意网页判成地图坐标
        val parsed = SharedContentParser.parse(
            "https://example.com/wiki?position=116.4074,39.9042"
        )
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
        assertEquals("https://example.com/wiki?position=116.4074,39.9042", parsed?.uri)
    }

    @Test
    fun `非百度域名带 location 参数时按普通链接处理`() {
        val parsed = SharedContentParser.parse(
            "https://example.com/article?location=39.9042,116.4074"
        )
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
    }

    @Test
    fun `非腾讯域名带 marker 参数时按普通链接处理`() {
        val parsed = SharedContentParser.parse(
            "https://example.com/t/123?marker=coord:39.9042,116.4074;title:x"
        )
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
    }

    @Test
    fun `Google 搜索链接不按地图处理`() {
        val parsed = SharedContentParser.parse(
            "https://www.google.com/search?q=39.9042,116.4074"
        )
        assertEquals(SharedContentParser.Kind.URI, parsed?.kind)
    }

    @Test
    fun `Google 地图 www 域名带 maps 路径时仍识别`() {
        val parsed = SharedContentParser.parse(
            "https://www.google.com/maps?q=39.9042,116.4074"
        )
        assertEquals(SharedContentParser.Kind.GEO, parsed?.kind)
        assertEquals(39.9042, parsed?.latitude!!, delta)
    }

    @Test
    fun `高德子域名的 position 仍然识别`() {
        val parsed = SharedContentParser.parse(
            "https://m.amap.com/marker?position=116.4074,39.9042"
        )
        assertEquals(SharedContentParser.Kind.GEO, parsed?.kind)
        assertEquals(39.9042, parsed?.latitude!!, delta)
        assertEquals(116.4074, parsed.longitude!!, delta)
    }

    // ---------- 健壮性 ----------

    @Test
    fun `坐标格式非法时降级为普通链接而不崩溃`() {
        val parsed = SharedContentParser.parse("https://uri.amap.com/marker?position=abc,def")
        assertNotNull(parsed)
        // position 解析不出来，但整体仍是合法链接 → 当普通链接处理
        assertEquals(SharedContentParser.Kind.URI, parsed!!.kind)
        assertEquals("https://uri.amap.com/marker?position=abc,def", parsed.uri)
    }

    @Test
    fun `地图 scheme 内容非法时报错误而非降级`() {
        val parsed = SharedContentParser.parse("amap://view?sourceApplication=test")
        assertNotNull(parsed)
        assertEquals(SharedContentParser.Kind.GEO, parsed!!.kind)
        assertNotNull(parsed.error)
        assertTrue(!parsed.isValid)
    }

    @Test
    fun `负数坐标可正确解析`() {
        val parsed = SharedContentParser.parse("geo:-33.8568,151.2153?q=悉尼歌剧院")
        assertEquals(-33.8568, parsed?.latitude!!, delta)
        assertEquals(151.2153, parsed.longitude!!, delta)
    }

    @Test
    fun `URL 编码的地点名正确解码`() {
        val parsed = SharedContentParser.parse(
            "https://api.map.baidu.com/marker?location=39.9042,116.4074&title=%E5%8C%97%E4%BA%AC%E5%8F%B0"
        )
        assertEquals("北京台", parsed?.label)
    }

    @Test
    fun `解析结果的 kind 与内容自洽`() {
        val geo = SharedContentParser.parse("geo:39.9,116.4")
        assertTrue(geo?.kind == SharedContentParser.Kind.GEO)
        assertTrue(geo?.latitude != null && geo.longitude != null)

        val uri = SharedContentParser.parse("https://example.com")
        assertTrue(uri?.kind == SharedContentParser.Kind.URI)
        assertTrue(uri?.uri != null)
    }
}
