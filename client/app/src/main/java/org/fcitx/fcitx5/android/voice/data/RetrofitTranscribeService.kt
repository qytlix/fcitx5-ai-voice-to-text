package org.fcitx.fcitx5.android.voice.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.fcitx.fcitx5.android.voice.core.model.TranscribeRequest
import org.fcitx.fcitx5.android.voice.core.model.TranscribeResponse
import org.fcitx.fcitx5.android.voice.core.network.RetryPolicy
import org.fcitx.fcitx5.android.voice.core.network.TranscribeService
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Retrofit 实现的 HTTP 客户端，对接服务端 /v1/transcribe。
 *
 * @param baseUrl 服务端基础 URL（默认 http://localhost:8080）
 */
class RetrofitTranscribeService(
    /**
     * 服务端基础 URL。
     *
     * - 模拟器: http://10.0.2.2:8080 （默认）
     * - 真机 + 局域网: http://192.168.x.x:8080
     * - 真机 + USB 反向代理: http://localhost:8080（需 adb reverse tcp:8080 tcp:8080）
     */
    baseUrl: String = "http://10.0.2.2:8080"
) : TranscribeService {

    private val api: TranscribeApi

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

        api = retrofit.create(TranscribeApi::class.java)
    }

    private interface TranscribeApi {
        @POST("/v1/transcribe")
        suspend fun transcribe(@Body request: TranscribeRequest): TranscribeResponse
    }

    override suspend fun transcribe(request: TranscribeRequest): TranscribeResponse {
        return api.transcribe(request)
    }
}
