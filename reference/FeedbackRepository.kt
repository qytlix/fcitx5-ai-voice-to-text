/**
 * // REFERENCE: Copy this file to the Fcitx5 Android main app
 * // Package: org.fcitx.fcitx5.android.input.voice
 * //
 * // 反馈事件本地仓库 — 缓存 VoiceFeedbackEvent，支持批量上传。
 * //
 * // 使用方式：
 * //   在 FcitxInputMethodService 中持有本类实例。
 * //   语音回调中调用 enqueue() 入队。
 * //   在网络可用时调用 getPendingUploads() 批量上传。
 * //
 * // @since 0.2.0
 *
 * package org.fcitx.fcitx5.android.input.voice
 *
 * import android.util.Log
 * import java.util.concurrent.ConcurrentLinkedQueue
 *
 * /**
 *  * 反馈事件本地仓库。
 *  *
 *  * ## 设计
 *  * - 原型阶段使用内存队列（ConcurrentLinkedQueue）
 *  * - 生产环境可替换为 Room/SQLite 实现，接口不变
 *  * - 线程安全：使用 ConcurrentLinkedQueue
 *  *
 *  * ## 容量管理
 *  * - [maxQueueSize] 限制内存中最大事件数，超出时丢弃最旧的事件
 *  */
 * class FeedbackRepository(
 *     private val maxQueueSize: Int = 500
 * ) {
 *
 *     companion object {
 *         private const val TAG = "FeedbackRepository"
 *     }
 *
 *     private val queue = ConcurrentLinkedQueue<VoiceFeedbackEvent>()
 *
 *     /**
 *      * 入队一条反馈事件。
 *      *
 *      * @param event 反馈事件
 */
 *     fun enqueue(event: VoiceFeedbackEvent) {
 *         queue.add(event)
 *         Log.d(TAG, "反馈事件入队: ${event.id}, 队列大小: ${queue.size}")
 *
 *         // 超出容量时移除最旧的事件
 *         while (queue.size > maxQueueSize) {
 *             val removed = queue.poll()
 *             Log.w(TAG, "队列已满，丢弃最旧事件: ${removed?.id}")
 *         }
 *     }
 *
 *     /**
 *      * 获取所有待上传的事件。
 *      *
 *      * @param maxBatch 单次批量上传上限
 *      * @return 待上传事件列表
 */
 *     fun getPendingUploads(maxBatch: Int = 50): List<VoiceFeedbackEvent> {
 *         val batch = mutableListOf<VoiceFeedbackEvent>()
 *         repeat(maxBatch) {
 *             val event = queue.poll() ?: return batch
 *             batch.add(event)
 *         }
 *         return batch
 *     }
 *
 *     /**
 *      * 将事件重新入队（上传失败时回退）。
 *      *
 *      * @param events 上传失败的事件列表
 */
 *     fun requeue(events: List<VoiceFeedbackEvent>) {
 *         events.forEach { enqueue(it) }
 *         Log.d(TAG, "重新入队 ${events.size} 条事件")
 *     }
 *
 *     /** 当前队列大小 */
 *     fun size(): Int = queue.size
 *
 *     /** 清空队列 */
 *     fun clear() {
 *         val count = queue.size
 *         queue.clear()
 *         Log.d(TAG, "清空队列，移除 $count 条事件")
 *     }
 *
 *     /** 队列是否为空 */
 *     fun isEmpty(): Boolean = queue.isEmpty()
 * }
