package org.fcitx.fcitx5.android.voice.core.test

import org.fcitx.fcitx5.android.voice.core.model.TranscribeRequest
import org.fcitx.fcitx5.android.voice.core.model.TranscribeResponse
import org.fcitx.fcitx5.android.voice.core.network.TranscribeService

/**
 * Mock 网络服务 — 返回固定响应，不发起真实 HTTP 请求。
 *
 * 用于单元测试 [org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase]。
 * 支持模拟不同场景：成功、服务器错误、网络超时等。
 */
class MockTranscribeService : TranscribeService {

    /** 请求记录（可用于断言测试中发送的参数） */
    var lastRequest: TranscribeRequest? = null

    /** 可自定义的 Mock 响应 */
    var mockResponse: TranscribeResponse = DEFAULT_SUCCESS_RESPONSE

    /** 设为 true 模拟服务器返回业务错误 */
    var shouldReturnError: Boolean = false

    /** 设为 true 模拟网络超时 */
    var shouldTimeout: Boolean = false

    /** 设为 true 模拟连接异常 */
    var shouldThrowConnectionError: Boolean = false

    /** 可自定义的错误消息 */
    var errorMessage: String = "服务暂时不可用"

    override suspend fun transcribe(request: TranscribeRequest): TranscribeResponse {
        lastRequest = request

        when {
            shouldTimeout -> throw java.net.SocketTimeoutException("Read timed out")
            shouldThrowConnectionError -> throw java.net.ConnectException("Connection refused")
            shouldReturnError -> return TranscribeResponse(
                text = "",
                original_text = "",
                error = errorMessage
            )
            else -> return mockResponse
        }
    }

    companion object {
        val DEFAULT_SUCCESS_RESPONSE = TranscribeResponse(
            text = "好的，这是一个测试响应。下午三点有个会议你记得参加。",
            original_text = "那个 下午 三点 有个 会议 你 记得 参加 一下",
            duration_ms = 42,
            error = null
        )

        val CONCISE_RESPONSE = TranscribeResponse(
            text = "下午三点有会议，请参加。",
            original_text = "那个 下午 三点 有个 会议 你 记得 参加 一下",
            duration_ms = 35,
            error = null
        )

        val FORMAL_RESPONSE = TranscribeResponse(
            text = "您下午三点有一个会议，请准时参加。",
            original_text = "那个 下午 三点 有个 会议 你 记得 参加 一下",
            duration_ms = 50,
            error = null
        )

        val POLITE_RESPONSE = TranscribeResponse(
            text = "您好，下午三点有一个会议，麻烦您记得参加一下。谢谢！",
            original_text = "那个 下午 三点 有个 会议 你 记得 参加 一下",
            duration_ms = 45,
            error = null
        )
    }
}