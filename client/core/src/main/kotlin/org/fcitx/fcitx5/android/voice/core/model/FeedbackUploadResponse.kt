package org.fcitx.fcitx5.android.voice.core.model

/**
 * 反馈上传响应 DTO — 与服务端 POST /v1/feedback 响应一致。
 *
 * @property status   状态："ok" 表示成功
 * @property event_id 服务端分配的事件 ID
 * @property error    错误信息（成功时为 null）
 */
data class FeedbackUploadResponse(
    val status: String,
    val event_id: String? = null,
    val error: String? = null
) {
    /** 是否上传成功 */
    val isSuccess: Boolean get() = status == "ok" && error == null
}
