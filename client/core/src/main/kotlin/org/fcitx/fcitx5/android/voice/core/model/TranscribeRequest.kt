package org.fcitx.fcitx5.android.voice.core.model

/**
 * 与服务端 server/models.py 保持一致的转录请求模型。
 *
 * @property audio   base64 编码的音频数据（PCM / WAV）
 * @property prompt  自定义提示词
 * @property sample  用户提供的样例文本（few-shot 风格化参考）
 * @property style   风格：正式 / 精简 / 礼貌 / 翻译_英文 / 自定义
 */
data class TranscribeRequest(
    val audio: String,
    val prompt: String? = null,
    val sample: String? = null,
    val style: String = "正式"
)