package com.nfctool.app.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShareIntentRouter] 单元测试。
 *
 * **最关键的是 Chrome 分享网页那个场景** ——
 * Intent 里同时有 `EXTRA_TEXT`（URL）和 `EXTRA_STREAM`（页面截图），
 * MIME 是 `text/plain`。早期实现「看到 stream 就当图片」，
 * 导致 Chrome 分享网页会进「图片写入」页，用户明明分享的是链接。
 */
class ShareIntentRouterTest {

    // ---------- Chrome 分享网页（核心场景） ----------

    @Test
    fun `Chrome 分享网页走文本而不是图片`() {
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = "https://example.com/article",
            subject = "示例文章",
            streamUri = "content://com.android.chrome/screenshot.png",
        )
        assertEquals(ShareIntentRouter.Route.TEXT, decision.route)
        assertEquals("https://example.com/article", decision.text)
        assertEquals("示例文章", decision.subject)
        assertNull("不应带出图片 URI", decision.imageUri)
    }

    @Test
    fun `Chrome 分享网页的判定理由说明了忽略截图`() {
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = "https://example.com",
            subject = "标题",
            streamUri = "content://com.android.chrome/screenshot.png",
        )
        assertTrue(decision.reason.contains("忽略"))
    }

    @Test
    fun `Firefox 分享网页同样走文本`() {
        // Firefox 不带截图，但判定结果应一致
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = "https://example.com",
            subject = "标题",
            streamUri = null,
        )
        assertEquals(ShareIntentRouter.Route.TEXT, decision.route)
    }

    @Test
    fun `text 子类型同样按文本处理`() {
        listOf("text/plain", "text/html", "text/uri-list", "TEXT/PLAIN").forEach { mime ->
            val decision = ShareIntentRouter.decide(
                mime = mime,
                extraText = "https://example.com",
                subject = null,
                streamUri = "content://x/s.png",
            )
            assertEquals("MIME=$mime 应走文本", ShareIntentRouter.Route.TEXT, decision.route)
        }
    }

    // ---------- 相册分享图片 ----------

    @Test
    fun `相册分享图片走图片`() {
        val decision = ShareIntentRouter.decide(
            mime = "image/jpeg",
            extraText = null,
            subject = null,
            streamUri = "content://media/external/images/media/123",
        )
        assertEquals(ShareIntentRouter.Route.IMAGE, decision.route)
        assertEquals("content://media/external/images/media/123", decision.imageUri)
    }

    @Test
    fun `分享图片附带文字说明时仍走图片但保留文字`() {
        val decision = ShareIntentRouter.decide(
            mime = "image/png",
            extraText = "看这个",
            subject = null,
            streamUri = "content://media/456",
        )
        assertEquals(ShareIntentRouter.Route.IMAGE, decision.route)
        assertEquals("看这个", decision.text)
    }

    @Test
    fun `image 通配 MIME 走图片`() {
        val decision = ShareIntentRouter.decide(
            mime = "image/*",
            extraText = null,
            subject = null,
            streamUri = "content://media/789",
        )
        assertEquals(ShareIntentRouter.Route.IMAGE, decision.route)
    }

    // ---------- MIME 缺失 / 通配 ----------

    @Test
    fun `MIME 缺失有文本走文本`() {
        val decision = ShareIntentRouter.decide(
            mime = null,
            extraText = "一些文字",
            subject = null,
            streamUri = null,
        )
        assertEquals(ShareIntentRouter.Route.TEXT, decision.route)
    }

    @Test
    fun `MIME 通配有文本走文本`() {
        val decision = ShareIntentRouter.decide(
            mime = "*/*",
            extraText = "https://example.com",
            subject = null,
            streamUri = "content://x/1.png",
        )
        assertEquals(ShareIntentRouter.Route.TEXT, decision.route)
    }

    @Test
    fun `MIME 通配无文本有图片走图片`() {
        val decision = ShareIntentRouter.decide(
            mime = "*/*",
            extraText = null,
            subject = null,
            streamUri = "content://x/1.png",
        )
        assertEquals(ShareIntentRouter.Route.IMAGE, decision.route)
    }

    @Test
    fun `MIME 缺失时从 ClipData 取图片`() {
        val decision = ShareIntentRouter.decide(
            mime = null,
            extraText = null,
            subject = null,
            streamUri = null,
            clipUri = "content://x/clip.png",
        )
        assertEquals(ShareIntentRouter.Route.IMAGE, decision.route)
        assertEquals("content://x/clip.png", decision.imageUri)
    }

    @Test
    fun `MIME 缺失时从 ClipData 取文本`() {
        val decision = ShareIntentRouter.decide(
            mime = null,
            extraText = null,
            subject = null,
            streamUri = null,
            clipText = "来自剪贴板的文字",
        )
        assertEquals(ShareIntentRouter.Route.TEXT, decision.route)
        assertEquals("来自剪贴板的文字", decision.text)
    }

    // ---------- 边界 ----------

    @Test
    fun `内容全空返回 NONE`() {
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = null,
            subject = null,
            streamUri = null,
        )
        assertEquals(ShareIntentRouter.Route.NONE, decision.route)
        assertNotNull(decision.reason)
    }

    @Test
    fun `空白字符串等同空值`() {
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = "   ",
            subject = "  ",
            streamUri = "   ",
        )
        assertEquals(ShareIntentRouter.Route.NONE, decision.route)
    }

    @Test
    fun `文本为空白时回退到 ClipData 文本`() {
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = "  ",
            subject = null,
            streamUri = null,
            clipText = "ClipData 里的文本",
        )
        assertEquals(ShareIntentRouter.Route.TEXT, decision.route)
        assertEquals("ClipData 里的文本", decision.text)
    }

    @Test
    fun `声称是文本但只有图片时回退到图片`() {
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = null,
            subject = null,
            streamUri = "content://x/1.png",
        )
        assertEquals(ShareIntentRouter.Route.IMAGE, decision.route)
    }

    @Test
    fun `声称是图片但没有 URI 时返回 NONE`() {
        val decision = ShareIntentRouter.decide(
            mime = "image/jpeg",
            extraText = null,
            subject = null,
            streamUri = null,
        )
        assertEquals(ShareIntentRouter.Route.NONE, decision.route)
    }

    @Test
    fun `标题为空字符串时当作没有标题`() {
        val decision = ShareIntentRouter.decide(
            mime = "text/plain",
            extraText = "https://example.com",
            subject = "   ",
            streamUri = null,
        )
        assertNull(decision.subject)
    }
}
