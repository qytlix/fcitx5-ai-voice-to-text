# Fcitx5 Android 主应用 — 语音输入插件集成指南

> 版本: 0.2.0 | 最后更新: 2026-06-15

本指南面向 Fcitx5 Android 输入法主应用的开发者，详细说明如何集成语音输入插件。

---

## 1. 概述

语音输入插件以独立 APK 的形式发布（包名 `org.fcitx.fcitx5.android.voice`），
通过 AIDL IPC 与 Fcitx5 主应用双向通信。

```
Fcitx5 主应用（本指南目标）               语音输入插件（独立 APK）
┌─────────────────────────┐     AIDL     ┌─────────────────────────┐
│ FcitxInputMethodService │ ◄──────────► │ VoiceInputPluginService │
│  ├─ VoiceInputManager   │              │  ├─ AndroidAudioRecorder│
│  ├─ VoiceFeedbackCollector│            │  ├─ RetrofitTranscribe  │
│  └─ FeedbackUploader    │              │  └─ TranscribeUseCase   │
└─────────────────────────┘              └─────────────────────────┘
```

### 前提条件

- Fcitx5 Android 主应用项目已存在
- 插件 APK 已安装到同一设备
- 服务端（FastAPI）正在运行

---

## 2. 集成步骤

### 步骤 1：拷贝 AIDL 文件

将以下 2 个 AIDL 文件从插件仓库拷贝到主应用：

**源路径（插件仓库）：**
```
client/app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputPlugin.aidl
client/app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputCallback.aidl
```

**目标路径（主应用）：**
```
app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputPlugin.aidl
app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputCallback.aidl
```

目录不存在则创建。确保包路径 `org/fcitx/fcitx5/android/voice/` 完全一致。

### 步骤 2：开启 AIDL 编译

在主应用的 `app/build.gradle.kts` 中：

```kotlin
android {
    buildFeatures {
        aidl = true
    }
}
```

同步 Gradle 后，Android Studio 会自动生成 `IVoiceInputPlugin.java` 和 `IVoiceInputCallback.java`。

### 步骤 3：添加语音按钮

在键盘布局 XML 中添加语音输入按钮。具体位置取决于你的键盘布局结构。

**示例（Toolbar 方式）：**
```xml
<ImageButton
    android:id="@+id/voice_input_button"
    android:layout_width="48dp"
    android:layout_height="match_parent"
    android:src="@drawable/ic_mic"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="语音输入" />
```

在键盘代码中绑定触摸事件：

```kotlin
voiceButton.setOnTouchListener { view, event ->
    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            voiceInputManager.onVoiceButtonDown()
            true
        }
        MotionEvent.ACTION_UP -> {
            voiceInputManager.onVoiceButtonUp()
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            voiceInputManager.onVoiceButtonCancel()
            true
        }
        else -> false
    }
}
```

### 步骤 4：集成 VoiceInputManager

从 `reference/VoiceInputManager.kt` 拷贝到主应用的适当包路径。

在 `FcitxInputMethodService` 中：

```kotlin
class FcitxInputMethodService : InputMethodService() {

    private val voiceInputManager = VoiceInputManager(this)

    override fun onCreate() {
        super.onCreate()
        voiceInputManager.bindPlugin()
    }

    override fun onCreateInputView(): View {
        // ... 创建键盘视图

        // 设置语音按钮监听
        voiceButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 采集上一次语音输入的用户修改
                    voiceFeedbackCollector.checkAndCollect(currentInputConnection)
                    voiceInputManager.onVoiceButtonDown()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    voiceInputManager.onVoiceButtonUp()
                    true
                }
                else -> false
            }
        }
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        voiceInputManager.updateInputConnection(currentInputConnection)
    }

    override fun onDestroy() {
        voiceInputManager.unbindPlugin()
        super.onDestroy()
    }
}
```

### 步骤 5：集成反馈采集

从插件仓库拷贝以下参考文件到主应用：
- `reference/VoiceFeedbackCollector.kt`
- `reference/FeedbackRepository.kt`
- `reference/FeedbackUploader.kt`
- `reference/TextDiffUtil.kt`

在 `FcitxInputMethodService` 中集成：

