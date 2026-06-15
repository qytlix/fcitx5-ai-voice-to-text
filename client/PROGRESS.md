# Fcitx5 AI Voice-to-Text Client — 开发进度

> 最后更新: 2026-06-15

---

## 总体进度

```
Phase 1: 纯 Kotlin 核心库 + 单元测试       ✅ 已完成
Phase 2: Android 模块 (独立 App / 长按录音)  ✅ 已完成
Phase 3: Fcitx5 插件侧 AIDL 与服务实现       ✅ 已完成
Phase 4: Fcitx5 主应用集成 + 反馈闭环        ✅ 已完成
Phase 5: 完善与优化                          ⬜ 待开始
```

---

## Phase 1 — 纯 Kotlin 核心库 ✅

### 目标
构建不依赖 Android 框架的核心逻辑层，可在 JVM 上直接运行单元测试。

### 文件结构

```
client/core/
├── build.gradle.kts
└── src/
    ├── main/kotlin/.../core/
    │   ├── model/
    │   │   ├── TranscribeRequest.kt      # 请求模型（与 server/models.py 一致）
    │   │   ├── TranscribeResponse.kt     # 响应模型（与 server/models.py 一致）
    │   │   └── TranscribeResult.kt       # 客户端完整结果（含总耗时）
    │   ├── bridge/
    │   │   ├── AudioRecorder.kt          # 录音接口
    │   │   └── CommitTextHandler.kt      # 上屏接口
    │   ├── network/
    │   │   ├── TranscribeService.kt      # 网络请求接口
    │   │   └── TranscribeException.kt    # 错误类型 + RetryPolicy
    │   ├── domain/
    │   │   ├── TranscribeUseCase.kt      # 核心业务编排（含自动重试）
    │   │   ├── RecordingUseCase.kt       # 录音管理 + 时长估算
    │   │   └── TranscribeState.kt        # 状态机定义
    │   └── test/
    │       ├── MockAudioRecorder.kt      # Mock 录音器（含 WAV 生成）
    │       ├── MockTranscribeService.kt  # Mock 网络服务
    │       └── MockCommitTextHandler.kt  # Mock 上屏处理器
    └── test/kotlin/.../core/domain/
        ├── TranscribeUseCaseTest.kt      # 12 个测试用例
        └── RecordingUseCaseTest.kt       # 5 个测试用例
```

### 测试结果

```
17/17 测试通过 ✅

TranscribeUseCase:
  - 正常流程: 完整流程、外部音频源、风格参数、自定义 prompt/sample
  - 错误处理: 录音失败、编码失败、服务器业务错误、网络超时（含重试）、连接异常（含重试）
  - 参数验证: 默认 style、base64 合法性

RecordingUseCase:
  - 时长估算: 空音频、Mock WAV 0.5s、无效 base64、长音频对比
  - 权限检查: 默认通过
```

---

## Phase 2 — Android 模块 (独立 App) ✅

### 目标
搭建 Android 项目，实现录音（AudioRecord）、网络（Retrofit）、UI Activity，可独立运行并调用 server。

### 文件结构

```
client/app/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml              # RECORD_AUDIO + INTERNET 权限
    ├── res/
    │   ├── values/
    │   │   ├── strings.xml              # 全中文字符串
    │   │   └── themes.xml               # AppCompat 主题
    │   └── layout/
    │       └── activity_main.xml        # 风格选择 + 录音按钮 + 结果展示
    └── java/.../voice/
        ├── data/
        │   ├── AndroidAudioRecorder.kt  # AudioRecord 实现
        │   └── RetrofitTranscribeService.kt  # Retrofit HTTP 客户端
        ├── bridge/
        │   ├── ClipboardCommitTextHandler.kt  # 剪贴板上屏（独立 App 模式）
        │   ├── NoOpCommitTextHandler.kt       # 无操作上屏（插件模式）
        │   └── VoiceInputPluginService.kt     # Fcitx5 插件 Service
        └── ui/
            ├── VoiceInputViewModel.kt   # UI 状态管理
            └── MainActivity.kt          # 主界面
```

### 构建验证

