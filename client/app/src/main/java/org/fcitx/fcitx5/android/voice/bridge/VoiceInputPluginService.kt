package org.fcitx.fcitx5.android.voice.bridge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.voice.IVoiceInputCallback
import org.fcitx.fcitx5.android.voice.IVoiceInputPlugin
import org.fcitx.fcitx5.android.voice.core.bridge.SessionIdProvider
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase
import org.fcitx.fcitx5.android.voice.core.network.TranscribeException
import org.fcitx.fcitx5.android.voice.data.AndroidAudioRecorder
import org.fcitx.fcitx5.android.voice.data.RetrofitTranscribeService

/**
 * Fcitx5 语音输入插件服务。
 *
 * 作为 AIDL-bound Service 被 Fcitx5 主应用发现和绑定。
 * 实现 [IVoiceInputPlugin] 接口，向主应用暴露录音和转录能力。
 *
 * ## 生命周期
 * - **创建**: Fcitx5 主应用 bindService 时
 * - **销毁**: Fcitx5 主应用 unbindService 时
 * - **录音会话**: 每次 startRecording → stopRecording 为一个会话，
 *   在 [serviceScope] 中启动协程处理
 *
 * ## 进程
 * 在独立进程 `:fcitx_plugin_voice` 中运行（见 AndroidManifest.xml），
 * 与 Fcitx5 主应用通过 AIDL IPC 通信。
 *
 * ## 数据流
 * ```
 * startRecording() → audioRecorder.startRecording()
 * stopRecording()  → audioRecorder.stopRecording() → get base64 WAV
 *                  → TranscribeUseCase.execute(audioSource=base64)
 *                  → IVoiceInputCallback.onResult(text, originalText, durationMs)
 *                  → Fcitx5 主应用: InputConnection.commitText(text, 1)
 * ```
 *
 * ## 线程安全
 * AIDL 方法在 Binder 线程池调用。录音和 HTTP 请求通过协程切换到
 * Dispatchers.IO。所有状态字段使用 @Volatile 保证可见性。
 */
class VoiceInputPluginService : Service() {

    companion object {
        private const val TAG = "VoiceInputPluginService"

        /** 插件版本号（语义化版本） */
        private const val PLUGIN_VERSION = "0.2.0"

        /** 默认转录风格 */
        private const val DEFAULT_STYLE = "正式"

        /** 默认服务端 URL */
        private const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"

        /** SharedPreferences 文件名 */
        private const val PREFS_NAME = "voice_input_plugin_prefs"

        /** 风格持久化 key */
        private const val KEY_CURRENT_STYLE = "pref_current_style"

        /** 服务端 URL key */
        private const val KEY_BASE_URL = "pref_base_url"
    }

    // --- 依赖 ---
    private val audioRecorder = AndroidAudioRecorder()
    private val commitTextHandler = NoOpCommitTextHandler()
    private val sessionIdProvider: SessionIdProvider = SessionIdProvider.DEFAULT

    /** 当前使用的 baseUrl（内部可变，通过 SharedPreferences 持久化） */
    @Volatile
    private var currentBaseUrl: String = DEFAULT_BASE_URL

    /** 当前使用的 TranscribeService（baseUrl 变化时重新创建） */
    @Volatile
    private var transcribeService: RetrofitTranscribeService = RetrofitTranscribeService(currentBaseUrl)

    /** TranscribeUseCase（baseUrl 变化时重新创建） */
    @Volatile
    private var transcribeUseCase: TranscribeUseCase = TranscribeUseCase(
        audioRecorder = audioRecorder,
        transcribeService = transcribeService,
        commitTextHandler = commitTextHandler
    )

    // --- 并发控制 ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前的转录协程任务（进行中时非 null） */
    private var transcribeJob: Job? = null

    /** 已注册的回调（通过 registerCallback 设置） */
    @Volatile
    private var callback: IVoiceInputCallback? = null

    /** 当前选中的风格（持久化到 SharedPreferences） */
    @Volatile
    private var currentStyle: String = DEFAULT_STYLE

    /** 当前会话 ID（startRecording 时生成，stopRecording 回调时携带） */
    @Volatile
    private var currentSessionId: String? = null

    /** 是否正在录音 */
    @Volatile
    private var _isRecording = false

