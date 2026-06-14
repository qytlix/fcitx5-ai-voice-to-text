package org.fcitx.fcitx5.android.voice.core.model

/**
 * 与服务端 server/models.py 保持一致的转录响应模型。
 *
 * @property text           风格化后的文字
 * @property originalText   ASR 原始转写文字
 * @property durationMs     服务端处理耗时（毫秒）
 * @property error          错误信息，成功时为 null
 */
data class TranscribeResponse(
    val text: String,
    val original_text: String,
    val duration_ms: Int? = null,
    val error: String? = null
)