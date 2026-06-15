/**
 * // REFERENCE: Copy this file to the Fcitx5 Android main app
 * // Package: org.fcitx.fcitx5.android.input.voice
 * //
 * // 管理语音输入插件 Service 的绑定、生命周期、AIDL 调用与回调。
 * //
 * // 使用方式：
 * //   在 FcitxInputMethodService 中实例化本类，在 onCreateInputView() 中
 * //   调用 bindPlugin()，在 onDestroy() 中调用 unbindPlugin()。
 * //   在键盘语音按钮的 OnTouchListener 中调用 onVoiceButtonDown/Up/Cancel。
 * //
 * // @since 0.2.0
 *
 * package org.fcitx.fcitx5.android.input.voice
 *
 * import android.content.ComponentName
 * import android.content.Context
 * import android.content.Intent
 * import android.content.ServiceConnection
 * import android.os.IBinder
 * import android.util.Log
 * import android.view.inputmethod.InputConnection
 * import org.fcitx.fcitx5.android.voice.IVoiceInputCallback
 * import org.fcitx.fcitx5.android.voice.IVoiceInputPlugin
 *
 * /**
 *  * 语音输入管理器 — 封装与语音插件 Service 的全部交互。
 *  *
 *  * ## 生命周期
 *  * - [bindPlugin] → 绑定插件 Service
 *  * - [onVoiceButtonDown] → ACTION_DOWN 触发录音开始
 *  * - [onVoiceButtonUp] → ACTION_UP 触发停止录音 + 转录
 *  * - [onVoiceButtonCancel] → 取消当前录音
 *  * - [unbindPlugin] → 解绑并释放资源
 *  *
 *  * ## 线程安全
 *  * - AIDL 回调在 Binder 线程池调用，操作 UI 或 InputConnection 前需切换到主线程
 *  * - 使用 Handler(Looper.getMainLooper()) 进行线程切换
 *  */
 * class VoiceInputManager(private val context: Context) {
 *
 *     companion object {
 *         private const val TAG = "VoiceInputManager"
 *
 *         /** 语音插件的目标包名（需与实际插件 APK 的包名一致） */
 *         const val PLUGIN_PACKAGE = "org.fcitx.fcitx5.android.voice"
 *
 *         /** 插件 Service 的 action（与插件 AndroidManifest intent-filter 一致） */
 *         const val PLUGIN_ACTION = "org.fcitx.fcitx5.android.plugin.SERVICE"
 *     }
 *
 *     // --- 状态 ---
 *     private var plugin: IVoiceInputPlugin? = null
 *     private var isBound: Boolean = false
 *     private var currentStyle: String = "正式"
 *     private var currentSessionId: String? = null
 *
 *     // --- 回调监听 ---
 *     private var resultListener: VoiceResultListener? = null
 *     private var errorListener: VoiceErrorListener? = null
 *     private var stateListener: VoiceStateListener? = null
 *
 *     // --- InputConnection（当前编辑框的连接） ---
 *     private var inputConnection: InputConnection? = null
 *
 *     /**
 *      * AIDL 回调实现 — 接收插件的转录结果、错误和状态变更。
 *      *
 *      * 回调在 Binder 线程池调用，需切换到主线程操作 UI。
 *      */
 *     private val callback = object : IVoiceInputCallback.Stub() {
 *
 *         override fun onResult(text: String, originalText: String, durationMs: Long, sessionId: String) {
 *             Log.d(TAG, "onResult: text=$text, session=$sessionId")
 *             currentSessionId = sessionId
 *
 *             // 切换到主线程执行 InputConnection 操作
 *             runOnMainThread {
 *                 // 1. 提交风格化文本到当前输入框
 *                 inputConnection?.commitText(text, 1)
 *
 *                 // 2. 通知反馈采集器记录本次转录
 *                 resultListener?.onVoiceResult(
 *                     sessionId = sessionId,
 *                     originalText = originalText,
 *                     styledText = text,
 *                     style = currentStyle,
 *                     durationMs = durationMs
 *                 )
 *             }
 *         }
 *
 *         override fun onError(message: String) {
 *             Log.e(TAG, "onError: $message")
 *             runOnMainThread {
 *                 errorListener?.onVoiceError(message)
 *             }
 *         }
 *
 *         override fun onStateChanged(state: Int) {
 *             Log.d(TAG, "onStateChanged: $state")
 *             runOnMainThread {
 *                 stateListener?.onVoiceStateChanged(state)
 *             }
 *         }
 *     }
 *
 *     /** Service 绑定连接 */
 *     private val serviceConnection = object : ServiceConnection {
 *         override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
 *             Log.d(TAG, "onServiceConnected: $name")
 *             plugin = IVoiceInputPlugin.Stub.asInterface(service)
 *             isBound = true
 *
 *             // 注册回调
 *             plugin?.registerCallback(callback)
 *
 *             // 同步风格到插件
 *             plugin?.setStyle(currentStyle)
 *
 *             Log.d(TAG, "插件已连接，版本: ${plugin?.version}")
 *         }
 *
 *         override fun onServiceDisconnected(name: ComponentName?) {
 *             Log.w(TAG, "onServiceDisconnected: $name")
 *             plugin = null
 *             isBound = false
 *         }
 *     }
 *
 *     // ========================================================================
 *     // 公开 API
 *     // ========================================================================
 *
 *     /** 绑定语音插件 Service */
 *     fun bindPlugin() {
 *         if (isBound) {
 *             Log.w(TAG, "已绑定，忽略重复 bindPlugin()")
 *             return
 *         }
 *
 *         val intent = Intent(PLUGIN_ACTION).setPackage(PLUGIN_PACKAGE)
 *         val result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
 *         Log.d(TAG, "bindService result: $result")
 *     }
 *
 *     /** 解绑语音插件 Service */
 *     fun unbindPlugin() {
 *         if (isBound) {
 *             plugin?.registerCallback(null)  // 清理回调引用
 *             context.unbindService(serviceConnection)
 *             plugin = null
 *             isBound = false
 *         }
 *     }
 *
 *     /** 语音按钮按下（ACTION_DOWN）— 开始录音 */
 *     fun onVoiceButtonDown() {
 *         if (!isBound || plugin == null) {
 *             Log.w(TAG, "插件未绑定，无法开始录音")
 *             errorListener?.onVoiceError("语音插件未就绪")
 *             return
 *         }
 *         plugin?.startRecording()
 *     }
 *
 *     /** 语音按钮释放（ACTION_UP）— 停止录音并转录 */
 *     fun onVoiceButtonUp() {
 *         plugin?.stopRecording()
 *     }
 *
 *     /** 滑动取消 — 丢弃当前录音 */
 *     fun onVoiceButtonCancel() {
 *         plugin?.cancelRecording()
 *         currentSessionId = null
 *     }
 *
 *     /** 设置转录风格 */
 *     fun setStyle(style: String) {
 *         currentStyle = style
 *         plugin?.setStyle(style)
 *     }
 *
 *     /** 更新当前 InputConnection（在 onStartInputView / onUpdateSelection 中调用） */
 *     fun updateInputConnection(ic: InputConnection?) {
 *         inputConnection = ic
 *     }
 *
 *     /** 查询是否正在录音 */
 *     fun isRecording(): Boolean = plugin?.isRecording ?: false
 *
 *     /** 获取当前会话 ID */
 *     fun getCurrentSessionId(): String? = currentSessionId
 *
 *     // ========================================================================
 *     // 监听器接口
 *     // ========================================================================
 *
 *     interface VoiceResultListener {
 *         fun onVoiceResult(
 *             sessionId: String,
 *             originalText: String,
 *             styledText: String,
 *             style: String,
 *             durationMs: Long
 *         )
 *     }
 *
 *     fun interface VoiceErrorListener {
 *         fun onVoiceError(message: String)
 *     }
 *
 *     fun interface VoiceStateListener {
 *         fun onVoiceStateChanged(state: Int)
 *     }
 *
 *     fun setOnVoiceResultListener(listener: VoiceResultListener?) {
 *         resultListener = listener
 *     }
 *
 *     fun setOnVoiceErrorListener(listener: VoiceErrorListener?) {
 *         errorListener = listener
 *     }
 *
 *     fun setOnVoiceStateListener(listener: VoiceStateListener?) {
 *         stateListener = listener
 *     }
 *
 *     // ========================================================================
 *     // 内部工具
 *     // ========================================================================
 *
 *     private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
 *
 *     private fun runOnMainThread(action: () -> Unit) {
 *         if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
 *             action()
 *         } else {
 *             mainHandler.post(action)
 *         }
 *     }
 * }
