package org.fcitx.fcitx5.android.voice.core.test

import org.fcitx.fcitx5.android.voice.core.bridge.CommitTextHandler

/**
 * Mock 上屏处理器 — 仅记录最后一次上屏文字，不实际提交到输入法。
 *
 * 用于单元测试 [org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase]。
 */
class MockCommitTextHandler : CommitTextHandler {

    /** 记录最后一次 commit 的文字 */
    var lastCommittedText: String? = null

    /** 记录最后一次 setComposing 的文字 */
    var lastComposingText: String? = null

    /** commitText 被调用的次数 */
    var commitCount: Int = 0
        private set

    /** setComposingText 被调用的次数 */
    var composingCount: Int = 0
        private set

    /** clearComposingText 被调用的次数 */
    var clearComposingCount: Int = 0
        private set

    override fun commitText(text: String) {
        lastCommittedText = text
        commitCount++
    }

    override fun setComposingText(text: String) {
        lastComposingText = text
        composingCount++
    }

    override fun clearComposingText() {
        lastComposingText = null
        clearComposingCount++
    }

    /** 重置所有状态 */
    fun reset() {
        lastCommittedText = null
        lastComposingText = null
        commitCount = 0
        composingCount = 0
        clearComposingCount = 0
    }
}