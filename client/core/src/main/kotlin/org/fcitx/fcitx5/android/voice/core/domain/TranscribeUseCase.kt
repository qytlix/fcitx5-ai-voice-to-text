package org.fcitx.fcitx5.android.voice.core.domain

import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.voice.core.bridge.AudioRecorder
import org.fcitx.fcitx5.android.voice.core.bridge.CommitTextHandler
import org.fcitx.fcitx5.android.voice.core.model.TranscribeRequest
import org.fcitx.fcitx5.android.voice.core.model.TranscribeResult
import org.fcitx.fcitx5.android.voice.core.network.RetryPolicy
import org.fcitx.fcitx5.android.voice.core.network.TranscribeException
import org.fcitx.fcitx5.android.voice.core.network.TranscribeService

/**
 * 语音转录用例 — 协调录音 → 发送 → 接收 → 上屏的完整流程。
 *
 * 通过依赖注入三个接口（录音、网络、上屏），本用例可在不依赖
 * Android 框架的环境下进行单元测试（使用 Mock 实现）。
 *
 * @param audioRecorder      录音接口
 * @param transcribeService  网络请求接口
 * @param commitTextHandler  上屏接口
 */
class TranscribeUseCase(
    private val audioRecorder: AudioRecorder,
    private val transcribeService: TranscribeService,
    private val commitTextHandler: CommitTextHandler
) {

    /**
     * 开始录音 — 两段式接口的第一段。
     *
     * 调用后录音器会持续采集音频，直到调用 [stopRecordingAndTranscribe]。
     * 适用于长按录音的交互模式。
     *
     * @throws TranscribeException.RecordingFailed 无法启动录音时
     */
    suspend fun startRecording() {
        try {
            audioRecorder.startRecording()
        } catch (e: Exception) {
            throw TranscribeException.RecordingFailed(
                reason = "无法启动录音: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * 停止录音并执行转录 — 两段式接口的第二段。
     *
     * 流程：停止录音编码 base64 → HTTP POST 转录 → 上屏
     *
     * @param style      风格选项（正式/精简/礼貌/翻译_英文/自定义）
     * @param prompt     自定义提示词
     * @param sample     样例文本（few-shot）
     * @return 包含完整流程信息的 TranscribeResult
     * @throws TranscribeException 流程中的任何错误
     */
    suspend fun stopRecordingAndTranscribe(
        style: String = "正式",
        prompt: String? = null,
        sample: String? = null,
        sessionId: String? = null
    ): TranscribeResult {
        val startTime = System.currentTimeMillis()

        // Step 1: 停止录音并编码
        val audioBase64 = try {
            audioRecorder.stopRecording()
        } catch (e: Exception) {
            throw TranscribeException.EncodingFailed(
                reason = "音频编码失败: ${e.message}",
                cause = e
            )
        }

        // Step 2: 发送请求（含自动重试）
        val response = transcribeWithRetry(
            TranscribeRequest(
                audio = audioBase64,
                prompt = prompt,
                sample = sample,
                style = style
            )
        )

        // Step 3: 检查业务错误
        if (response.error != null) {
            throw TranscribeException.ServerReturnedError(response.error)
        }

        // Step 4: 上屏
        commitTextHandler.commitText(response.text)

        val totalDuration = System.currentTimeMillis() - startTime

        return TranscribeResult(
            text = response.text,
            originalText = response.originalText,
            serverDurationMs = response.durationMs?.toLong(),
            totalDurationMs = totalDuration,
            sessionId = sessionId
        )
    }

    /**
     * 一站式执行：录音 → 发送 → 上屏。
     *
     * 内部调用 [startRecording] 立即 [stopRecordingAndTranscribe]。
     * 适用于无需等待用户"停止"的场景（如外部音频输入、插件模式）。
     *
     * @param style      风格选项
     * @param prompt     自定义提示词
     * @param sample     样例文本
     * @param audioSource 可选：直接传入 base64 音频（跳过录音环节）
     * @return TranscribeResult
     */
    suspend fun execute(
        style: String = "正式",
        prompt: String? = null,
        sample: String? = null,
        audioSource: String? = null,
        sessionId: String? = null
    ): TranscribeResult {
        // 有外部音频源则跳过录音
        if (audioSource != null) {
            return transcribeAndCommit(audioSource, style, prompt, sample, sessionId)
        }
        // 否则执行录音 → 转录
        startRecording()
        return stopRecordingAndTranscribe(style, prompt, sample, sessionId)
    }

    /**
     * 直接对已有音频 base64 执行转录和上屏（跳过录音）。
     */
    private suspend fun transcribeAndCommit(
        audioBase64: String,
        style: String,
        prompt: String?,
        sample: String?,
        sessionId: String?
    ): TranscribeResult {
        val startTime = System.currentTimeMillis()

        val response = transcribeWithRetry(
            TranscribeRequest(
                audio = audioBase64,
                prompt = prompt,
                sample = sample,
                style = style
            )
        )

        if (response.error != null) {
            throw TranscribeException.ServerReturnedError(response.error)
        }

        commitTextHandler.commitText(response.text)

        return TranscribeResult(
            text = response.text,
            originalText = response.originalText,
            serverDurationMs = response.durationMs?.toLong(),
            totalDurationMs = System.currentTimeMillis() - startTime,
            sessionId = sessionId
        )
    }

    /**
     * 发送转录请求，包含自动重试逻辑。
     */
    private suspend fun transcribeWithRetry(
        request: TranscribeRequest,
        depth: Int = 0
    ): TranscribeResponseAdapter {
        try {
            val response = transcribeService.transcribe(request)
            return TranscribeResponseAdapter(response)
        } catch (e: Exception) {
            // 判断是否应重试
            if (depth < RetryPolicy.MAX_RETRIES && RetryPolicy.shouldRetry(e)) {
                delay(RetryPolicy.RETRY_DELAY_MS)
                return transcribeWithRetry(request, depth + 1)
            }
            // 将异常映射为 TranscribeException
            throw mapToTranscribeException(e)
        }
    }

    /**
     * 将各种底层异常映射为统一的 TranscribeException。
     */
    private fun mapToTranscribeException(e: Exception): TranscribeException = when (e) {
        is TranscribeException -> e
        is java.net.SocketTimeoutException ->
            TranscribeException.Timeout(RetryPolicy.READ_TIMEOUT_MS)
        is java.net.ConnectException ->
            TranscribeException.NetworkError("server", e)
        is java.net.UnknownHostException ->
            TranscribeException.NetworkError("server", e)
        else ->
            TranscribeException.Unknown(e)
    }
}

/**
 * 内部适配器，将 [org.fcitx.fcitx5.android.voice.core.model.TranscribeResponse]
 * 包装为 UseCase 使用的内部类型。当前是直接透传，未来可在此处添加
 * 响应验证/转换逻辑。
 */
private data class TranscribeResponseAdapter(
    val text: String,
    val originalText: String,
    val durationMs: Int?,
    val error: String?
) {
    constructor(response: org.fcitx.fcitx5.android.voice.core.model.TranscribeResponse) : this(
        text = response.text,
        originalText = response.original_text,
        durationMs = response.duration_ms,
        error = response.error
    )
}