    /** SharedPreferences（延迟初始化，onCreate 时可用） */
    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        // 恢复持久化的设置
        currentStyle = prefs.getString(KEY_CURRENT_STYLE, DEFAULT_STYLE) ?: DEFAULT_STYLE
        currentBaseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        rebuildTranscribeService()
        Log.d(TAG, "Service 创建，baseUrl=$currentBaseUrl, style=$currentStyle")
    }

    /** 根据当前 baseUrl 重建 HTTP 客户端 */
    private fun rebuildTranscribeService() {
        transcribeService = RetrofitTranscribeService(currentBaseUrl)
        transcribeUseCase = TranscribeUseCase(
            audioRecorder = audioRecorder,
            transcribeService = transcribeService,
            commitTextHandler = commitTextHandler
        )
    }

    /**
     * AIDL Binder 实现。
     *
     * 所有方法在 Binder 线程池调用。录音操作委托给 [serviceScope] 中的协程。
     */
    private val binder = object : IVoiceInputPlugin.Stub() {

        override fun startRecording() {
            Log.d(TAG, "startRecording() called")

            if (_isRecording) {
                Log.w(TAG, "Already recording, ignoring startRecording()")
                return
            }

            // 生成新会话 ID
            currentSessionId = sessionIdProvider.generateSessionId()
            Log.d(TAG, "新会话: $currentSessionId")

            transcribeJob = serviceScope.launch {
                try {
                    _isRecording = true
                    updateState(IVoiceInputCallback.STATE_RECORDING)
                    Log.d(TAG, "开始录音 (style=$currentStyle, session=$currentSessionId)")

                    // 直接调用 AudioRecorder 开始录音
                    // 整个过程在协程中，startRecording() 内部切换到 IO 线程
                    audioRecorder.startRecording()

                } catch (e: Exception) {
                    _isRecording = false
                    currentSessionId = null
                    updateState(IVoiceInputCallback.STATE_IDLE)
                    Log.e(TAG, "启动录音失败", e)
                    callback?.onError("录音启动失败: ${e.message}")
                }
            }
        }

        override fun stopRecording() {
            Log.d(TAG, "stopRecording() called")

            if (!_isRecording) {
                Log.w(TAG, "Not recording, ignoring stopRecording()")
                return
            }

            // 取消录音阶段的协程
            transcribeJob?.cancel()
            transcribeJob = null

            transcribeJob = serviceScope.launch {
                try {
                    updateState(IVoiceInputCallback.STATE_PROCESSING)
                    Log.d(TAG, "停止录音并开始转录")

                    // 停止录音并获取 base64 音频
                    val audioBase64 = audioRecorder.stopRecording()
                    _isRecording = false

                    Log.d(TAG, "音频已获取: ${audioBase64.length} chars base64")

                    // 通过 TranscribeUseCase 发送请求（复用核心逻辑）
                    // 传入 audioSource 参数跳过录音环节，直接使用刚获取的音频
                    val result = transcribeUseCase.execute(
                        style = currentStyle,
                        audioSource = audioBase64,
                        sessionId = currentSessionId
                    )

                    Log.d(TAG, "转录成功: ${result.text} (${result.totalDurationMs}ms)")

                    // 通过 AIDL 回调将结果送达主应用
                    val sid = currentSessionId ?: ""
                    callback?.onResult(
                        result.text,
                        result.originalText,
                        result.totalDurationMs,
                        sid
                    )

                } catch (e: TranscribeException) {
                    _isRecording = false
                    Log.e(TAG, "转录失败", e)
                    callback?.onError(e.message ?: "转录失败")
                } catch (e: Exception) {
                    _isRecording = false
                    Log.e(TAG, "转录异常", e)
                    callback?.onError("转录异常: ${e.message}")
                } finally {
                    updateState(IVoiceInputCallback.STATE_IDLE)
                }
            }
        }

        override fun cancelRecording() {
            Log.d(TAG, "cancelRecording() called")

            transcribeJob?.cancel()
            transcribeJob = null
            _isRecording = false
            currentSessionId = null
            updateState(IVoiceInputCallback.STATE_IDLE)
        }

        override fun setStyle(style: String) {
            Log.d(TAG, "setStyle: $style")
            currentStyle = style
            // 持久化到 SharedPreferences
            prefs.edit().putString(KEY_CURRENT_STYLE, style).apply()
        }

        override fun registerCallback(callback: IVoiceInputCallback?) {
            Log.d(TAG, "registerCallback: $callback")
            this@VoiceInputPluginService.callback = callback
        }

        override fun isRecording(): Boolean {
            return _isRecording
        }

        override fun getVersion(): String {
            return PLUGIN_VERSION
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind: ${intent?.action} ${intent?.`package`}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        // 清理回调引用
        callback = null
        return false // 不重绑定
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 通知注册的回调当前状态变化。
     *
     * Binder 调用是异步的，跨进程异常不会影响插件自身。
     */
    private fun updateState(state: Int) {
        try {
            callback?.onStateChanged(state)
        } catch (e: Exception) {
            Log.w(TAG, "通知状态变更失败", e)
        }
    }
}