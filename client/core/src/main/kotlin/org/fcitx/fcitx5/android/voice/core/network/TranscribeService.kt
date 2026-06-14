package org.fcitx.fcitx5.android.voice.core.network

import org.fcitx.fcitx5.android.voice.core.model.TranscribeRequest
import org.fcitx.fcitx5.android.voice.core.model.TranscribeResponse

/**
 * 语音转录网络服务接口。
 *
 * 定义与 [服务端 /v1/transcribe] 的通信合约。
 * 纯 Kotlin 版本定义接口，具体 HTTP 实现在 app 模块中。
 *
 * 实现类：
 * - RetrofitTranscribeService — Retrofit + OkHttp 实现（app 模块）
 * - MockTranscribeService — 单元测试用（返回固定响应）
 */
interface TranscribeService {

    /**
     * 发送音频并返回风格化结果。
     *
     * @param request 包含 base64 音频、风格选项等的请求
     * @return 服务端返回的转录结果
     * @throws TranscribeException 网络错误或服务端错误时抛出
     */
    suspend fun transcribe(request: TranscribeRequest): TranscribeResponse
}