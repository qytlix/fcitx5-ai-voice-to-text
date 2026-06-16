# 语音输入一体化集成规划

> 目标：将语音输入插件直接合并到 Fcitx5 Android 主应用中，实现单 APK 分发。

## 1. 现状 vs 目标

```
现状（两 APK 架构）                        目标（单 APK 架构）
══════════════════════                    ══════════════════════
┌────────────────────┐  AIDL  ┌────────┐ ┌──────────────────────────────┐
│ fcitx5-android     │◄─────►│ 语音插件│ │ fcitx5-android               │
│ (修改后的主应用)    │  IPC  │ (独立)  │ │ ┌──────────────────────────┐ │
│                    │       │        │ │ │ voice/  录音 + HTTP + 反馈│ │
│ ├ voice button     │       │ ├ 录音  │ │ ├ AndroidAudioRecorder    │ │
│ ├ VoiceInputManager│       │ ├ HTTP  │ │ ├ RetrofitTranscribeService│ │
│ └ feedback system  │       │ └ 回调  │ │ ├ VoiceFeedbackCollector  │ │
└────────────────────┘       └────────┘ │ └ FeedbackUploader        │ │
                                        │ └──────────────────────────┘ │
                                        └──────────────────────────────┘
```

## 2. 核心变更

### 2.1 删除 AIDL 层

| 操作 | 说明 |
|------|------|
| 删除 `IVoiceInputPlugin.aidl` | 不再需要跨进程接口 |
| 删除 `IVoiceInputCallback.aidl` | 回调改为直接函数调用 |
| 删除 `VoiceInputPluginService` | 录音逻辑移入 IME 进程 |
| 删除 `VoiceInputManager` | start/stop/cancel 改为直接调用 |
| 删除 `app/build.gradle.kts` 中 `aidl = true` | 不再需要 AIDL 编译 |

### 2.2 移植插件代码到 fcitx5-android

从 `Fcitx5-ai-voice-to-text-core/client/` 移植以下模块到 `fcitx5-android/app/`：

备注：fcitx5-android 的位置在 `/home/qytlix/Documents/ECNU/Studys/k课程/设计思维/fcitx5-android-work`

```
fcitx5-android/app/src/main/java/org/fcitx/fcitx5/android/voice/
├── audio/
│   └── AndroidAudioRecorder.kt      ← client/app/.../AndroidAudioRecorder.kt
├── network/
│   ├── RetrofitTranscribeService.kt ← client/app/.../RetrofitTranscribeService.kt
│   ├── TranscribeException.kt       ← client/core/.../TranscribeException.kt
│   └── RetryPolicy.kt               ← client/core/.../RetryPolicy.kt
├── domain/
│   ├── TranscribeUseCase.kt         ← client/core/.../TranscribeUseCase.kt
│   ├── TranscribeState.kt           ← client/core/.../TranscribeState.kt
│   └── SessionIdProvider.kt         ← client/core/.../SessionIdProvider.kt
├── model/
│   ├── TranscribeRequest.kt         ← client/core/.../TranscribeRequest.kt
│   └── TranscribeResponse.kt        ← client/core/.../TranscribeResponse.kt
├── feedback/                        ← 已在 reference/ + 已部分集成
│   ├── VoiceFeedbackCollector.kt    ← reference/VoiceFeedbackCollector.kt
│   ├── FeedbackRepository.kt        ← reference/FeedbackRepository.kt
│   ├── FeedbackUploader.kt          ← reference/FeedbackUploader.kt (需加 Retrofit)
│   └── TextDiffUtil.kt             ← reference/TextDiffUtil.kt
└── VoiceInputController.kt          ← 新建：替代 VoiceInputManager
```

### 2.3 VoiceInputController（新核心类）

替代 `VoiceInputManager`，不再通过 AIDL，直接持有录音器和 HTTP 客户端：

