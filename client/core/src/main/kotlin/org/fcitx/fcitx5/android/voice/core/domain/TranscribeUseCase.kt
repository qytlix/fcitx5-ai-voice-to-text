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
     * 执行一次完整的语音输入流程。
     *
     * 流程：
     *   1. 开始录音（若未提供外部音频）
     *   2. 停止录音并编码为 base64 WAV
     *   3. 发送 HTTP POST 到 /v1/transcribe
     *   4. 收到响应后调用 [commitTextHandler] 上屏
     *   5. 返回完整结果
     *
     * @param style      风格选项（正式/精简/礼貌/翻译_英文/自定义）
     * @param prompt     自定义提示词
     * @param sample     样例文本（few-shot）
     * @param audioSource 可选：直接传入 base64 音频（跳过录音环节）
     * @return 包含完整流程信息的 TranscribeResult
     * @throws TranscribeException 流程中的任何错误
     */
    suspend fun execute(
        style: String = "正式",
        prompt: String? = null,
        sample: String? = null,
        audioSource: String? = null
    ): TranscribeResult {
        val startTime = System.currentTimeMillis()

        // Step 1: 获取音频数据
        val audioBase64 = audioSource ?: recordAndEncode()

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
            totalDurationMs = totalDuration
        )
    }

    /**
     * 录音并编码为 base64 WAV。
     */
    private suspend fun recordAndEncode(): String {
        try {
            audioRecorder.startRecording()
        } catch (e: Exception) {
            throw TranscribeException.RecordingFailed(
                reason = "无法启动录音: ${e.message}",
                cause = e
            )
        }

        try {
            return audioRecorder.stopRecording()
        } catch (e: Exception) {
            throw TranscribeException.EncodingFailed(
                reason = "音频编码失败: ${e.message}",
                cause = e
            )
        }
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