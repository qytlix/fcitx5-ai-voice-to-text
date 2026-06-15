package org.fcitx.fcitx5.android.voice.core.model

/**
 * 反馈上传请求 DTO — 与服务端 POST /v1/feedback JSON body 保持一致。
 *
 * 字段使用 snake_case 命名（与 server/models.py 的 Pydantic 模型对齐），
 * 通过 Gson @SerializedName 注解映射到 Kotlin 属性。
 *
 * @see VoiceFeedbackEvent.toUploadRequest
 */
data class FeedbackUploadRequest(
    /** 客户端生成的唯一事件 ID（服务端用于去重） */
    val client_event_id: String,
    /** 语音输入会话 ID */
    val session_id: String,
    /** ASR 原始转写文字 */
    val original_text: String,
    /** AI 风格化后上屏的文字 */
    val styled_text: String,
    /** 用户最终确认的文字 */
    val final_text: String,
    /** 使用的风格 */
    val style: String,
    /** 自定义提示词 */
    val prompt: String? = null,
    /** 处理耗时（毫秒） */
    val duration_ms: Long,
    /** 事件发生时间（Unix 毫秒） */
    val timestamp: Long
)