```kotlin
class VoiceInputController(private val context: Context) {
    // 直接依赖（非 AIDL）
    private val audioRecorder = AndroidAudioRecorder()
    private val transcribeService = RetrofitTranscribeService(baseUrl)
    private val useCase = TranscribeUseCase(audioRecorder, transcribeService, commitHandler)

    // 公开 API（同原 AIDL 接口）
    fun startRecording()          // 直接调用 audioRecorder.startRecording()
    fun stopRecording()           // 直接调用 useCase.execute()
    fun cancelRecording()         // 取消
    fun setStyle(style: String)   // 持久化

    // 回调改为 Listener 接口（同进程，无需 Binder 线程切换）
    var onResult: (text, originalText, durationMs, sessionId) -> Unit
    var onError: (message) -> Unit
    var onStateChanged: (state) -> Unit
}
```

### 2.4 依赖引入（fcitx5-android 的 build.gradle.kts）

fcitx5-android 已有 `kotlinx-coroutines`，需新增：

```kotlin
// Retrofit（HTTP 请求）
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
```

### 2.5 添加 RECORD_AUDIO 权限

在 `app/src/main/AndroidManifest.xml` 新增：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### 2.6 服务端 URL 持久化

利用现有 `AppPrefs` 体系新增配置项：

```kotlin
// AppPrefs.kt — Keyboard 分类下
val voiceServerUrl = string(
    R.string.voice_server_url,
    "voice_server_url",
    "http://localhost:8080"
)
```

## 3. FcitxInputMethodService 集成变更

```kotlin
class FcitxInputMethodService : LifecycleInputMethodService() {

    // 替换原来的 VoiceInputManager + FeedbackUploader
    private val voiceController = VoiceInputController(this)
    private val feedbackRepository = FeedbackRepository()
    private val feedbackCollector = VoiceFeedbackCollector(feedbackRepository)

    override fun onCreate() {
        // ... 现有逻辑 ...

        // 设置语音回调
        voiceController.onResult = { sessionId, originalText, styledText, style, durationMs ->
            currentInputConnection?.commitText(styledText, 1)
            feedbackCollector.recordSession(sessionId, originalText, styledText, style, durationMs)
        }
        voiceController.onError = { Timber.w("Voice: $it") }
        voiceController.onStateChanged = { /* 更新按钮 UI */ }
    }

    // onStartInputView / onFinishInputView / onDestroy 中
    // 保持现有 feedbackCollector 集成逻辑
}
```

## 4. UI 变更

### 4.1 语音按钮

当前 `KawaiiBarComponent.onStartInput()` 中的语音按钮逻辑已就绪（触摸手势）。移除 `voicePluginGestureListener`，改为直接调用 `voiceController`：

```kotlin
private val voiceGestureListener = CustomGestureView.OnGestureListener { _, event ->
    when (event.type) {
        GestureType.Down -> {
            // 采集上次反馈
            feedbackCollector.checkAndCollect(service.currentInputConnection)
            voiceController.startRecording()
            true
        }
        GestureType.Up -> {
            voiceController.stopRecording()
            true
        }
        else -> false
    }
}
```

### 4.2 语音按钮状态指示

根据 `onStateChanged` 回调更新按钮外观：
- `IDLE` → 麦克风图标
- `RECORDING` → 红色脉冲 / 按下态
- `PROCESSING` → 旋转加载动画

### 4.3 服务端 URL 配置

在 fcitx5 设置 → 虚拟键盘 中新增：
- **语音服务端地址**（EditTextPreference，默认 `http://localhost:8080`）

## 5. 文件变更清单

