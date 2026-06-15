/**
 * // REFERENCE: Copy this file to the Fcitx5 Android main app
 * // Package: org.fcitx.fcitx5.android.input.voice
 * //
 * // 反馈事件上传器 — 将本地缓存的 VoiceFeedbackEvent 批量 POST 到服务端。
 * //
 * // 使用方式：
 * //   在 FcitxInputMethodService 中持有本类实例。
 * //   在 onVoiceButtonDown() 中（用户开始新录音前）调用 uploadPending() 批量上传。
 * //   或在 WorkManager 定时任务中调用。
 * //
 * // @since 0.2.0
 *
 * package org.fcitx.fcitx5.android.input.voice
 *
 * import android.util.Log
 * import kotlinx.coroutines.*
 * import org.json.JSONObject
 * import java.io.OutputStream
 * import java.net.HttpURLConnection
 * import java.net.URL
 *
 * /**
 *  * 反馈事件上传器。
 *  *
 *  * ## 上传策略
 *  * - 使用 HttpURLConnection（避免引入额外依赖；如果项目已有 OkHttp 可直接替换）
 *  * - 批量上传：每次最多 20 条
 *  * - 单条上传失败不影响其他事件（逐条 POST）
 *  * - 上传失败的事件重新入队，等待下次上传
 *  *
 *  * ## 服务端接口
 *  * POST /v1/feedback
 *  * Content-Type: application/json
 */
 * class FeedbackUploader(
 *     private val baseUrl: String = "http://10.0.2.2:8080"
 * ) {
 *
 *     companion object {
 *         private const val TAG = "FeedbackUploader"
 *         private const val UPLOAD_PATH = "/v1/feedback"
 *         private const val CONNECT_TIMEOUT_MS = 5000
 *         private const val READ_TIMEOUT_MS = 10000
 *         private const val MAX_BATCH_SIZE = 20
 *     }
 *
 *     /**
 *      * 上传所有待处理的反馈事件。
 *      *
 *      * @param repository 反馈事件仓库
 *      * @return 成功上传的条数
 */
 *     suspend fun uploadPending(repository: FeedbackRepository): Int = withContext(Dispatchers.IO) {
 *         var uploadedCount = 0
 *         var consecutiveFailures = 0
 *         val maxConsecutiveFailures = 3
 *
 *         while (true) {
 *             val batch = repository.getPendingUploads(MAX_BATCH_SIZE)
 *             if (batch.isEmpty()) break
 *
 *             for (event in batch) {
 *                 val success = uploadSingle(event)
 *                 if (success) {
 *                     uploadedCount++
 *                     consecutiveFailures = 0
 *                 } else {
 *                     consecutiveFailures++
 *                     // 失败的事件放回队列
 *                     repository.requeue(listOf(event))
 *
 *                     if (consecutiveFailures >= maxConsecutiveFailures) {
 *                         Log.w(TAG, "连续 $maxConsecutiveFailures 次上传失败，暂停上传")
 *                         return@withContext uploadedCount
 *                     }
 *                 }
 *             }
 *         }
 *
 *         Log.d(TAG, "上传完成: $uploadedCount 条成功")
 *         uploadedCount
 *     }
 *
 *     /**
 *      * 上传单条反馈事件。
 *      *
 *      * @return true 表示上传成功
 */
 *     private fun uploadSingle(event: VoiceFeedbackEvent): Boolean {
 *         return try {
 *             val url = URL("$baseUrl$UPLOAD_PATH")
 *             val conn = url.openConnection() as HttpURLConnection
 *             conn.requestMethod = "POST"
 *             conn.setRequestProperty("Content-Type", "application/json")
 *             conn.connectTimeout = CONNECT_TIMEOUT_MS
 *             conn.readTimeout = READ_TIMEOUT_MS
 *             conn.doOutput = true
 *
 *             // 构建 JSON body
 *             val json = JSONObject().apply {
 *                 put("client_event_id", event.id)
 *                 put("session_id", event.sessionId)
 *                 put("original_text", event.originalText)
 *                 put("styled_text", event.styledText)
 *                 put("final_text", event.finalText)
 *                 put("style", event.style)
 *                 if (event.prompt != null) put("prompt", event.prompt)
 *                 put("duration_ms", event.durationMs)
 *                 put("timestamp", event.timestamp)
 *             }
 *
 *             // 发送
 *             val output: OutputStream = conn.outputStream
 *             output.write(json.toString().toByteArray(Charsets.UTF_8))
 *             output.close()
 *
 *             // 检查响应
 *             val responseCode = conn.responseCode
 *             conn.disconnect()
 *
 *             val success = responseCode in 200..299
 *             if (success) {
 *                 Log.d(TAG, "上传成功: ${event.id}")
 *             } else {
 *                 Log.w(TAG, "上传失败: ${event.id}, HTTP $responseCode")
 *             }
 *             success
 *
 *         } catch (e: Exception) {
 *             Log.e(TAG, "上传异常: ${event.id}", e)
 *             false
 *         }
 *     }
 * }
