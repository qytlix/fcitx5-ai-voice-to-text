package org.fcitx.fcitx5.android.voice.bridge

import android.util.Log
import org.fcitx.fcitx5.android.voice.core.bridge.CommitTextHandler

/**
 * 插件模式的无操作上屏处理器。
 *
 * 在 Phase 3（Fcitx5 插件）模式下，文本上屏的责任在 Fcitx5 主应用侧
 * （它持有 InputConnection）。插件通过 AIDL 回调 [IVoiceInputCallback.onResult]
 * 将结果传递给主应用，主应用在回调中调用 [android.view.inputmethod.InputConnection.commitText] 完成上屏。
 *
 * 此 Handler 用于满足 [TranscribeUseCase] 对 [CommitTextHandler] 的依赖，
 * 它接收文本但不上屏。真正的上屏行为在 Fcitx5 主应用的
 * [IVoiceInputCallback] 实现中。
 *
 * @see ClipboardCommitTextHandler Phase 2 独立 App 模式的剪贴板上屏实现
 */
class NoOpCommitTextHandler : CommitTextHandler {

    companion object {
        private const val TAG = "NoOpCommitTextHandler"
    }

    /** 最近一次接收到的文本（用于调试和日志检查） */
    @Volatile
    var lastText: String? = null
        private set

    override fun commitText(text: String) {
        lastText = text
        Log.d(TAG, "commitText (no-op, delegated to main app): $text")
        // 实际上屏发生在 Fcitx5 主应用侧的
        // IVoiceInputCallback.onResult() 实现中
    }

    override fun setComposingText(text: String) {
        Log.d(TAG, "setComposingText (no-op): $text")
        // Composing text 由主应用的 InputConnection 管理
    }

    override fun clearComposingText() {
        Log.d(TAG, "clearComposingText (no-op)")
    }
}