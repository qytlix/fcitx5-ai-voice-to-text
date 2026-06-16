// IVoiceInputPlugin.aidl
package org.fcitx.fcitx5.android.voice;

import org.fcitx.fcitx5.android.voice.IVoiceInputCallback;

/**
 * Fcitx5 语音输入插件 AIDL 接口。
 *
 * Fcitx5 主应用通过此接口控制插件进行录音和转录。
 * 插件作为独立 APK 运行在独立进程中，通过 AIDL IPC 与主应用通信。
 *
 * 使用流程：
 *   1. 主应用 bindService 发现插件 → onServiceConnected
 *   2. 主应用调用 registerCallback() 注册回调
 *   3. 主应用调用 startRecording() → 插件开始录音
 *   4. 主应用调用 stopRecording() → 插件停止录音、发 HTTP 请求、通过回调返回结果
 *   5. 主应用在回调中调用 InputConnection.commitText() 上屏
 *   6. （可选）主应用调用 setStyle() 在录音前切换风格
 *   7. （可选）主应用调用 cancelRecording() 取消正在进行的录音
 */
interface IVoiceInputPlugin {

    /**
     * 开始录音。
     *
     * 插件开始使用 AudioRecord 采集麦克风音频（16kHz/16-bit/单声道）。
     * 录音会持续到调用 stopRecording() 或 cancelRecording() 为止。
     *
     * 如果已经处于录音状态，此调用被忽略。
     */
    void startRecording();

    /**
     * 停止录音并开始转录。
     *
     * 停止音频采集 → 编码为 base64 WAV → 发送 HTTP POST /v1/transcribe →
     * 收到响应后在注册的 IVoiceInputCallback.onResult() 中返回结果。
     *
     * 如果当前没有录音，此调用被忽略。
     */
    void stopRecording();

    /**
     * 取消当前录音（丢弃音频，不触发转录）。
     *
     * 适用于用户关闭键盘或取消操作。
     * 状态重置为 STATE_IDLE。
     */
    void cancelRecording();

    /**
     * 设置转录风格。
     *
     * 在下一次录音前调用生效。
     *
     * @param style 风格名称：正式 / 精简 / 礼貌 / 翻译_英文 / 自定义
     */
    void setStyle(String style);

    /**
     * 注册结果回调。
     *
     * 插件通过此回调向主应用报告结果和状态变化。
     * 每次 bind 时调用一次即可。重复调用会替换之前的注册。
     *
     * @param callback 回调接口；传 null 可取消注册
     */
    void registerCallback(IVoiceInputCallback callback);

    /**
     * 查询是否正在录音。
     *
     * @return true 表示正在录音
     */
    boolean isRecording();

    /**
     * 获取插件版本号。
     *
     * @return 语义化版本号，如 "0.1.0"
     */
    String getVersion();
}