package org.fcitx.fcitx5.android.voice.core.bridge

/**
 * Fcitx5 上屏接口抽象。
 *
 * 解耦核心业务逻辑与 Fcitx5 输入法服务，使 TestUseCase 可以在没有
 * Fcitx5 环境的情况下运行单元测试。
 *
 * 目前采用「独立 App + 系统剪切板上屏」的轻量方案，
 * 后续 Phase 3 适配为 Fcitx5 插件后切换为 InputConnection 上屏。
 *
 * 实现类：
 * - [Fcitx5CommitTextHandler] — Fcitx5 集成实现（Phase 3）
 * - [org.fcitx.fcitx5.android.voice.core.test.MockCommitTextHandler] — 单元测试用
 */
interface CommitTextHandler {

    /**
     * 提交最终文本到当前输入框。
     * 这是核心上屏接口，Fcitx5 插件模式通过 InputConnection.commitText()
     * 实现；独立 App 模式通过 ClipboardManager 实现。
     */
    fun commitText(text: String)

    /**
     * 设置正在拼写中的文本（可选，用于实时预览）。
     * 在 Fcitx5 中对应 setComposingText。
     */
    fun setComposingText(text: String)

    /**
     * 清除正在拼写中的文本。
     */
    fun clearComposingText()
}