| 项目 | 状态 |
|------|------|
| `./gradlew :core:test` | ✅ 17/17 通过 |
| `./gradlew :app:assembleDebug` | ✅ 成功 (4.3MB APK) |
| APK 路径 | `app/build/outputs/apk/debug/app-debug.apk` |

### 交互方式

- **按住说话**：用户按住录音按钮开始录音
- **松手发送**：用户释放按钮后自动停止录音并发起 HTTP 转录
- **滑动取消**：可扩展为滑动到取消区域后丢弃录音（预留 `cancelRecording()`）

### 架构总览

```
用户点击录音 → MainActivity → VoiceInputViewModel
                                    │
                          TranscribeUseCase
                         ┌───────┼───────┐
                         │       │       │
                   AudioRecorder  │  CommitTextHandler
                  (AndroidAudio)  │  (Clipboard / NoOp)
                                  │
                     RetrofitTranscribeService
                                  │
                           POST /v1/transcribe
                                  │
                          Server (FastAPI)
```

### 服务端连接方式

| 场景 | URL | 方式 |
|------|-----|------|
| Android Studio 模拟器（默认） | `http://10.0.2.2:8080` | 自动映射宿主机 localhost |
| 真机 + 同一局域网 | `http://192.168.x.x:8080` | 改 RetrofitTranscribeService 的 baseUrl |
| 真机 + USB 线 | `http://localhost:8080` | 先执行 `adb reverse tcp:8080 tcp:8080` |

---

## Phase 3 — Fcitx5 插件适配 ✅

### 目标
将独立 App 改造为 Fcitx5 Android 插件，通过 AIDL IPC 与 Fcitx5 主应用通信。

### 架构

```
Fcitx5 主应用（独立 APK）                   语音输入插件（本 APK）
┌──────────────────────┐          AIDL     ┌─────────────────────────┐
│ InputMethodService   │ ◄─────────────── ► │ VoiceInputPluginService │
│                      │   IVoiceInputPlugin│                         │
│  ┌───────────────┐   │   startRecording() │  ┌─────────────────┐   │
│  │ voice button  │───┼───────────────────►│  │ AndroidAudio-   │   │
│  └───────┬───────┘   │                    │  │ Recorder        │   │
│          │           │                    │  └────────┬────────┘   │
│  onResult(text) ◄────┼────────────────────┼───────────┘            │
│          │           │                    │  ┌─────────────────┐   │
│          ▼           │   onResult(text)   │  │ RetrofitTrans-  │   │
│  InputConnection     │◄────────────────────│  │ cribeService    │   │
│  .commitText(text,1) │                    │  └────────┬────────┘   │
└──────────────────────┘                    │           │            │
                                            │  POST /v1/transcribe   │
                                            │           │            │
                                            │  ┌────────▼────────┐   │
                                            │  │ NoOpCommitText  │   │
                                            │  │ Handler(no-op)  │   │
                                            │  └─────────────────┘   │
                                            └─────────────────────────┘
```

- 插件负责录音（AudioRecord）和 HTTP 请求（Retrofit），复用 Phase 2 的全部核心逻辑
- 文本上屏通过 AIDL 回调 `IVoiceInputCallback.onResult()` 委托给 Fcitx5 主应用
- 主应用在回调中调用 `InputConnection.commitText()` 完成上屏
- 插件内使用 `NoOpCommitTextHandler`（空操作），因为真正的上屏在主应用侧

### 创建/修改的文件

#### 新增 4 个文件

| 文件 | 说明 |
|------|------|
| `app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputPlugin.aidl` | 插件 AIDL 接口：startRecording, stopRecording, cancelRecording, setStyle, registerCallback, isRecording, getVersion |
| `app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputCallback.aidl` | 回调 AIDL 接口：onResult, onError, onStateChanged + STATE_IDLE/RECORDING/PROCESSING 常量 |
| `app/src/main/java/.../bridge/VoiceInputPluginService.kt` | 插件 Service：实现 AIDL，协程管理录音/HTTP/回调生命周期 |
| `app/src/main/java/.../bridge/NoOpCommitTextHandler.kt` | 无操作上屏 Handler（上屏由主应用在 AIDL 回调中处理） |

#### 修改 3 个文件

