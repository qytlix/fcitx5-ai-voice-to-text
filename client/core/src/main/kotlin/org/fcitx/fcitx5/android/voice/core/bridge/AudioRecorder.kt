package org.fcitx.fcitx5.android.voice.core.bridge

/**
 * 音频录音接口。
 *
 * 负责采集麦克风音频并转为 base64 编码的 WAV 数据。
 * 纯 Kotlin 版本定义接口合约，Android 具体实现在 app 模块中。
 *
 * 实现类：
 * - AndroidAudioRecorder — 使用 android.media.AudioRecord（app 模块）
 * - MockAudioRecorder — 单元测试用（返回固定 base64）
 */
interface AudioRecorder {

    /** 当前是否正在录音 */
    val isRecording: Boolean

    /**
     * 开始录音。
     *
     * 启动 AudioRecord 采集 16kHz / 16-bit / 单声道 PCM 数据。
     * 调用此方法前调用方应确保已获取录音权限。
     */
    suspend fun startRecording()

    /**
     * 停止录音并返回 base64 编码的音频数据。
     *
     * 内部流程：PCM ByteArray → 添加 WAV header → Base64.encodeToString
     *
     * @return base64 编码的 WAV 音频数据
     */
    suspend fun stopRecording(): String
}