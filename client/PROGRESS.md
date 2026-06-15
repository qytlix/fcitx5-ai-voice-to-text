# Fcitx5 AI Voice-to-Text Client — 开发进度

> 最后更新: 2026-06-14

---

## 总体进度

```
Phase 1: 纯 Kotlin 核心库 + 单元测试   ✅ 已完成
Phase 2: Android 模块 (独立 App)       ✅ 已完成
Phase 3: Fcitx5 插件适配               ✅ 已完成
Phase 4: 完善与优化                    ⬜ 待开始
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

Fcitx5 Android 源码需要做的修改（记录供参考）：

1. 在键盘布局中添加语音输入按钮
2. 在 `InputMethodService` 中绑定插件（bindService → 通过 AIDL 控制）
3. 实现 `IVoiceInputCallback.Stub`：`onResult()` 中调用 `InputConnection.commitText()`
4. 将两个 AIDL 文件拷贝到 Fcitx5 Android 项目或通过 APK 依赖引用

---

## Phase 4 — 完善与优化 ⬜

---

## Phase 4 — 完善与优化 ⬜

### 4.1 功能完善
- [ ] **自定义提示词模板管理** — UI 设置页，保存常用 prompt
- [ ] **自动语音检测 (VAD)** — 检测到静音自动停止录音
- [ ] **录音时长限制** — 最长 60s，超时自动停止
- [ ] **音频压缩** — 在发送前压缩音频减少上传时间
- [ ] **历史记录** — 保存最近的转录结果

### 4.2 体验优化
- [ ] **录音动画** — 波形/电平指示器
- [ ] **流式显示** — 收到部分结果时实时更新 UI
- [ ] **错误重试 UI** — 更友好的错误提示和重试引导
- [ ] **多语言支持** — strings.xml 国际化

### 4.3 工程化
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
