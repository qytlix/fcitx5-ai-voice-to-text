package org.fcitx.fcitx5.android.voice.core.bridge

import java.util.UUID

/**
 * 会话 ID 生成器接口。
 *
 * 为每次语音输入会话（startRecording → stopRecording）生成唯一标识符，
 * 用于串联 ASR 转写 → 风格化 → 反馈采集的完整生命周期。
 *
 * 实现类：
 * - UuidSessionIdProvider — 基于 UUID v4 生成（默认）
 * - 测试时可 mock 为固定 ID
 */
fun interface SessionIdProvider {

    /** 生成一个新的会话 ID */
    fun generateSessionId(): String

    companion object {
        /** 默认实现：UUID v4 */
        val DEFAULT: SessionIdProvider = SessionIdProvider {
            UUID.randomUUID().toString()
        }
    }
}
