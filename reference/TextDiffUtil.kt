/**
 * // REFERENCE: Copy this file to the Fcitx5 Android main app
 * // Package: org.fcitx.fcitx5.android.input.voice
 * //
 * // 文本差异计算工具 — 提供字符级与行级差异计算。
 * //
 * // @since 0.2.0
 *
 * package org.fcitx.fcitx5.android.input.voice
 *
 * /**
 *  * 文本差异计算工具。
 *  *
 *  * 用于在反馈采集中计算 styledText → finalText 的最小编辑路径。
 *  * 基于 Levenshtein 编辑距离算法。
 *  */
 * object TextDiffUtil {
 *
 *     /**
 *      * 计算两个字符串的 Levenshtein 编辑距离。
 *      *
 *      * @return 将 a 转换为 b 所需的最小单字符编辑次数
 */
 *     fun levenshteinDistance(a: String, b: String): Int {
 *         if (a.isEmpty()) return b.length
 *         if (b.isEmpty()) return a.length
 *
 *         // 使用滚动数组优化空间复杂度 O(min(m,n))
 *         val (shorter, longer) = if (a.length < b.length) a to b else b to a
 *         val prev = IntArray(shorter.length + 1) { it }
 *         val curr = IntArray(shorter.length + 1)
 *
 *         for (i in 1..longer.length) {
 *             curr[0] = i
 *             for (j in 1..shorter.length) {
 *                 val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
 *                 curr[j] = minOf(
 *                     prev[j] + 1,       // 删除
 *                     curr[j - 1] + 1,   // 插入
 *                     prev[j - 1] + cost // 替换
 *                 )
 *             }
 *             // 交换数组
 *             for (k in prev.indices) prev[k] = curr[k]
 *         }
 *         return curr[shorter.length]
 *     }
 *
 *     /**
 *      * 计算差异比例。
 *      *
 *      * 公式：编辑距离 / max(len(a), len(b))
 *      *
 *      * @return 差异比例，范围 [0.0, 1.0]
 */
 *     fun diffRatio(a: String, b: String): Double {
 *         if (a.isEmpty() && b.isEmpty()) return 0.0
 *         val maxLen = maxOf(a.length, b.length).coerceAtLeast(1)
 *         return levenshteinDistance(a, b).toDouble() / maxLen
 *     }
 *
 *     /**
 *      * 判断两个文本是否有显著差异。
 *      *
 *      * @param threshold 差异比例阈值（默认 0.1，即 10%）
 *      * @return true 表示差异超过阈值
 */
 *     fun hasSignificantDiff(a: String, b: String, threshold: Double = 0.1): Boolean {
 *         return diffRatio(a, b) >= threshold
 *     }
 *
 *     /**
 *      * 生成人类可读的 diff 摘要。
 *      *
 *      * @return 描述变更的字符串，如 "修改 5 个字符 (差异 12.3%)"
 */
 *     fun diffSummary(a: String, b: String): String {
 *         val distance = levenshteinDistance(a, b)
 *         val ratio = diffRatio(a, b)
 *         val percent = String.format("%.1f%%", ratio * 100)
 *
 *         return if (distance == 0) {
 *             "无变更"
 *         } else if (a.length == b.length && distance <= 2) {
 *             "微调 $distance 个字符 (差异 $percent)"
 *         } else {
 *             "修改 $distance 个字符 (差异 $percent)"
 *         }
 *     }
 * }