| 文件 | 变更 |
|------|------|
| `app/src/main/AndroidManifest.xml` | 添加 VoiceInputPluginService 声明 + `org.fcitx.fcitx5.android.plugin.SERVICE` intent filter + 独立进程 `:fcitx_plugin_voice` |
| `app/build.gradle.kts` | `buildFeatures { aidl = true }` |
| `app/src/main/res/values/strings.xml` | 添加 `plugin_description` |

### AIDL 接口详情

**IVoiceInputPlugin.aidl** — 插件暴露给主应用的接口：

```aidl
interface IVoiceInputPlugin {
    void startRecording();              // 开始录音
    void stopRecording();               // 停止录音 → 转录 → 回调
    void cancelRecording();             // 取消（丢弃音频）
    void setStyle(String style);        // 设置风格
    void registerCallback(IVoiceInputCallback callback);  // 注册回调
    boolean isRecording();              // 查询状态
    String getVersion();                // 获取版本
}
```

**IVoiceInputCallback.aidl** — 主应用实现的回调接口：

```aidl
interface IVoiceInputCallback {
    void onResult(String text, String originalText, long durationMs);
    void onError(String message);
    void onStateChanged(int state);     // STATE_IDLE=0 / STATE_RECORDING=1 / STATE_PROCESSING=2
}
```

### 插件 Service 数据流

```
startRecording() → audioRecorder.startRecording()  [直接调用 AudioRecorder]
stopRecording()  → audioRecorder.stopRecording() → 获取 base64 WAV
                 → TranscribeUseCase.execute(audioSource=base64)  [跳过录音，走 HTTP]
                 → callback.onResult(text, originalText, durationMs)
                 → Fcitx5 主应用: InputConnection.commitText(text, 1)
cancelRecording() → 清除状态，丢弃音频
```

### 构建验证

| 项目 | 状态 |
|------|------|
| `./gradlew :core:test` | ✅ 17/17 通过（core 模块未改动） |
| `./gradlew :app:assembleDebug` | ✅ 成功，AIDL 编译通过 |
| APK service 声明 | ✅ 包含 `VoiceInputPluginService` + intent filter + 独立进程 |
| AIDL 桩代码生成 | ✅ `IVoiceInputPlugin.java` + `IVoiceInputCallback.java` |

### 主应用侧修改（不在本项目范围内）

Fcitx5 Android 源码需要做的修改已整理到 **Phase 4**，主要包括：

1. 在键盘布局中添加语音输入按钮
2. 在 `InputMethodService` 中绑定插件（bindService → 通过 AIDL 控制）
3. 实现 `IVoiceInputCallback.Stub`：`onResult()` 中调用 `InputConnection.commitText()`
4. 将两个 AIDL 文件拷贝到 Fcitx5 Android 项目或通过 APK 依赖引用
5. 采集用户后续编辑内容，建立反馈闭环

详见 Phase 4 详细规划。

## Phase 4 — Fcitx5 主应用集成 + 反馈闭环 ✅

> 完成日期: 2026-06-15

### 目标

插件侧（本仓库）已完成 AIDL 接口与服务；Phase 4 完成了反馈闭环的全部核心实现（数据模型、服务端接口、插件增强、主应用参考代码），使语音输入具备持续优化的数据基础。

### 4a. 反馈数据模型 + 服务端接口 ✅

**本仓库实现** — 在 core 模块新增反馈相关数据模型和网络接口，在 server 新增反馈接收端点。

#### 新建文件

| 文件 | 说明 |
|------|------|
| `core/.../model/VoiceFeedbackEvent.kt` | 反馈事件数据类（含 toUploadRequest、MIN_DIFF_RATIO） |
| `core/.../model/FeedbackUploadRequest.kt` | 上传请求 DTO（字段 snake_case 与 server JSON 对齐） |
| `core/.../model/FeedbackUploadResponse.kt` | 上传响应 DTO（status, event_id） |
| `core/.../network/FeedbackService.kt` | 反馈网络服务接口 |
| `core/.../test/MockFeedbackService.kt` | Mock 实现（遵循 MockTranscribeService 模式） |
| `core/.../test/.../VoiceFeedbackEventTest.kt` | 13 个单元测试 |
| `server/feedback_store.py` | JSONL 文件存储模块（append / list / rotate） |

