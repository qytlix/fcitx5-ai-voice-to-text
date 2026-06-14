package org.fcitx.fcitx5.android.voice.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import org.fcitx.fcitx5.android.voice.R
import org.fcitx.fcitx5.android.voice.bridge.ClipboardCommitTextHandler
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeState
import org.fcitx.fcitx5.android.voice.core.domain.TranscribeUseCase
import org.fcitx.fcitx5.android.voice.databinding.ActivityMainBinding
import org.fcitx.fcitx5.android.voice.data.AndroidAudioRecorder
import org.fcitx.fcitx5.android.voice.data.RetrofitTranscribeService

/**
 * 主 Activity — 语音输入独立 App 的入口。
 *
 * 提供录音按钮、风格选择、结果展示等 UI。
 * 通过 [VoiceInputViewModel] 管理状态，使用 ViewBinding 操作视图。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: VoiceInputViewModel

    /** 录音权限请求 */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.onStartRecording()
        } else {
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 创建依赖
        val audioRecorder = AndroidAudioRecorder()
        val transcribeService = RetrofitTranscribeService()
        val commitTextHandler = ClipboardCommitTextHandler(this)
        val transcribeUseCase = TranscribeUseCase(
            audioRecorder = audioRecorder,
            transcribeService = transcribeService,
            commitTextHandler = commitTextHandler
        )

        // 创建 ViewModel
        viewModel = VoiceInputViewModel(transcribeUseCase)

        // 初始化 UI
        setupStyleSelector()
        setupRecordButton()
        setupCopyButton()
        setupRetryButton()
        observeState()
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
     * 设置录音按钮的点击监听。
     *
     * Idle/Error 状态 → 检查权限 → 开始录音
     * Recording 状态 → 停止录音
     */
    private fun setupRecordButton() {
        binding.recordButton.setOnClickListener {
            val currentState = viewModel.state.value
            when (currentState) {
                is TranscribeState.Idle,
                is TranscribeState.Error,
                is TranscribeState.WaitingForPermission -> {
                    // 检查录音权限
                    if (ContextCompat.checkSelfPermission(
                            this, Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnClickListener
                    }
                    viewModel.onStartRecording()
                }
                is TranscribeState.Recording -> {
                    viewModel.onStopRecording()
                }
                is TranscribeState.Processing,
                is TranscribeState.Success -> {
                    // 处理中或成功状态下点击按钮重新开始
                    viewModel.resetToIdle()
                    viewModel.onStartRecording()
                }
            }
        }
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
            viewModel.onStartRecording()
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
                        binding.retryButton.visibility = android.view.View.GONE
                    }

                    is TranscribeState.WaitingForPermission -> {
                        binding.statusText.text = getString(R.string.error_no_permission)
                    }

                    is TranscribeState.Recording -> {
                        binding.statusText.text = getString(R.string.status_recording)
                        binding.recordButton.text = getString(R.string.btn_stop_recording)
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
                        binding.copyButton.visibility = android.view.View.VISIBLE
                        binding.retryButton.visibility = android.view.View.GONE
                    }

                    is TranscribeState.Error -> {
                        binding.statusText.text = getString(R.string.status_error)
                        binding.recordButton.text = getString(R.string.btn_retry)
                        binding.recordButton.isEnabled = true
                        binding.resultText.text = state.message
                        binding.retryButton.visibility = android.view.View.VISIBLE
                        binding.copyButton.visibility = android.view.View.GONE
                    }
                }
            }
        }
    }
}