| 操作 | 文件 |
|------|------|
| ✕ 删除 | `app/src/main/aidl/org/fcitx/fcitx5/android/voice/*.aidl` |
| ✕ 删除 | `input/voice/VoiceInputManager.kt` |
| → 新增 | `voice/audio/AndroidAudioRecorder.kt` |
| → 新增 | `voice/network/RetrofitTranscribeService.kt` |
| → 新增 | `voice/network/TranscribeException.kt` |
| → 新增 | `voice/network/RetryPolicy.kt` |
| → 新增 | `voice/domain/TranscribeUseCase.kt` |
| → 新增 | `voice/domain/TranscribeState.kt` |
| → 新增 | `voice/domain/SessionIdProvider.kt` |
| → 新增 | `voice/model/TranscribeRequest.kt` |
| → 新增 | `voice/model/TranscribeResponse.kt` |
| → 新增 | `voice/VoiceInputController.kt` |
| ✓ 保留 | `input/voice/VoiceFeedbackCollector.kt` |
| ✓ 保留 | `input/voice/FeedbackRepository.kt` |
| ✓ 修改 | `input/voice/FeedbackUploader.kt` (改用 Retrofit) |
| ✓ 保留 | `input/voice/TextDiffUtil.kt` |
| △ 修改 | `AndroidManifest.xml` (+RECORD_AUDIO) |
| △ 修改 | `app/build.gradle.kts` (+Retrofit, -aidl) |
| △ 修改 | `FcitxInputMethodService.kt` |
| △ 修改 | `KawaiiBarComponent.kt` |
| △ 修改 | `AppPrefs.kt` (+voiceServerUrl) |
| △ 修改 | `strings.xml` (+voice_server_url string) |

图例：✕ 删除 → 新增 ✓ 保留 △ 修改

## 6. 实施步骤

### Phase A：依赖与配置（预计 30 分钟）

1. 在 `app/build.gradle.kts` 添加 Retrofit/OkHttp 依赖
2. 在 `AndroidManifest.xml` 添加 `RECORD_AUDIO` 权限
3. 在 `AppPrefs.kt` 添加 `voiceServerUrl` 配置项
4. 在 `strings.xml` 添加相关字符串
5. 删除 AIDL 目录和 `buildFeatures { aidl = true }`

### Phase B：移植核心代码（预计 1 小时）

1. 创建 `voice/` 包目录结构
2. 移植 `AndroidAudioRecorder.kt`（复制 + 调整包名）
3. 移植 Retrofit 网络层（复制 model + network 文件）
4. 移植 domain 层（复制 UseCase + State + SessionIdProvider）
5. 创建 `VoiceInputController.kt`
6. 更新 `FeedbackUploader.kt`（改用 Retrofit 或直接复用 url）

### Phase C：集成（预计 30 分钟）

1. 修改 `FcitxInputMethodService`：用 `VoiceInputController` 替换 `VoiceInputManager`
2. 修改 `KawaiiBarComponent`：改按钮 gesture listener
3. 添加按钮状态 UI 更新逻辑
4. 添加服务端 URL 设置入口

### Phase D：清理与验证（预计 30 分钟）

1. 删除不再需要的代码：`VoiceInputManager`, AIDL 文件
2. 构建验证：`./gradlew :app:assembleDebug`
3. 真机测试：安装 → 启用语音按钮 → 录音 → 上屏 → 反馈

## 7. 风险点

| 风险 | 级别 | 应对 |
|------|------|------|
| Retrofit 与现有 OkHttp/Coroutines 版本冲突 | 中 | 检查 fcitx5-android 已有依赖版本 |
| AudioRecord 在 IME 进程中受限 | 低 | IME 是前台 Service，不受后台限制 |
| SharedPreferences 跨进程读写 | — | 单进程后不再有此问题 |
| 代码迁移引入编译错误 | 中 | 逐文件编译验证 |

## 8. 预期收益

- **安装简化**：1 个 APK 替代 2 个
- **性能提升**：消除 AIDL IPC 开销，同进程直接调用
- **维护简化**：无需维护插件接口兼容性
- **调试便利**：单进程 logcat，问题定位更快
- **权限统一**：RECORD_AUDIO 由 IME 统一管理