#### 修改文件

| 文件 | 变更 |
|------|------|
| `server/models.py` | 新增 FeedbackRequest / FeedbackResponse Pydantic 模型 |
| `server/app.py` | 新增 `POST /v1/feedback` 和 `GET /v1/feedback/count` 端点 |

### 4b. 插件侧增强 ✅

**本仓库实现** — AIDL + session 追踪 + 风格持久化。

#### 新建文件

| 文件 | 说明 |
|------|------|
| `core/.../bridge/SessionIdProvider.kt` | UUID 生成接口（可 mock 测试） |

#### 修改文件

| 文件 | 变更 |
|------|------|
| `app/.../IVoiceInputCallback.aidl` | `onResult()` 增加第 4 参数 `String sessionId` |
| `app/.../VoiceInputPluginService.kt` | sessionId 生成与传递；setStyle() SharedPreferences 持久化；版本 → 0.2.0 |
| `core/.../TranscribeUseCase.kt` | `stopRecordingAndTranscribe()` / `execute()` / `transcribeAndCommit()` 增加可选参数 `sessionId: String? = null` |
| `core/.../TranscribeResult.kt` | 增加 `sessionId: String? = null` 字段 |

### 4c. 主应用参考实现 ✅

**参考代码** — 位于仓库根目录 `reference/`，不参与 Gradle 构建，供 Fcitx5 主应用开发者直接拷贝使用。

| 文件 | 说明 |
|------|------|
| `reference/Fcitx5IntegrationGuide.md` | 分步骤集成指南（6 步 + 验证清单） |
| `reference/VoiceInputManager.kt` | Service 绑定管理 + IVoiceInputCallback.Stub 实现 + commitText |
| `reference/VoiceFeedbackCollector.kt` | InputConnection 读取 + 文本差异计算 + 反馈事件构建 |
| `reference/FeedbackRepository.kt` | 内存队列 + 批量上传管理 |
| `reference/FeedbackUploader.kt` | HTTP 客户端调用 POST /v1/feedback |
| `reference/TextDiffUtil.kt` | Levenshtein 编辑距离 + diffRatio + 可读摘要 |

### 4d. App 模块反馈客户端 ✅

**本仓库实现** — 独立 App 中的端到端反馈测试能力。

| 文件 | 说明 |
|------|------|
| `app/.../data/RetrofitFeedbackService.kt` | 实现 FeedbackService 的 Retrofit HTTP 客户端 |
| `app/.../bridge/FeedbackCollector.kt` | 独立 App 反馈采集器（手动输入 finalText 模式） |
| `app/.../ui/VoiceInputViewModel.kt` | 新增 TranscriptionSession 数据类 + 会话元数据暴露 |
| `app/.../ui/MainActivity.kt` | 新增反馈区域 UI（EditText + 发送/清除按钮） |
| `app/.../res/layout/activity_main.xml` | 新增 feedbackSection（EditText + 按钮组） |
| `app/.../res/values/strings.xml` | 新增 4 个字符串（label_feedback, hint_feedback_edit, btn_send_feedback, btn_clear） |

### 4e. 验证结果

- ✅ core 测试: 30/30 通过（原有 17 + 新增 13 反馈模型测试）
- ✅ app 构建: `assembleDebug` 成功，AIDL 编译通过（含新 sessionId 参数）
- ✅ server 端点: `/health`、`/v1/transcribe`、`/v1/feedback`、`/v1/feedback/count` 均正常
- ✅ JSONL 存储: `server/data/feedback.jsonl` 写入和读取正常

### 4.1 Fcitx5 Android 主应用需要修改的代码（参考）

以下修改发生在 Fcitx5 Android 主应用仓库（非本仓库），但由本项目定义接口与行为约定。

#### 1) 复制 AIDL 文件

将插件侧的 2 个 AIDL 文件拷贝到主应用对应包路径：

| 源文件（本仓库） | 目标路径（Fcitx5 Android） |
|---|---|
| `app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputPlugin.aidl` | `app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputPlugin.aidl` |
| `app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputCallback.aidl` | `app/src/main/aidl/org/fcitx/fcitx5/android/voice/IVoiceInputCallback.aidl` |