```kotlin
class FcitxInputMethodService : InputMethodService() {

    private val feedbackRepository = FeedbackRepository()
    private val feedbackCollector = VoiceFeedbackCollector(feedbackRepository)
    private val feedbackUploader = FeedbackUploader()

    override fun onCreate() {
        super.onCreate()
        voiceInputManager.bindPlugin()

        // 监听转录结果
        voiceInputManager.setOnVoiceResultListener { sessionId, originalText, styledText, style, durationMs ->
            feedbackCollector.recordSession(
                sessionId = sessionId,
                originalText = originalText,
                styledText = styledText,
                style = style,
                durationMs = durationMs
            )
            // commitText 已在 VoiceInputManager 内部完成
        }

        // 监听错误
        voiceInputManager.setOnVoiceErrorListener { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        // 监听状态变更（更新语音按钮 UI）
        voiceInputManager.setOnVoiceStateListener { state ->
            when (state) {
                IVoiceInputCallback.STATE_IDLE -> updateVoiceButton(idle = true)
                IVoiceInputCallback.STATE_RECORDING -> updateVoiceButton(recording = true)
                IVoiceInputCallback.STATE_PROCESSING -> updateVoiceButton(processing = true)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        voiceInputManager.updateInputConnection(currentInputConnection)

        // 采集上次语音输入后的用户编辑结果
        val event = feedbackCollector.checkAndCollect(currentInputConnection)
        if (event != null) {
            // 异步上传
            CoroutineScope(Dispatchers.IO).launch {
                feedbackUploader.uploadPending(feedbackRepository)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 兜底采集
        feedbackCollector.forceCollect(currentInputConnection)
    }
}
```

---

## 3. 验证清单

- [ ] AIDL 文件在正确的包路径下
- [ ] `buildFeatures { aidl = true }` 已开启
- [ ] `IVoiceInputPlugin.java` 和 `IVoiceInputCallback.java` 已生成
- [ ] 语音按钮可见且可点击
- [ ] 按住语音按钮 → 插件开始录音
- [ ] 释放按钮 → 文本成功上屏
- [ ] 状态变更正确反映到按钮 UI
- [ ] 用户编辑后再次点击语音 → 反馈事件成功上传到服务端
- [ ] 服务端 `server/data/feedback.jsonl` 包含上传的反馈事件

---

## 4. 常见问题

### Q: bindService 返回 false？

检查：
1. 插件 APK 是否已安装（`adb install app-debug.apk`）
2. 包名是否一致（`org.fcitx.fcitx5.android.voice`）
3. AndroidManifest intent-filter action 是否匹配（`org.fcitx.fcitx5.android.plugin.SERVICE`）

### Q: AIDL 编译报错 "couldn't find import for class"？

确保两个 AIDL 文件在完全一致的包路径下：
`app/src/main/aidl/org/fcitx/fcitx5/android/voice/`

### Q: commitText 无效果？

检查：
1. `InputConnection` 是否有效（在 `onStartInputView` 中更新）
2. 是否在 Binder 线程调用了 `commitText`（需切换到主线程）

### Q: 反馈事件未上传？

检查：
1. 服务端是否运行：`curl http://localhost:8080/health`
2. 模拟器网络：`baseUrl` 应为 `http://10.0.2.2:8080`
3. 真机 USB 代理：先执行 `adb reverse tcp:8080 tcp:8080`

---

## 5. 文件清单

以下文件需要从插件仓库拷贝到主应用：

| 源文件（插件仓库） | 作用 |
|---|---|
| `client/app/src/main/aidl/.../IVoiceInputPlugin.aidl` | 插件 AIDL 接口 |
| `client/app/src/main/aidl/.../IVoiceInputCallback.aidl` | 回调 AIDL 接口 |
| `reference/VoiceInputManager.kt` | 插件绑定管理 |
| `reference/VoiceFeedbackCollector.kt` | 反馈采集器 |
| `reference/FeedbackRepository.kt` | 反馈本地仓库 |
| `reference/FeedbackUploader.kt` | 反馈上传器 |
| `reference/TextDiffUtil.kt` | 文本差异工具 |

---

## 6. 服务端接口参考

### POST /v1/feedback

```json
POST /v1/feedback
Content-Type: application/json

{
  "client_event_id": "550e8400-e29b-41d4-a716-446655440000",
  "session_id": "session-uuid",
  "original_text": "那个 下午三点 有个会",
  "styled_text": "下午三点有个会。",
  "final_text": "您有一个新的会议邀请，安排在下午三点。",
  "style": "正式",
  "prompt": null,
  "duration_ms": 1240,
  "timestamp": 1718500000000
}
```

Response: `{"status": "ok", "event_id": "...", "error": null}`
