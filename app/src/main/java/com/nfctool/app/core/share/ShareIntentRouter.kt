package com.nfctool.app.core.share

/**
 * 分享 Intent 的类型判定器。
 *
 * ## 为什么需要它
 *
 * **Chrome 分享网页时，Intent 里同时带着文本和图片**：
 * ```kotlin
 * Intent intent = Intent(ACTION_SEND)
 * intent.type = "text/plain"                        // ← MIME 是文本
 * intent.putExtra(EXTRA_SUBJECT, titleOfPage)      // ← 网页标题
 * intent.putExtra(EXTRA_TEXT, urlOfPage)           // ← 网页 URL
 * intent.putExtra(EXTRA_STREAM, screenshotUri)      // ← 页面截图！
 * ```
 * Chrome 会顺手把当前页面截个图放进 `EXTRA_STREAM`。
 *
 * 所以**不能看到 `EXTRA_STREAM` 就当图片** ——
 * 那样 Chrome 分享网页会进「图片写入」页，而用户明明分享的是链接。
 *
 * ## 判定原则
 *
 * **MIME type 是首要判据**（Android 官方文档的示例代码也是这么写的）：
 *
 * | MIME | 判定 | 理由 |
 * |---|---|---|
 * | `text/` 前缀 | 文本 | 明确说了是文本，stream 只是附属（如 Chrome 截图） |
 * | `image/` 前缀 | 图片 | 明确说了是图片 |
 * | 通配 / 空 | 看内容 | 有文本走文本，否则走图片 |
 *
 * 特别地，`text/plain` **同时**带 `EXTRA_TEXT` 和 `EXTRA_STREAM` 时，
 * 走**文本**分支 —— 这正是 Chrome 分享网页的场景。
 */
object ShareIntentRouter {

    /**
     * 判定结果。
     *
     * @param route  处理路线
     * @param text   文本内容（route 为 TEXT 时有效）
     * @param subject 网页标题（可能为空）
     * @param imageUri 图片 URI 的字符串形式（route 为 IMAGE 时有效）
     * @param reason 判定依据，用于调试与界面提示
     */
    data class Decision(
        val route: Route,
        val text: String? = null,
        val subject: String? = null,
        val imageUri: String? = null,
        val reason: String = "",
    )

    /**
     * 处理路线。
     */
    enum class Route {
        /** 按文本处理（链接 / 纯文本 / 位置 / 名片） */
        TEXT,

        /** 按图片处理 */
        IMAGE,

        /** 无法识别 */
        NONE,
    }

    /**
     * 判定一个分享 Intent 该走哪条路。
     *
     * @param mime      Intent 的 MIME type，可能为 null
     * @param extraText `EXTRA_TEXT` 的内容
     * @param subject   `EXTRA_SUBJECT` 的内容（网页标题）
     * @param streamUri `EXTRA_STREAM` 的 URI 字符串形式
     * @param clipText  ClipData 第一项里的文本（部分应用只放这里）
     * @param clipUri   ClipData 第一项里的 URI 字符串形式
     */
    fun decide(
        mime: String?,
        extraText: String?,
        subject: String?,
        streamUri: String?,
        clipText: String? = null,
        clipUri: String? = null,
    ): Decision {
        val title = subject?.trim()?.takeIf { it.isNotEmpty() }
        val directText = extraText?.trim()?.takeIf { it.isNotEmpty() }
        val clipTextValue = clipText?.trim()?.takeIf { it.isNotEmpty() }
        // 文本优先取 EXTRA_TEXT，没有再取 ClipData
        val text = directText ?: clipTextValue

        // URI 同样要剔空白 —— 空白串不是有效 URI
        val stream = streamUri?.trim()?.takeIf { it.isNotEmpty() }
        val clip = clipUri?.trim()?.takeIf { it.isNotEmpty() }

        val mimeValue = mime?.trim()?.lowercase().orEmpty()

        return when {
            // ---- MIME 明确是文本 ----
            // ⚠️ Chrome 分享网页就在这一支：text/plain + EXTRA_STREAM(截图)
            mimeValue.startsWith("text/") -> {
                if (text != null) {
                    Decision(
                        route = Route.TEXT,
                        text = text,
                        subject = title,
                        reason = "MIME 为文本类型，优先按文本处理" +
                            if (stream != null) "（已忽略附带的截图）" else "",
                    )
                } else if (stream != null) {
                    // 类型说是文本却只有 stream，按图片兜底
                    Decision(
                        route = Route.IMAGE,
                        imageUri = stream,
                        subject = title,
                        reason = "MIME 为文本但无文本内容，回退到图片",
                    )
                } else {
                    Decision(Route.NONE, reason = "MIME 为文本但内容为空")
                }
            }

            // ---- MIME 明确是图片 ----
            mimeValue.startsWith("image/") -> {
                val uri = stream ?: clip
                if (uri != null) {
                    Decision(
                        route = Route.IMAGE,
                        imageUri = uri,
                        // 图片分享也可能带文字说明，一并带过去
                        text = text,
                        subject = title,
                        reason = "MIME 为图片类型",
                    )
                } else {
                    Decision(Route.NONE, reason = "MIME 为图片但无图片 URI")
                }
            }

            // ---- MIME 缺失或为通配 ----
            else -> when {
                text != null -> Decision(
                    route = Route.TEXT,
                    text = text,
                    subject = title,
                    reason = "无明确 MIME，按文本处理",
                )

                stream != null -> Decision(
                    route = Route.IMAGE,
                    imageUri = stream,
                    subject = title,
                    reason = "无明确 MIME 且无文本，按图片处理",
                )

                clip != null -> Decision(
                    route = Route.IMAGE,
                    imageUri = clip,
                    subject = title,
                    reason = "无明确 MIME，按 ClipData 图片处理",
                )

                else -> Decision(Route.NONE, reason = "未取到任何可识别内容")
            }
        }
    }
}
