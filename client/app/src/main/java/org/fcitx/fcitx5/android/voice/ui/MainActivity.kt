package org.fcitx.fcitx5.android.voice.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import org.fcitx.fcitx5.android.voice.R
import org.fcitx.fcitx5.android.voice.bridge.ClipboardCommitTextHandler
import org.fcitx.fcitx5.android.voice.bridge.FeedbackCollector
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeState
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase
import org.fcitx.fcitx5.android.voice.data.AndroidAudioRecorder
import org.fcitx.fcitx5.android.voice.data.RetrofitFeedbackService
import org.fcitx.fcitx5.android.voice.data.RetrofitTranscribeService
import org.fcitx.fcitx5.android.voice.databinding.ActivityMainBinding

/**
 * 主 Activity — 语音输入独立 App 的入口。
 *
 * 提供录音按钮、风格选择、结果展示等 UI。
 * 通过 [VoiceInputViewModel] 管理状态，使用 ViewBinding 操作视图。
 *
 * 交互模式：长按录音按钮 → 开始录音；松手 → 停止并转录。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "voice_input_plugin_prefs"
        private const val KEY_BASE_URL = "pref_base_url"
        private const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: VoiceInputViewModel
    private lateinit var feedbackCollector: FeedbackCollector
    private lateinit var feedbackService: RetrofitFeedbackService

    /** SharedPreferences（与插件 Service 共享） */
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    /** 录音权限请求 */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.onPress()
        } else {
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 读取保存的服务端 URL
        val savedUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

        // 创建依赖
        val audioRecorder = AndroidAudioRecorder()
        val transcribeService = RetrofitTranscribeService(savedUrl)
        val commitTextHandler = ClipboardCommitTextHandler(this)
        val transcribeUseCase = TranscribeUseCase(
            audioRecorder = audioRecorder,
            transcribeService = transcribeService,
            commitTextHandler = commitTextHandler
        )
        feedbackService = RetrofitFeedbackService(savedUrl)

        // 创建 ViewModel
        viewModel = VoiceInputViewModel(transcribeUseCase)

        // 创建反馈采集器
        feedbackCollector = FeedbackCollector()

        // 初始化 UI
        setupServerUrlInput(savedUrl)
        setupStyleSelector()
        setupRecordButton()
        setupCopyButton()
        setupRetryButton()
        setupFeedbackSection()
        observeState()
        observeTranscriptionSession()
    }

    /** 设置服务端 URL 输入框 */
    private fun setupServerUrlInput(savedUrl: String) {
        binding.serverUrlInput.setText(savedUrl)
        binding.serverUrlInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val url = s?.toString()?.trim() ?: DEFAULT_BASE_URL
                if (url.isNotBlank()) {
                    prefs.edit().putString(KEY_BASE_URL, url).apply()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /**
     * 设置风格选择 RadioGroup 的监听。
     */
    private fun setupStyleSelector() {
        binding.styleGroup.setOnCheckedChangeListener { _, checkedId ->
            val style = when (checkedId) {
                R.id.styleFormal -> "正式"
                R.id.styleConcise -> "精简"
                R.id.stylePolite -> "礼貌"
                R.id.styleTranslate -> "翻译_英文"
                else -> "正式"
            }
            viewModel.setStyle(style)
        }
    }

    /**
     * 设置录音按钮的长按交互。
     *
     * ACTION_DOWN（按下）→ 检查权限 → 开始录音
     * ACTION_UP / ACTION_CANCEL（松手/取消）→ 停止录音并发送
     */
    private fun setupRecordButton() {
        binding.recordButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handlePress()
                    true // 消费事件，确保接收到 ACTION_UP
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    handleRelease()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 按下操作：检查权限后开始录音。
     */
    private fun handlePress() {
        val currentState = viewModel.state.value

        // 如果正在处理中，忽略按下
        if (currentState is TranscribeState.Processing) {
            Toast.makeText(this, R.string.processing_hint, Toast.LENGTH_SHORT).show()
            return
        }

        // 检查录音权限
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        viewModel.onPress()
    }

    /**
     * 松手操作：停止录音并开始转录。
     */
    private fun handleRelease() {
        viewModel.onRelease()
    }

    /**
     * 设置复制按钮。
     */
    private fun setupCopyButton() {
        binding.copyButton.setOnClickListener {
            val text = viewModel.lastResultText.value
            if (text != null) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("voice_input", text))
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 设置重试按钮。
     */
    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener {
            viewModel.resetToIdle()
            // 检查权限后重新开始
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }
            viewModel.onPress()
        }
    }

    /**
     * 观察 ViewModel 状态变化并更新 UI。
     */
    private fun observeState() {
        lifecycleScope.launchWhenStarted {
            viewModel.state.collectLatest { state ->
                when (state) {
                    is TranscribeState.Idle -> {
                        binding.statusText.text = getString(R.string.status_idle)
                        binding.recordButton.text = getString(R.string.btn_start_recording)
                        binding.recordButton.isEnabled = true
                        binding.retryButton.visibility = View.GONE
                    }

                    is TranscribeState.WaitingForPermission -> {
                        binding.statusText.text = getString(R.string.error_no_permission)
                    }

                    is TranscribeState.Recording -> {
                        binding.statusText.text = getString(R.string.status_recording)
                        binding.recordButton.text = getString(R.string.btn_recording)
                        binding.recordButton.isEnabled = true
                    }

                    is TranscribeState.Processing -> {
                        binding.statusText.text = getString(R.string.status_processing)
                        binding.recordButton.text = getString(R.string.processing_hint)
                        binding.recordButton.isEnabled = false
                    }

                    is TranscribeState.Success -> {
                        binding.statusText.text = getString(R.string.status_success)
                        binding.recordButton.text = getString(R.string.btn_start_recording)
                        binding.recordButton.isEnabled = true
                        binding.resultText.text = state.text
                        binding.originalText.text = state.originalText
                        binding.durationText.text =
                            getString(R.string.milliseconds_format, state.durationMs)
                        binding.copyButton.visibility = View.VISIBLE
                        binding.retryButton.visibility = View.GONE
                    }

                    is TranscribeState.Error -> {
                        binding.statusText.text = getString(R.string.status_error)
                        binding.recordButton.text = getString(R.string.btn_retry)
                        binding.recordButton.isEnabled = true
                        binding.resultText.text = state.message
                        binding.retryButton.visibility = View.VISIBLE
                        binding.copyButton.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * 设置反馈区域 — 提供编辑文本输入框和发送按钮（调试用途）。
     */
    private fun setupFeedbackSection() {
        binding.feedbackSendButton.setOnClickListener {
            val finalText = binding.feedbackEditText.text?.toString() ?: ""
            if (finalText.isBlank()) {
                Toast.makeText(this, "请先输入编辑后的最终文本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 构建反馈事件
            val event = feedbackCollector.compareAndBuild(finalText)
            if (event == null) {
                Toast.makeText(this, "无显著差异，不发送反馈", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 上传反馈
            lifecycleScope.launchWhenStarted {
                try {
                    val response = feedbackService.uploadFeedback(event.toUploadRequest())
                    if (response.isSuccess) {
                        Toast.makeText(
                            this@MainActivity,
                            "反馈已发送 (event_id=${response.event_id})",
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.clearSession()
                        binding.feedbackEditText.text?.clear()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "反馈发送失败: ${response.error}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "反馈上传异常: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.feedbackClearButton.setOnClickListener {
            feedbackCollector.clear()
            viewModel.clearSession()
            binding.feedbackEditText.text?.clear()
            Toast.makeText(this, "已清除反馈缓存", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 观察转录会话 — 转录成功后将元数据传递给 FeedbackCollector。
     */
    private fun observeTranscriptionSession() {
        lifecycleScope.launchWhenStarted {
            viewModel.lastTranscriptionSession.collectLatest { session ->
                if (session != null) {
                    feedbackCollector.recordSession(
                        sessionId = session.sessionId,
                        originalText = session.originalText,
                        styledText = session.styledText,
                        style = session.style,
                        durationMs = session.durationMs
                    )
                    // 将 styledText 预填到编辑框方便用户修改
                    binding.feedbackEditText.setText(session.styledText)
                    binding.feedbackSection.visibility = View.VISIBLE
                }
            }
        }
    }
}
