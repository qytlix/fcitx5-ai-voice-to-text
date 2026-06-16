package org.fcitx.fcitx5.android.voice.core.model

import java.util.UUID

/**
 * 语音输入反馈事件 — 记录一次完整语音输入会话的"上屏 vs 最终文本"差异。
 *
 * 由 Fcitx5 主应用的 [VoiceFeedbackCollector] 采集，用于：
 * 1. 对比 AI 风格化输出与用户最终接受文本的差异
 * 2. 积累训练数据以优化风格化 prompt / fine-tune 模型
 * 3. 发现 badcase 进行人工标注
 *
 * @property id           事件唯一 ID（客户端生成 UUID）
 * @property sessionId    语音输入会话 ID（一次 startRecording → stopRecording 为一个会话）
 * @property originalText ASR 原始转写文字
 * @property styledText   AI 风格化后上屏的文字
 * @property finalText    用户最终确认的文字（采集自 InputConnection 后续读取）
 * @property style        使用的风格（正式/精简/礼貌/翻译_英文/自定义）
 * @property prompt       自定义提示词（如果有）
 * @property durationMs   处理耗时（毫秒）
 * @property timestamp    事件发生时间（Unix 毫秒）
 */
data class VoiceFeedbackEvent(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val originalText: String,
    val styledText: String,
    val finalText: String,
    val style: String,
    val prompt: String? = null,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 转换为上传请求 DTO（与 server /v1/feedback 接口对齐）。
     */
    fun toUploadRequest(): FeedbackUploadRequest = FeedbackUploadRequest(
        client_event_id = id,
        session_id = sessionId,
        original_text = originalText,
        styled_text = styledText,
        final_text = finalText,
        style = style,
        prompt = prompt,
        duration_ms = durationMs,
        timestamp = timestamp
    )

    companion object {
        /**
         * 差异最小比例阈值：只有当 styledText 与 finalText 的
         * 差异超过此比例时，才视为有效反馈。
         *
         * 避免记录无意义的标点/空格微调。
         */
        const val MIN_DIFF_RATIO = 0.1
    }
}
