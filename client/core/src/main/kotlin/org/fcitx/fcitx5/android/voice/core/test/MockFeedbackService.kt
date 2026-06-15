package org.fcitx.fcitx5.android.voice.core.test

import org.fcitx.fcitx5.android.voice.core.model.FeedbackUploadRequest
import org.fcitx.fcitx5.android.voice.core.model.FeedbackUploadResponse
import org.fcitx.fcitx5.android.voice.core.network.FeedbackService

/**
 * Mock 反馈上传服务 — 返回固定响应，不发起真实 HTTP 请求。
 *
 * 用于单元测试反馈收集与上传逻辑。
 * 支持模拟不同场景：成功、服务器错误、网络超时、连接异常。
 *
 * 使用模式与 [MockTranscribeService] 一致。
 */
class MockFeedbackService : FeedbackService {

    /** 请求记录（可用于断言测试中发送的参数） */
    var lastRequest: FeedbackUploadRequest? = null
        private set

    /** 请求次数计数 */
    var uploadCount: Int = 0
        private set

    /** 可自定义的 Mock 响应 */
    var mockResponse: FeedbackUploadResponse = DEFAULT_SUCCESS_RESPONSE

    /** 设为 true 模拟服务器返回错误 */
    var shouldReturnError: Boolean = false

    /** 设为 true 模拟网络超时 */
    var shouldTimeout: Boolean = false

    /** 设为 true 模拟连接异常 */
    var shouldThrowConnectionError: Boolean = false

    /** 可自定义的错误消息 */
    var errorMessage: String = "服务暂时不可用"

    override suspend fun uploadFeedback(request: FeedbackUploadRequest): FeedbackUploadResponse {
        lastRequest = request
        uploadCount++

        return when {
            shouldTimeout -> throw java.net.SocketTimeoutException("Read timed out")
            shouldThrowConnectionError -> throw java.net.ConnectException("Connection refused")
            shouldReturnError -> FeedbackUploadResponse(
                status = "error",
                error = errorMessage
            )
            else -> mockResponse
        }
    }

    /** 重置所有状态（用于测试间清理） */
    fun reset() {
        lastRequest = null
        uploadCount = 0
        shouldReturnError = false
        shouldTimeout = false
        shouldThrowConnectionError = false
        mockResponse = DEFAULT_SUCCESS_RESPONSE
    }

    companion object {
        val DEFAULT_SUCCESS_RESPONSE = FeedbackUploadResponse(
            status = "ok",
            event_id = "srv-ev-001",
            error = null
        )

        val DUPLICATE_RESPONSE = FeedbackUploadResponse(
            status = "ok",
            event_id = "srv-ev-existing",
            error = null
        )
    }
}
