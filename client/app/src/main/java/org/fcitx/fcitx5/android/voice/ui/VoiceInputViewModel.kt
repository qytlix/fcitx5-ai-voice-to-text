package org.fcitx.fcitx5.android.voice.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.voice.core.bridge.SessionIdProvider
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeState
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase
import org.fcitx.fcitx5.android.voice.core.network.TranscribeException

/**
 * 语音输入 ViewModel — 管理 UI 状态与用户交互。
 *
 * 支持长按录音、松手停止并发送的交互模式。
 * 通过 StateFlow 暴露 UI 状态，UI 层 collect 此 Flow 来驱动视觉更新。
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

    /**
     * 转录会话元数据 — 用于反馈采集。
     */
    data class TranscriptionSession(
        val sessionId: String,
        val originalText: String,
        val styledText: String,
        val style: String,
        val durationMs: Long
    )

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

    /** 最后一次转录的会话元数据（供反馈采集使用） */
    private val _lastTranscriptionSession = MutableStateFlow<TranscriptionSession?>(null)
    val lastTranscriptionSession: StateFlow<TranscriptionSession?> = _lastTranscriptionSession.asStateFlow()

    /** 当前正在执行的录音/转录任务 */
    private var currentJob: Job? = null

    /** 当前会话 ID（录音开始时生成） */
    private var currentSessionId: String? = null

    /** 会话 ID 生成器 */
    private val sessionIdProvider: SessionIdProvider = SessionIdProvider.DEFAULT

    /**
     * 长按按下 — 开始录音。
     *
     * Idle / Error / Success 状态 → 启动录音。
     * 其他状态忽略。
     */
    fun onPress() {
        val currentState = _state.value
        when (currentState) {
            is TranscribeState.Idle,
            is TranscribeState.Error,
            is TranscribeState.Success -> {
                startRecording()
            }
            is TranscribeState.WaitingForPermission -> {
                Log.w(TAG, "等待权限中")
            }
            is TranscribeState.Recording -> {
                Log.w(TAG, "已经在录音中")
            }
            is TranscribeState.Processing -> {
                Log.w(TAG, "正在处理中")
            }
        }
    }

    /**
     * 松手释放 — 停止录音并发送 HTTP 转录。
     *
     * 仅在 Recording 状态有效。
     */
    fun onRelease() {
        if (_state.value !is TranscribeState.Recording) {
            Log.w(TAG, "当前不在录音状态，忽略释放操作")
            return
        }
        stopAndTranscribe()
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
        _lastTranscriptionSession.value = null
    }

    /** 清除转录会话元数据（反馈已采集后调用） */
    fun clearSession() {
        _lastTranscriptionSession.value = null
        currentSessionId = null
    }

    /**
     * 重置状态到 Idle（例如错误确认后）。
     */
    fun resetToIdle() {
        _state.value = TranscribeState.Idle
    }

    /**
     * 开始录音（长按按下时触发）。
     */
    private fun startRecording() {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                // 生成新会话 ID
                val sid = sessionIdProvider.generateSessionId()
                currentSessionId = sid

                _state.value = TranscribeState.Recording(durationMs = 0L)
                Log.d(TAG, "开始录音 (style=${_currentStyle.value}, session=$sid)")

                // 仅启动录音，不阻塞等待
                transcribeUseCase.startRecording()

                Log.d(TAG, "录音已启动，等待释放")
            } catch (e: TranscribeException) {
                val message = when (e) {
                    is TranscribeException.RecordingFailed -> "录音启动失败"
                    else -> "未知错误"
                }
                _state.value = TranscribeState.Error(message = message)
                Log.e(TAG, "录音启动失败", e)
            } catch (e: Exception) {
                _state.value = TranscribeState.Error(message = "录音启动失败: ${e.message}")
                Log.e(TAG, "录音启动异常", e)
            }
        }
    }

    /**
     * 停止录音并执行转录（松手释放时触发）。
     */
    private fun stopAndTranscribe() {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                _state.value = TranscribeState.Processing
                Log.d(TAG, "停止录音，开始转录 (style=${_currentStyle.value})")

                val sid = currentSessionId
                val result = transcribeUseCase.stopRecordingAndTranscribe(
                    style = _currentStyle.value,
                    sessionId = sid
                )

                _state.value = TranscribeState.Success(
                    text = result.text,
                    originalText = result.originalText,
                    durationMs = result.totalDurationMs
                )
                _lastResultText.value = result.text
                _lastOriginalText.value = result.originalText

                // 记录转录会话元数据（供反馈采集）
                _lastTranscriptionSession.value = TranscriptionSession(
                    sessionId = sid ?: "",
                    originalText = result.originalText,
                    styledText = result.text,
                    style = _currentStyle.value,
                    durationMs = result.totalDurationMs
                )

                Log.d(TAG, "转录成功: ${result.text} (${result.totalDurationMs}ms, session=$sid)")

            } catch (e: TranscribeException) {
                val message = when (e) {
                    is TranscribeException.EncodingFailed -> "音频处理失败"
                    is TranscribeException.NetworkError -> "网络连接失败，请检查服务器"
                    is TranscribeException.ServerError -> "服务器错误 (${e.code})"
                    is TranscribeException.Timeout -> "请求超时，请重试"
                    is TranscribeException.ServerReturnedError -> "服务返回错误"
                    is TranscribeException.Unknown -> "未知错误"
                    else -> "转录失败"
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