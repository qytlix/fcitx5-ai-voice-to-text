package org.fcitx.fcitx5.android.voice.core.domain

/**
 * 转录流程状态机。
 *
 * 描述一次完整的语音输入→转写→上屏流程的状态变迁。
 * ViewModel 通过 StateFlow<TranscribeState> 驱动 UI 更新。
 */
sealed class TranscribeState {

    /** 空闲，等待用户触发 */
    data object Idle : TranscribeState()

    /** 等待录音权限 */
    data object WaitingForPermission : TranscribeState()

    /** 录音中 */
    data class Recording(
        val durationMs: Long = 0L
    ) : TranscribeState()

    /** 音频处理中（编码 + 网络请求） */
    data object Processing : TranscribeState()

    /** 完成，结果已就绪 */
    data class Success(
        val text: String,
        val originalText: String,
        val durationMs: Long
    ) : TranscribeState()

    /** 失败 */
    data class Error(
        val message: String,
        val code: Int? = null
    ) : TranscribeState()
}