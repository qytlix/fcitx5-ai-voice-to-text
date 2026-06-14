package org.fcitx.fcitx5.android.voice.data

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.voice.core.bridge.AudioRecorder
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Android AudioRecord 实现的录音器。
 *
 * 采集 16kHz / 16-bit / 单声道 PCM 音频，输出为 WAV 格式的 base64 字符串。
 * 录音在后台线程执行，不阻塞主线程。
 *
 * @param sampleRate   采样率（Hz），ASR 常用 16000
 * @param channelConfig 声道配置，默认单声道
 * @param audioFormat   音频格式，默认 16-bit PCM
 */
class AndroidAudioRecorder(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) : AudioRecorder {

    companion object {
        private const val TAG = "AndroidAudioRecorder"
    }

    /** 所需的 Android 权限列表 */
    val requiredPermissions: List<String> = listOf(Manifest.permission.RECORD_AUDIO)

    /** 录音缓冲区大小 */
    private val bufferSize: Int by lazy {
        val size = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        // 确保缓冲区至少能容纳 100ms 的数据
        val minSize = sampleRate * 2 / 10 // 100ms at 16kHz 16-bit
        maxOf(size, minSize)
    }

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var _isRecording = false

    override val isRecording: Boolean get() = _isRecording

    /**
     * 开始录音。
     *
     * 在 IO 调度器上创建 AudioRecord 实例并开始采集。
     * 录音数据写入内部 ByteArrayOutputStream。
     */
    override suspend fun startRecording() = withContext(Dispatchers.IO) {
        if (_isRecording) {
            Log.w(TAG, "已经在录音中")
            return@withContext
        }

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord 初始化失败 (state=${record.state})")
            }

            audioRecord = record
            _isRecording = true
            record.startRecording()

            Log.d(TAG, "录音已启动: ${sampleRate}Hz, bufferSize=${bufferSize}")
        } catch (e: SecurityException) {
            _isRecording = false
            throw RuntimeException("录音权限被拒绝", e)
        } catch (e: Exception) {
            _isRecording = false
            throw RuntimeException("启动录音失败: ${e.message}", e)
        }
    }

    /**
     * 停止录音并返回 base64 编码的 WAV 音频数据。
     *
     * 流程：
     * 1. 停止 AudioRecord 采集
     * 2. 从 ByteArrayOutputStream 读取 PCM 数据
     * 3. 添加 WAV 文件头
     * 4. Base64 编码
     *
     * @return base64 编码的 WAV 音频数据
     */
    override suspend fun stopRecording(): String = withContext(Dispatchers.IO) {
        val record = audioRecord ?: throw RuntimeException("没有正在进行的录音")

        _isRecording = false
        record.stop()

        // 从 AudioRecord 读取 PCM 数据
        val pcmData = readPcmData(record)
        record.release()
        audioRecord = null

        Log.d(TAG, "录音已停止: ${pcmData.size} bytes PCM")

        // 添加 WAV 头并编码为 base64
        val wavData = createWavFile(pcmData, sampleRate, audioFormat)
        Base64.getEncoder().encodeToString(wavData)
    }

    /**
     * 从 AudioRecord 读取所有 PCM 数据。
     */
    private fun readPcmData(record: AudioRecord): ByteArray {
        val buffer = ByteArray(bufferSize)
        val outputStream = ByteArrayOutputStream()

        while (_isRecording || record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = record.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                outputStream.write(buffer, 0, bytesRead)
            } else if (bytesRead < 0) {
                Log.w(TAG, "AudioRecord.read() 返回错误: $bytesRead")
                break
            }
        }

        return outputStream.toByteArray()
    }

    /**
     * 将 PCM 原始数据包装为 WAV 格式。
     *
     * WAV 文件结构：
     * - RIFF header (12 bytes)
     * - fmt chunk (24 bytes)
     * - data chunk (8 bytes + PCM data)
     */
    private fun createWavFile(pcmData: ByteArray, sampleRate: Int, audioFormat: Int): ByteArray {
        val bitsPerSample = when (audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
        val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 44 + dataSize

        val wav = ByteArray(totalSize)
        var offset = 0

        // RIFF header
        writeString(wav, offset, "RIFF"); offset += 4
        writeInt32LE(wav, offset, totalSize - 8); offset += 4
        writeString(wav, offset, "WAVE"); offset += 4

        // fmt chunk
        writeString(wav, offset, "fmt "); offset += 4
        writeInt32LE(wav, offset, 16); offset += 4          // chunk size
        writeInt16LE(wav, offset, 1); offset += 2            // PCM format
        writeInt16LE(wav, offset, channels); offset += 2     // channels
        writeInt32LE(wav, offset, sampleRate); offset += 4   // sample rate
        writeInt32LE(wav, offset, byteRate); offset += 4     // byte rate
        writeInt16LE(wav, offset, blockAlign); offset += 2   // block align
        writeInt16LE(wav, offset, bitsPerSample); offset += 2 // bits per sample

        // data chunk
        writeString(wav, offset, "data"); offset += 4
        writeInt32LE(wav, offset, dataSize); offset += 4

        // PCM data
        System.arraycopy(pcmData, 0, wav, offset, dataSize)

        return wav
    }

    private fun writeString(buffer: ByteArray, offset: Int, value: String) {
        for (i in value.indices) {
            buffer[offset + i] = value[i].code.toByte()
        }
    }

    private fun writeInt16LE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32LE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
