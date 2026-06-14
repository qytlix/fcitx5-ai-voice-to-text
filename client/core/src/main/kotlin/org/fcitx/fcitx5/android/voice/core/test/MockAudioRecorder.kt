package org.fcitx.fcitx5.android.voice.core.test

import org.fcitx.fcitx5.android.voice.core.bridge.AudioRecorder

/**
 * Mock 录音器 — 返回固定 base64 音频数据，不涉及真实硬件。
 *
 * 用于单元测试 [org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase]。
 *
 * 模拟约 0.5 秒的 16kHz 16-bit 单声道静音 WAV 数据 base64。
 */
class MockAudioRecorder : AudioRecorder {

    /** 内部状态：是否正在录音 */
    private var _isRecording = false

    override val isRecording: Boolean get() = _isRecording

    /** 可自定义返回的 base64 数据 */
    var mockBase64Audio: String = MOCK_WAV_BASE64

    /** 设为 true 模拟录音失败 */
    var shouldFailOnStart: Boolean = false

    /** 设为 true 模拟编码失败 */
    var shouldFailOnStop: Boolean = false

    override suspend fun startRecording() {
        if (shouldFailOnStart) {
            throw RuntimeException("模拟录音启动失败")
        }
        _isRecording = true
    }

    override suspend fun stopRecording(): String {
        _isRecording = false
        if (shouldFailOnStop) {
            throw RuntimeException("模拟音频编码失败")
        }
        return mockBase64Audio
    }

    companion object {
        /**
         * 模拟 0.5 秒 16kHz 16-bit 单声道静音 WAV 的 base64 编码。
         * WAV header (44 bytes) + PCM 静音数据 (16000 × 2 × 0.5 = 16000 bytes)
         */
        val MOCK_WAV_BASE64: String = run {
            val sampleRate = 16000
            val bitsPerSample = 16
            val channels = 1
            val durationSeconds = 0.5
            val dataSize = (sampleRate * bitsPerSample / 8 * channels * durationSeconds).toInt()
            val totalSize = 44 + dataSize

            val buffer = ByteArray(totalSize)

            // RIFF header
            buffer[0] = 'R'.code.toByte()
            buffer[1] = 'I'.code.toByte()
            buffer[2] = 'F'.code.toByte()
            buffer[3] = 'F'.code.toByte()
            // File size - 8
            writeInt32LE(buffer, 4, totalSize - 8)
            buffer[8] = 'W'.code.toByte()
            buffer[9] = 'A'.code.toByte()
            buffer[10] = 'V'.code.toByte()
            buffer[11] = 'E'.code.toByte()

            // fmt chunk
            buffer[12] = 'f'.code.toByte()
            buffer[13] = 'm'.code.toByte()
            buffer[14] = 't'.code.toByte()
            buffer[15] = ' '.code.toByte()
            writeInt32LE(buffer, 16, 16) // chunk size
            writeInt16LE(buffer, 20, 1.toShort())   // PCM format
            writeInt16LE(buffer, 22, channels.toShort())
            writeInt32LE(buffer, 24, sampleRate)
            writeInt32LE(buffer, 28, sampleRate * channels * bitsPerSample / 8) // byte rate
            writeInt16LE(buffer, 32, (channels * bitsPerSample / 8).toShort())  // block align
            writeInt16LE(buffer, 34, bitsPerSample.toShort())

            // data chunk
            buffer[36] = 'd'.code.toByte()
            buffer[37] = 'a'.code.toByte()
            buffer[38] = 't'.code.toByte()
            buffer[39] = 'a'.code.toByte()
            writeInt32LE(buffer, 40, dataSize)
            // PCM data 静音（全零）

            java.util.Base64.getEncoder().encodeToString(buffer)
        }

        private fun writeInt16LE(buffer: ByteArray, offset: Int, value: Short) {
            buffer[offset] = (value.toInt() and 0xFF).toByte()
            buffer[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }

        private fun writeInt32LE(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value and 0xFF).toByte()
            buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
            buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
            buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
    }
}