/**
 * // REFERENCE: Copy this file to the Fcitx5 Android main app
 * // Package: org.fcitx.fcitx5.android.input.voice
 * //
 * // 语音反馈采集器 — 在合适的时机读取 InputConnection 中用户编辑后的文本，
 * // 与 AI 风格化上屏文本比对，计算差异并构建 VoiceFeedbackEvent。
 * //
 * // 使用方式：
 * //   在 FcitxInputMethodService 中持有本类实例。
 * //   在 onStartInputView() 中调用 checkAndCollect() 检查上次会话的编辑结果。
 * //   在 onResult 回调中调用 recordSession() 暂存本次会话数据。
 * //
 * // @since 0.2.0
 *
 * package org.fcitx.fcitx5.android.input.voice
 *
 * import android.util.Log
 * import android.view.inputmethod.InputConnection
 *
 * /**
 *  * 语音反馈采集器 — 采集用户编辑后的最终文本，构建反馈事件。
 *  *
 *  * ## 采集流程
 *  * 1. [recordSession] — 转录完成时暂存 (originalText, styledText, style, sessionId, timestamp)
 *  * 2. [checkAndCollect] — 下次输入框获得焦点时，读取 InputConnection 获取 finalText
 *  * 3. 计算 diff，如果差异超过阈值则构建 VoiceFeedbackEvent
 *  * 4. 将事件加入 FeedbackRepository 等待上传
 *  *
 *  * ## 采集时机（推荐）
 *  * - 主要：用户再次点击语音按钮时（触发 checkAndCollect）
 *  * - 兜底：onFinishInput() 时（输入框失去焦点）
 *  */
 * class VoiceFeedbackCollector(
 *     private val repository: FeedbackRepository
 * ) {
 *
 *     companion object {
 *         private const val TAG = "VoiceFeedbackCollector"
 *
 *         /**
 *          * 差异最小比例阈值。
 *          * 只有当 styledText 与 finalText 的字符级差异比例超过此值时，
 *          * 才视为有效反馈。避免记录无意义的标点/空格微调。
 *          */
 *         const val MIN_DIFF_RATIO = 0.1
 *     }
 *
 *     /** 上次转录会话的暂存数据 */
 *     private var pendingSession: PendingSession? = null
 *
 *     /**
 *      * 转录完成时调用 — 暂存会话数据。
 *      *
 *      * @param sessionId    会话 ID
 *      * @param originalText ASR 原始转写
 *      * @param styledText   AI 风格化结果（已上屏）
 *      * @param style        使用的风格
 *      * @param durationMs   处理耗时
 *      */
 *     fun recordSession(
 *         sessionId: String,
 *         originalText: String,
 *         styledText: String,
 *         style: String,
 *         prompt: String? = null,
 *         durationMs: Long
 *     ) {
 *         // 如果已有未处理的会话，先强制采集
 *         if (pendingSession != null) {
 *             Log.w(TAG, "上一条会话尚未采集，将丢弃")
 *         }
 *
 *         pendingSession = PendingSession(
 *             sessionId = sessionId,
 *             originalText = originalText,
 *             styledText = styledText,
 *             style = style,
 *             prompt = prompt,
 *             durationMs = durationMs,
 *             timestamp = System.currentTimeMillis()
 *         )
 *         Log.d(TAG, "记录会话: session=$sessionId, text=$styledText")
 *     }
 *
 *     /**
 *      * 检查并采集反馈 — 读取 InputConnection 中当前文本，
 *      * 与上次转录的 styledText 比对。
 *      *
 *      * @param inputConnection 当前输入框的 InputConnection
 *      * @return 构建的 VoiceFeedbackEvent，如果无差异或低于阈值则返回 null
 *      */
 *     fun checkAndCollect(inputConnection: InputConnection?): VoiceFeedbackEvent? {
 *         val session = pendingSession ?: return null
 *         pendingSession = null
 *
 *         // 读取当前输入框中的文本（光标前内容 + 光标后内容）
 *         val finalText = try {
 *             readFinalText(inputConnection, session.styledText)
 *         } catch (e: Exception) {
 *             Log.w(TAG, "读取 InputConnection 失败", e)
 *             null
 *         }
 *
 *         if (finalText == null || finalText == session.styledText) {
 *             Log.d(TAG, "文本无变化，跳过反馈采集")
 *             return null
 *         }
 *
 *         // 计算差异比例
 *         val diffRatio = computeDiffRatio(session.styledText, finalText)
 *         if (diffRatio < MIN_DIFF_RATIO) {
 *             Log.d(TAG, "差异比例 $diffRatio < $MIN_DIFF_RATIO，跳过")
 *             return null
 *         }
 *
 *         // 构建反馈事件
 *         val event = VoiceFeedbackEvent(
 *             sessionId = session.sessionId,
 *             originalText = session.originalText,
 *             styledText = session.styledText,
 *             finalText = finalText,
 *             style = session.style,
 *             prompt = session.prompt,
 *             durationMs = session.durationMs,
 *             timestamp = session.timestamp
 *         )
 *
 *         Log.d(TAG, "构建反馈事件: id=${event.id}, diff=$diffRatio")
 *         repository.enqueue(event)
 *         return event
 *     }
 *
 *     /**
 *      * 强制采集当前 pending session（不依赖 InputConnection）。
 *      * 在 onFinishInput() 中作为兜底调用。
 *      *
 *      * @param inputConnection 当前 InputConnection（可能为 null）
 *      */
 *     fun forceCollect(inputConnection: InputConnection?) {
 *         checkAndCollect(inputConnection)
 *     }
 *
 *     /** 是否有待采集的会话 */
 *     fun hasPendingSession(): Boolean = pendingSession != null
 *
 *     // ========================================================================
 *     // 内部
 *     // ========================================================================
 *
 *     /**
 *      * 从 InputConnection 中读取用户编辑后的最终文本。
 *      *
 *      * 策略：读取光标前的 N 个字符（N = styledText 长度的 2 倍，
 *      * 以捕获用户可能在 styledText 前后插入的内容）。
 *      */
 *     private fun readFinalText(
 *         ic: InputConnection?,
 *         styledText: String
 *     ): String? {
 *         if (ic == null) return null
 *
 *         val maxLen = (styledText.length * 2).coerceAtLeast(100)
 *
 *         // 获取光标前文本
 *         val before = ic.getTextBeforeCursor(maxLen, 0)?.toString() ?: ""
 *         // 获取光标后文本
 *         val after = ic.getTextAfterCursor(maxLen, 0)?.toString() ?: ""
 *
 *         return before + after
 *     }
 *
 *     /**
 *      * 计算两个文本的字符级差异比例。
 *
 *      * 使用 Levenshtein 距离 / max(len(a), len(b)) 作为差异度量。
 *      * 返回值范围 [0.0, 1.0]。
 *      */
 *     private fun computeDiffRatio(a: String, b: String): Double {
 *         if (a.isEmpty() && b.isEmpty()) return 0.0
 *         val distance = levenshteinDistance(a, b)
 *         return distance.toDouble() / maxOf(a.length, b.length).coerceAtLeast(1)
 *     }
 *
 *     /** Levenshtein 编辑距离 */
 *     private fun levenshteinDistance(s1: String, s2: String): Int {
 *         if (s1.isEmpty()) return s2.length
 *         if (s2.isEmpty()) return s1.length
 *
 *         val prev = IntArray(s2.length + 1) { it }
 *         val curr = IntArray(s2.length + 1)
 *
 *         for (i in 1..s1.length) {
 *             curr[0] = i
 *             for (j in 1..s2.length) {
 *                 val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
 *                 curr[j] = minOf(
 *                     prev[j] + 1,       // 删除
 *                     curr[j - 1] + 1,   // 插入
 *                     prev[j - 1] + cost // 替换
 *                 )
 *             }
 *             // swap
 *             for (k in prev.indices) prev[k] = curr[k]
 *         }
 *         return curr[s2.length]
 *     }
 *
 *     // ========================================================================
 *     // 内部数据类
 *     // ========================================================================
 *
 *     private data class PendingSession(
 *         val sessionId: String,
 *         val originalText: String,
 *         val styledText: String,
 *         val style: String,
 *         val prompt: String?,
 *         val durationMs: Long,
 *         val timestamp: Long
 *     )
 *
 *     /**
 *      * 反馈事件数据类（简化版，完整定义见插件 core 模块）。
 *      * 主应用侧可直接使用插件 core 模块的 VoiceFeedbackEvent 类，
 *      * 此处定义临时结构以避免编译依赖。
 *      */
 *     data class VoiceFeedbackEvent(
 *         val id: String = java.util.UUID.randomUUID().toString(),
 *         val sessionId: String,
 *         val originalText: String,
 *         val styledText: String,
 *         val finalText: String,
 *         val style: String,
 *         val prompt: String? = null,
 *         val durationMs: Long,
 *         val timestamp: Long = System.currentTimeMillis()
 *     )
 * }
