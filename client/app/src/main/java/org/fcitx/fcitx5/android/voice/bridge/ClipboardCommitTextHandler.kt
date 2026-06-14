package org.fcitx.fcitx5.android.voice.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import org.fcitx.fcitx5.android.voice.core.bridge.CommitTextHandler

/**
 * 独立 App 模式的上屏实现。
 *
 * 在 Phase 2（独立 App）阶段，通过系统剪贴板实现文字"上屏"。
 * 用户点击结果文本框后可直接粘贴，或 App 自动将结果写入剪贴板。
 *
 * Phase 3 适配为 Fcitx5 插件后，此实现将被 [Fcitx5CommitTextHandler] 替换，
 * 后者通过 InputConnection.commitText() 实现真正的输入法上屏。
 *
 * @param context Android Context
 * @param autoCopy 是否自动将结果写入剪贴板
 */
class ClipboardCommitTextHandler(
    private val context: Context,
    private val autoCopy: Boolean = true
) : CommitTextHandler {

    companion object {
        private const val TAG = "ClipboardCommitTextHandler"
    }

    /** 最后一次提交的文字（供 UI 显示） */
    @Volatile
    var lastText: String? = null
        private set

    override fun commitText(text: String) {
        lastText = text
        Log.d(TAG, "commitText: $text")

        if (autoCopy) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("voice_input", text)
                clipboard.setPrimaryClip(clip)
                Log.d(TAG, "已复制到剪贴板")
            }
        }
    }

    override fun setComposingText(text: String) {
        // 独立 App 模式不支持 composing text 预览
        Log.d(TAG, "setComposingText (ignored): $text")
    }

    override fun clearComposingText() {
        // 独立 App 模式不支持 composing text 预览
        Log.d(TAG, "clearComposingText (ignored)")
    }
}
