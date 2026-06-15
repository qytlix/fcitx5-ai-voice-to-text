// IVoiceInputCallback.aidl
package org.fcitx.fcitx5.android.voice;

/**
 * Fcitx5 语音输入插件的状态/结果回调接口。
 *
 * Fcitx5 主应用的 InputMethodService 实现此接口，注册到插件后，
 * 插件在录音/转录结果就绪时通过回调通知主应用。
 *
 * 回调由 Binder 线程池调用，主应用需在回调中切换到主线程操作 UI 或 InputConnection。
 */
interface IVoiceInputCallback {

    /**
     * 转录成功完成。
     *
     * @param text 风格化后的最终文本（上屏内容）
     * @param originalText ASR 原始转写
     * @param durationMs 总耗时（毫秒）
     */
    void onResult(String text, String originalText, long durationMs);

    /**
     * 转录过程出错。
     *
     * @param message 人类可读的错误描述
     */
    void onError(String message);

    /**
     * 录音/转录状态变更。
     *
     * @param state 状态常量值：STATE_IDLE / STATE_RECORDING / STATE_PROCESSING
     */
    void onStateChanged(int state);

    /** 空闲，等待用户操作 */
    const int STATE_IDLE = 0;
    /** 正在录音 */
    const int STATE_RECORDING = 1;
    /** 正在处理（编码 + HTTP 请求） */
    const int STATE_PROCESSING = 2;
}