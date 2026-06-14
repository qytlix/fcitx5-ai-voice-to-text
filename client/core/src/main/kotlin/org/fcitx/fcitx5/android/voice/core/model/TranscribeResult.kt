package org.fcitx.fcitx5.android.voice.core.model

/**
 * 转录结果数据类（客户端完整流程结果，比 TranscribeResponse 包含更多信息）。
 *
 * @property text             风格化后的最终文字
 * @property originalText     ASR 原始转写文字
 * @property serverDurationMs 服务端处理耗时（毫秒）
 * @property totalDurationMs  客户端全流程耗时（录音 + 编码 + 网络 + 上屏）
 */
data class TranscribeResult(
    val text: String,
    val originalText: String,
    val serverDurationMs: Long? = null,
    val totalDurationMs: Long = 0L
)