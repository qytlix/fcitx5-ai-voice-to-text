package org.fcitx.fcitx5.android.voice.core.network

import org.fcitx.fcitx5.android.voice.core.model.FeedbackUploadRequest
import org.fcitx.fcitx5.android.voice.core.model.FeedbackUploadResponse

/**
 * 反馈上传网络服务接口。
 *
 * 定义与 [服务端 POST /v1/feedback] 的通信合约。
 * 纯 Kotlin 版本定义接口，具体 HTTP 实现在 app 模块中。
 *
 * 实现类：
 * - RetrofitFeedbackService — Retrofit + OkHttp 实现（app 模块）
 * - MockFeedbackService — 单元测试用（返回固定响应）
 */
interface FeedbackService {

    /**
     * 上传反馈事件到服务端。
     *
     * @param request 包含原始转写、风格化结果、用户最终文本等
     * @return 服务端确认响应
     * @throws java.net.SocketTimeoutException 网络超时
     * @throws java.net.ConnectException 无法连接服务端
     */
    suspend fun uploadFeedback(request: FeedbackUploadRequest): FeedbackUploadResponse
}