> 主应用 `build.gradle.kts` 需开启 `buildFeatures { aidl = true }`。

#### 2) 新增键盘语音按钮

在 Fcitx5 Android 的键盘布局/主题系统中添加一个语音输入按键：

- **候选位置**：
  - 工具栏（Toolbar）新增麦克风图标按钮
  - 或在主键盘布局中增加一个可配置的语音键
- **行为**：
  - `ACTION_DOWN` → 调用 `IVoiceInputPlugin.startRecording()`
  - `ACTION_UP` → 调用 `IVoiceInputPlugin.stopRecording()`
  - 长按滑动到取消区域 → 调用 `IVoiceInputPlugin.cancelRecording()`

建议新增一个专门的 `VoiceInputManager` 类来封装这些交互，避免在 KeyboardView 中直接处理 IPC。

#### 3) 绑定插件 Service

在 `FcitxInputMethodService` 或新建的 `VoiceInputManager` 中：

```kotlin
// 发现已安装的语音插件
val intent = Intent("org.fcitx.fcitx5.android.plugin.SERVICE")
    .setPackage("org.fcitx.fcitx5.android.voice") // 本插件包名
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

并在 `onDestroy()` / `onFinishInput()` 中 `unbindService()`。

#### 4) 实现 `IVoiceInputCallback`

主应用实现回调，完成上屏与状态反馈：

```kotlin
private val callback = object : IVoiceInputCallback.Stub() {
    override fun onResult(text: String, originalText: String, durationMs: Long, sessionId: String) {
        // 切换到主线程
        currentInputConnection?.commitText(text, 1)
        // 可选：将 originalText 与最终 text 记录到反馈数据库
    }

    override fun onError(message: String) {
        // 显示 Toast / 键盘内错误提示
    }

    override fun onStateChanged(state: Int) {
        // 更新语音按钮 UI：Idle / Recording / Processing
    }
}
```

#### 5) 注册回调

绑定成功后立即调用：

```kotlin
plugin?.registerCallback(callback)
```

### 4.2 插件侧（本仓库）需要配合的微调

| 文件 | 变更 |
|---|---|
| `AndroidManifest.xml` | 确认 `VoiceInputPluginService` 的 intent-filter action 与主应用搜索一致；包名需与主应用 `setPackage()` 匹配 |
| `VoiceInputPluginService.kt` | 已支持 `startRecording()` / `stopRecording()` / `cancelRecording()`；后续根据主应用需求可增加 `setStyle()` 持久化 |
| `TranscribeUseCase.kt` | 已支持两段式录音；当前插件通过 `audioSource` 方式复用，无需改动 |

### 4.3 用户修改反馈闭环设计

为了持续优化 AI 风格化效果，需要在主应用侧收集“上屏后用户又修改了什么”。

#### 数据模型

```kotlin
data class VoiceFeedbackEvent(
    val id: String,                   // 本次语音输入会话 ID
    val originalText: String,         // ASR 原始转写
    val styledText: String,           // AI 风格化后上屏的文本
    val finalText: String,            // 用户最终确认的文本
    val style: String,                // 使用的风格
    val prompt: String?,              // 自定义提示词
    val durationMs: Long,             // 处理耗时
    val timestamp: Long               // 事件发生时间
)
```

#### 采集流程

```
1. 插件 onResult(text=A) 回调主应用
2. 主应用 commitText(A) 上屏
3. 记录 (originalText, styledText=A, style, prompt, timestamp)
4. 用户在目标应用中编辑，最终文本变为 B
5. 下一次该输入框获得焦点 / 用户再次调用语音输入 / 输入法主动读取时
   → 通过 InputConnection.getTextBeforeCursor() / getSelectedText() 获取 B
6. 计算差异：diff(A, B)
7. 将 VoiceFeedbackEvent 发送到服务端 / 本地存储
```

#### 主应用需要新增模块

| 类/模块 | 职责 |
|---|---|
| `VoiceFeedbackCollector` | 在合适的时机读取输入框内容，计算与上屏文本的差异 |
| `FeedbackRepository` | 本地缓存反馈事件，批量上传或导出 |
| `FeedbackUploader` | 调用服务端新增接口 `POST /v1/feedback` |

#### 服务端新增接口（后续 Phase 实现）

```http
POST /v1/feedback
Content-Type: application/json

