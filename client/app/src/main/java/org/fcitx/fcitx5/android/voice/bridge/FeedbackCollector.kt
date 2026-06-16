package org.fcitx.fcitx5.android.voice.bridge

import android.util.Log
import org.fcitx.fcitx5.android.voice.core.model.VoiceFeedbackEvent

/**
 * 独立 App 反馈采集器 — 在无需 Fcitx5 主应用的情况下测试反馈链路。
 *
 * ## 职责
 * 1. 记录最近一次转录的会话元数据
 * 2. 接收用户手动输入的最终文本
 * 3. 计算差异并构建 VoiceFeedbackEvent
 *
 * ## 与 Fcitx5 主应用侧 VoiceFeedbackCollector 的区别
 * - 本类不依赖 InputConnection，用户手动提供 finalText
 * - 用于独立 App 的调试和端到端测试
 * - 主应用侧的 VoiceFeedbackCollector 则自动从 InputConnection 读取
 *
 * @see VoiceFeedbackEvent 反馈事件数据模型
 * @see reference/VoiceFeedbackCollector.kt 主应用侧参考实现
 */
class FeedbackCollector {

    companion object {
        private const val TAG = "FeedbackCollector"
    }

    /** 最近一次转录的会话元数据 */
    private var lastSession: PendingSession? = null

    /**
     * 记录转录结果 — 在 ViewModel 成功转录后调用。
     *
     * @param sessionId    会话 ID
     * @param originalText ASR 原始转写
     * @param styledText   AI 风格化结果
     * @param style        使用的风格
     * @param durationMs   处理耗时
     */
    fun recordSession(
        sessionId: String,
        originalText: String,
        styledText: String,
        style: String,
        prompt: String? = null,
        durationMs: Long
    ) {
        lastSession = PendingSession(
            sessionId = sessionId,
            originalText = originalText,
            styledText = styledText,
            style = style,
            prompt = prompt,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        )
        Log.d(TAG, "记录会话: session=$sessionId, text=$styledText")
    }

    /**
     * 用户编辑后比对差异，构建反馈事件。
     *
     * @param finalText 用户编辑后的最终文本
     * @return 构建的 VoiceFeedbackEvent，无差异或低于阈值时返回 null
     */
    fun compareAndBuild(finalText: String): VoiceFeedbackEvent? {
        val session = lastSession ?: run {
            Log.w(TAG, "无待采集的会话")
            return null
        }
        lastSession = null

        if (finalText == session.styledText) {
            Log.d(TAG, "文本无变化，跳过反馈")
            return null
        }

        val diffRatio = computeDiffRatio(session.styledText, finalText)
        if (diffRatio < VoiceFeedbackEvent.MIN_DIFF_RATIO) {
            Log.d(TAG, "差异比例 ${"%.2f".format(diffRatio)} < ${VoiceFeedbackEvent.MIN_DIFF_RATIO}，跳过")
            return null
        }

        val event = VoiceFeedbackEvent(
            sessionId = session.sessionId,
            originalText = session.originalText,
            styledText = session.styledText,
            finalText = finalText,
            style = session.style,
            prompt = session.prompt,
            durationMs = session.durationMs,
            timestamp = session.timestamp
        )

        Log.d(TAG, "构建反馈事件: id=${event.id}, diff=${"%.2f".format(diffRatio)}")
        return event
    }

    /** 是否有待采集的会话 */
    fun hasPending(): Boolean = lastSession != null

    /** 清除待采集的会话 */
    fun clear() {
        lastSession = null
    }

    /**
     * 计算两个文本的差异比例（Levenshtein 距离归一化）。
     */
    private fun computeDiffRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val distance = levenshteinDistance(a, b)
        return distance.toDouble() / maxOf(a.length, b.length).coerceAtLeast(1)
    }

    /** Levenshtein 编辑距离（双数组滚动优化） */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val prev = IntArray(s2.length + 1) { it }
        val curr = IntArray(s2.length + 1)

        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            for (k in prev.indices) prev[k] = curr[k]
        }
        return curr[s2.length]
    }

    private data class PendingSession(
        val sessionId: String,
        val originalText: String,
        val styledText: String,
        val style: String,
        val prompt: String?,
        val durationMs: Long,
        val timestamp: Long
    )
}
