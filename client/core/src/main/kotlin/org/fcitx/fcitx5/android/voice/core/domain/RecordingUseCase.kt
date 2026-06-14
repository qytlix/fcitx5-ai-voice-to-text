package org.fcitx.fcitx5.android.voice.core.domain

import org.fcitx.fcitx5.android.voice.core.bridge.AudioRecorder
import java.util.Base64

/**
 * 录音管理用例 — 管理录音生命周期与权限检查。
 *
 * @param audioRecorder 录音接口
 */
class RecordingUseCase(
    private val audioRecorder: AudioRecorder
) {

    /**
     * 检查录音权限是否已获取。
     *
     * 在 Android 上对应检查 Manifest.permission.RECORD_AUDIO。
     * 纯 Kotlin 层不依赖 Android Context，此方法返回布尔值，
     * 由上层（ViewModel/Activity）处理权限请求流程。
     */
    fun isPermissionGranted(): Boolean {
        // 纯 Kotlin 层无法检查实际权限，
        // 此方法始终返回 true，由 Android 层覆盖。
        // 实际检查在 AndroidAudioRecorder 的
        // 实现或 ViewModel 中完成。
        return true
    }

    /**
     * 估算录音时长。
     *
     * 基于 base64 音频数据的长度和采样率（默认 16kHz 16-bit 单声道）
     * 估算录音时长。
     *
     * @param base64Audio base64 编码的 WAV 音频数据
     * @return 估算时长（毫秒），无法估算时返回 0
     */
    fun estimateDuration(base64Audio: String): Long {
        if (base64Audio.isEmpty()) return 0L

        return try {
            // base64 解码后的字节数
            val decodedBytes = Base64.getDecoder().decode(base64Audio)
            // WAV 头通常占 44 字节
            val pcmBytes = decodedBytes.size.coerceAtLeast(44) - 44
            // 16kHz 16-bit 单声道 = 32000 bytes/sec
            val bytesPerSecond = 16000 * 2 // 采样率 × 字节/样本
            (pcmBytes.toLong() * 1000) / bytesPerSecond
        } catch (e: IllegalArgumentException) {
            // base64 解码失败
            0L
        }
    }
}