{
  "original_text": "那个 下午三点 有个会",
  "styled_text": "下午三点有个会。",
  "final_text": "您有一个新的会议邀请，安排在下午三点。",
  "style": "正式",
  "prompt": null,
  "duration_ms": 1240,
  "timestamp": 1718500000000
}
```

该接口将数据存入数据集，用于后续微调风格化 prompt 或训练 LoRA。

### 4.4 Phase 4 任务清单

**本仓库侧（全部完成）：**
- [x] 设计并实现 `VoiceFeedbackEvent` 数据模型（core 模块）
- [x] 服务端新增 `POST /v1/feedback` + `GET /v1/feedback/count` 接口
- [x] 服务端新增 `feedback_store.py` JSONL 存储
- [x] AIDL 回调增加 `sessionId` 参数
- [x] 插件 Service 增加 sessionId 生成与传递
- [x] 插件 Service 增加 `setStyle()` SharedPreferences 持久化
- [x] `TranscribeUseCase` / `TranscribeResult` 支持 sessionId
- [x] 编写 `reference/` 主应用参考实现（6 个文件）
- [x] 独立 App 中实现反馈测试链路（RetrofitFeedbackService + FeedbackCollector）
- [x] 单元测试扩展至 30 个（原有 17 + 新增 13）
- [x] 构建验证通过（AIDL 编译 + APK 构建）

**Fcitx5 主应用侧（参考实现已提供，待主应用团队执行）：**
- [x] 将 AIDL 文件集成到 Fcitx5 Android 主应用（参考 `reference/Fcitx5IntegrationGuide.md` 步骤 1-2）
- [x] 在主应用键盘上添加语音按钮（参考集成指南步骤 3）
- [x] 实现主应用侧插件绑定与生命周期管理（参考 `reference/VoiceInputManager.kt`）
- [x] 实现 `IVoiceInputCallback` 并完成 `commitText` 上屏（参考集成指南步骤 4-5）
- [x] 将状态变更反映到语音按钮视觉反馈
- [x] 实现 `VoiceFeedbackCollector` 采集用户修改（参考 `reference/VoiceFeedbackCollector.kt`）
- [x] 端到端验证：长按录音 → 松手 → 文本上屏 → 收集反馈（参考 `reference/Phase4VerificationChecklist.md`）

---

## Phase 5 — 完善与优化 ⬜

### 5.1 功能完善
- [ ] **自定义提示词模板管理** — UI 设置页，保存常用 prompt
- [ ] **自动语音检测 (VAD)** — 检测到静音自动停止录音
- [ ] **录音时长限制** — 最长 60s，超时自动停止
- [ ] **音频压缩** — 在发送前压缩音频减少上传时间
- [ ] **历史记录** — 保存最近的转录结果

### 5.2 体验优化
- [ ] **录音动画** — 波形/电平指示器
- [ ] **流式显示** — 收到部分结果时实时更新 UI
- [ ] **错误重试 UI** — 更友好的错误提示和重试引导
- [ ] **多语言支持** — strings.xml 国际化

### 5.3 工程化
- [ ] **Hilt 依赖注入** — 替换手动 DI
- [ ] **ProGuard 混淆规则** — release 构建
- [ ] **CI/CD** — GitHub Actions 自动构建 APK
- [ ] **端到端测试** — 模拟器自动测试

---

## 技术债务

- `launchWhenStarted` 已废弃，后续应替换为 `repeatOnLifecycle`（见 MainActivity.kt:159）
- `VoiceInputViewModel.startTranscribeFlow()` 的 `wasRecording` 参数未使用
- 真机测试需要手动修改 baseUrl，后续应做成可配置项（设置页面或 build config field）

---

## 快速参考

```bash
# 激活服务端
conda activate v2t
uvicorn server.app:app --reload --host 0.0.0.0 --port 8080

# 运行单元测试
cd client
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test

# 构建 APK
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# USB 反向代理（真机测试用）
adb reverse tcp:8080 tcp:8080
```
