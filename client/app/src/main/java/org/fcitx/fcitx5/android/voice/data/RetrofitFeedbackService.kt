package org.fcitx.fcitx5.android.voice.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.fcitx.fcitx5.android.voice.core.model.FeedbackUploadRequest
import org.fcitx.fcitx5.android.voice.core.model.FeedbackUploadResponse
import org.fcitx.fcitx5.android.voice.core.network.FeedbackService
import org.fcitx.fcitx5.android.voice.core.network.RetryPolicy
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Retrofit 实现的反馈上传 HTTP 客户端，对接服务端 POST /v1/feedback。
 *
 * 完全遵循 [RetrofitTranscribeService] 的模式：相同的 baseUrl、
 * 相同的 OkHttpClient 配置（超时、日志拦截器）。
 *
 * @param baseUrl 服务端基础 URL（默认 http://10.0.2.2:8080）
 */
class RetrofitFeedbackService(
    baseUrl: String = "http://10.0.2.2:8080"
) : FeedbackService {

    private val api: FeedbackApi

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(RetryPolicy.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(RetryPolicy.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(FeedbackApi::class.java)
    }

    private interface FeedbackApi {
        @POST("/v1/feedback")
        suspend fun uploadFeedback(@Body request: FeedbackUploadRequest): FeedbackUploadResponse
    }

    override suspend fun uploadFeedback(request: FeedbackUploadRequest): FeedbackUploadResponse {
        return api.uploadFeedback(request)
    }
}
