package org.fcitx.fcitx5.android.voice.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeState
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase
import org.fcitx.fcitx5.android.voice.core.network.TranscribeException

/**
 * 语音输入 ViewModel — 管理 UI 状态与用户交互。
 *
 * 持有 [TranscribeUseCase] 的引用，通过 StateFlow 暴露 UI 状态。
 * UI 层 collect 此 Flow 来驱动视觉更新（按钮状态、结果展示、错误提示）。
 *
 * 状态流转：
 *   Idle → Recording → Processing → Success / Error → Idle
 */
class VoiceInputViewModel(
    private val transcribeUseCase: TranscribeUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "VoiceInputViewModel"
    }

    private val _state = MutableStateFlow<TranscribeState>(TranscribeState.Idle)
    val state: StateFlow<TranscribeState> = _state.asStateFlow()

    /** 当前选中的风格 */
    private val _currentStyle = MutableStateFlow("正式")
    val currentStyle: StateFlow<String> = _currentStyle.asStateFlow()

    /** 最后一次成功的结果文字（供 UI 复制） */
    private val _lastResultText = MutableStateFlow<String?>(null)
    val lastResultText: StateFlow<String?> = _lastResultText.asStateFlow()

    /** 最后一次成功的原始转写文字 */
    private val _lastOriginalText = MutableStateFlow<String?>(null)
    val lastOriginalText: StateFlow<String?> = _lastOriginalText.asStateFlow()

    /** 当前正在执行的录音/转录任务 */
    private var currentJob: Job? = null

    /**
     * 当用户点击"开始录音"按钮时调用。
     *
     * 如果当前是 Idle 状态 → 开始录音。
     * 如果当前是 Recording 状态 → 停止录音并开始转录。
     * 如果当前是 Error 状态 → 重置并开始录音。
     */
    fun onStartRecording() {
        val currentState = _state.value
        when (currentState) {
            is TranscribeState.Idle,
            is TranscribeState.Error,
            is TranscribeState.WaitingForPermission -> {
                startTranscribeFlow()
            }
            is TranscribeState.Recording -> {
                // 已经在录音中（按钮应显示"停止"），见 onStopRecording
                Log.w(TAG, "已经在录音中")
            }
            is TranscribeState.Processing,
            is TranscribeState.Success -> {
                Log.w(TAG, "当前状态 ($currentState) 下不能开始录音")
            }
        }
    }

    /**
     * 当用户点击"停止录音"按钮时调用。
     */
    fun onStopRecording() {
        val currentState = _state.value
        if (currentState !is TranscribeState.Recording) {
            Log.w(TAG, "当前不在录音状态，无法停止")
            return
        }
        // 录音的停止在 TranscribeUseCase 内部处理，
        // 此处通过取消当前 Job 来触发 stopRecording
        currentJob?.cancel()
        currentJob = null
        // 启动转录流程（会自动停止录音）
        startTranscribeFlow(wasRecording = true)
    }

    /**
     * 设置当前选中的风格选项。
     */
    fun setStyle(style: String) {
        _currentStyle.value = style
    }

    /**
     * 清除上次的结果文字。
     */
    fun clearResult() {
        _lastResultText.value = null
        _lastOriginalText.value = null
    }

    /**
     * 重置状态到 Idle（例如错误确认后）。
     */
    fun resetToIdle() {
        _state.value = TranscribeState.Idle
    }

    /**
     * 启动完整转录流程（录音 → 发送 → 上屏）。
     *
     * @param wasRecording 是否已经处于录音中（用户点击停止时触发）
     */
    private fun startTranscribeFlow(wasRecording: Boolean = false) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                _state.value = TranscribeState.Recording(durationMs = 0L)
                Log.d(TAG, "开始录音 (style=${_currentStyle.value})")

                val result = transcribeUseCase.execute(
                    style = _currentStyle.value
                )

                _state.value = TranscribeState.Success(
                    text = result.text,
                    originalText = result.originalText,
                    durationMs = result.totalDurationMs
                )
                _lastResultText.value = result.text
                _lastOriginalText.value = result.originalText

                Log.d(TAG, "转录成功: ${result.text} (${result.totalDurationMs}ms)")

            } catch (e: TranscribeException) {
                val message = when (e) {
                    is TranscribeException.RecordingFailed -> "录音失败"
                    is TranscribeException.EncodingFailed -> "音频处理失败"
                    is TranscribeException.NetworkError -> "网络连接失败，请检查服务器"
                    is TranscribeException.ServerError -> "服务器错误 (${e.code})"
                    is TranscribeException.Timeout -> "请求超时，请重试"
                    is TranscribeException.ServerReturnedError -> "服务返回错误"
                    is TranscribeException.Unknown -> "未知错误"
                }
                _state.value = TranscribeState.Error(message = message)
                Log.e(TAG, "转录失败: $message", e)

            } catch (e: Exception) {
                _state.value = TranscribeState.Error(message = "未知错误: ${e.message}")
                Log.e(TAG, "转录失败", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
    }
}