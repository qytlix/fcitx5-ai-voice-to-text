package org.fcitx.fcitx5.android.voice.core.network

/**
 * 转录过程中可能发生的各类错误。
 *
 * 封装了网络/服务器/客户端三类错误，上层 UI 可根据 [code] 和 [message]
 * 向用户展示合适的提示。
 */
sealed class TranscribeException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** 录音失败 */
    class RecordingFailed(
        reason: String,
        cause: Throwable? = null
    ) : TranscribeException("录音失败: $reason", cause)

    /** 音频编码失败 */
    class EncodingFailed(
        reason: String,
        cause: Throwable? = null
    ) : TranscribeException("音频编码失败: $reason", cause)

    /** 网络连接错误 */
    class NetworkError(
        url: String,
        cause: Throwable? = null
    ) : TranscribeException("无法连接到服务器: $url", cause)

    /** 服务器错误（5xx） */
    class ServerError(
        val code: Int,
        val body: String? = null
    ) : TranscribeException("服务器错误 ($code)")

    /** 请求超时 */
    class Timeout(
        val timeoutMs: Long
    ) : TranscribeException("请求超时 (${timeoutMs}ms)")

    /** 服务端返回的业务错误 */
    class ServerReturnedError(
        val serverMessage: String
    ) : TranscribeException("服务返回错误: $serverMessage")

    /** 未知错误 */
    class Unknown(
        cause: Throwable? = null
    ) : TranscribeException("未知错误", cause)
}

/**
 * 网络请求重试策略。
 */
object RetryPolicy {
    /** 最大重试次数 */
    const val MAX_RETRIES: Int = 1

    /** 重试间隔（毫秒） */
    const val RETRY_DELAY_MS: Long = 1000L

    /** HTTP 连接超时（毫秒） */
    const val CONNECT_TIMEOUT_MS: Long = 10_000L

    /** HTTP 读取超时（毫秒） */
    const val READ_TIMEOUT_MS: Long = 30_000L

    /** UseCase 级别总超时（毫秒） */
    const val TOTAL_TIMEOUT_MS: Long = 60_000L

    /**
     * 判断某个异常是否应该触发重试。
     * 仅对连接类错误重试，业务错误不重试。
     */
    fun shouldRetry(error: Throwable): Boolean = when (error) {
        is java.net.ConnectException -> true
        is java.net.SocketTimeoutException -> true
        is java.net.UnknownHostException -> true
        is java.net.NoRouteToHostException -> true
        else -> false
    }
}