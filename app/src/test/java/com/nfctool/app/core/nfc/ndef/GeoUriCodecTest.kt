package com.nfctool.app.core.nfc.ndef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * geo: URI（RFC 5870）编解码测试。
 */
class GeoUriCodecTest {

    @Test
    fun `解析基本坐标`() {
        val geo = GeoUriCodec.parse("geo:39.9042,116.4074")
        assertNotNull(geo)
        assertEquals(39.9042, geo!!.latitude, 0.00001)
        assertEquals(116.4074, geo.longitude, 0.00001)
        assertNull(geo.altitude)
        assertNull(geo.label)
    }

    @Test
    fun `解析带海拔与名称`() {
        val geo = GeoUriCodec.parse("geo:39.9042,116.4074,52?q=%E5%A4%A9%E5%AE%89%E9%97%A8")
        assertNotNull(geo)
        assertEquals(52.0, geo!!.altitude!!, 0.001)
        assertEquals("天安门", geo.label)
    }

    @Test
    fun `解析精度与缩放参数`() {
        val geo = GeoUriCodec.parse("geo:39.9,116.4;u=35?q=某地&z=16")
        assertNotNull(geo)
        assertEquals(35.0, geo!!.uncertainty!!, 0.001)
        assertEquals(16, geo.zoom)
        assertEquals("某地", geo.label)
    }

    @Test
    fun `经纬度越界返回 null`() {
        assertNull(GeoUriCodec.parse("geo:91.0,116.4"))
        assertNull(GeoUriCodec.parse("geo:39.9,181.0"))
    }

    @Test
    fun `非 geo URI 返回 null`() {
        assertNull(GeoUriCodec.parse("https://example.com"))
        assertNull(GeoUriCodec.parse("geo:"))
        assertNull(GeoUriCodec.parse("geo:abc,def"))
        assertNull(GeoUriCodec.parse("geo:39.9"))
    }

    @Test
    fun `生成的 URI 可被自身解析`() {
        val uri = GeoUriCodec.build(
            latitude = 39.9042,
            longitude = 116.4074,
            altitude = 52.0,
            uncertainty = 35.0,
            label = "天安门",
            zoom = 16,
        )
        assertTrue(uri.startsWith("geo:"))
        val geo = GeoUriCodec.parse(uri)
        assertNotNull(geo)
        assertEquals(39.9042, geo!!.latitude, 0.00001)
        assertEquals(116.4074, geo.longitude, 0.00001)
        assertEquals(52.0, geo.altitude!!, 0.001)
        assertEquals(35.0, geo.uncertainty!!, 0.001)
        assertEquals("天安门", geo.label)
        assertEquals(16, geo.zoom)
    }

    @Test
    fun `中文名称正确编解码`() {
        val uri = GeoUriCodec.build(39.9, 116.4, label = "北京·天安门广场")
        val geo = GeoUriCodec.parse(uri)
        assertNotNull(geo)
        assertEquals("北京·天安门广场", geo!!.label)
    }

    @Test
    fun `越界坐标构建失败`() {
        assertTrue(runCatching { GeoUriCodec.build(91.0, 116.4) }.isFailure)
        assertTrue(runCatching { GeoUriCodec.build(39.9, 181.0) }.isFailure)
    }

    @Test
    fun `识别 geo URI`() {
        assertTrue(GeoUriCodec.isGeoUri("geo:39.9,116.4"))
        assertTrue(GeoUriCodec.isGeoUri("GEO:39.9,116.4"))
        assertFalse(GeoUriCodec.isGeoUri("https://example.com"))
        assertFalse(GeoUriCodec.isGeoUri(null))
    }

    @Test
    fun `生成的 URI 去掉多余小数位`() {
        assertEquals("geo:39.9,116.4", GeoUriCodec.build(39.9, 116.4))
    }
}
