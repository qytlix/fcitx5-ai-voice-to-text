# Fcitx5 AI Voice-to-Text Client — 开发进度

> 最后更新: 2026-06-14

---

## 总体进度

```
Phase 1: 纯 Kotlin 核心库 + 单元测试   ✅ 已完成
Phase 2: Android 模块 (独立 App)       ✅ 已完成
Phase 3: Fcitx5 插件适配               ⬜ 待开始
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
        │   └── ClipboardCommitTextHandler.kt  # 剪贴板上屏
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
                  (AndroidAudio)  │  (Clipboard)
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

## Phase 3 — Fcitx5 插件适配 ⬜

### 目标
将独立 App 改造为 Fcitx5 Android 插件，通过插件系统与 Fcitx5 主应用通信。

### 需要完成的工作

#### 3.1 理解 Fcitx5 Android 插件机制
- 参考 `clipboard-filter` 插件的实现模式
- 插件通过 `FcitxPluginServices.PLUGIN_SERVICE_ACTION` 注册
- 主应用启动时自动发现并绑定已安装的插件
- 插件作为独立 APK，通过 AIDL IPC 与主应用通信

#### 3.2 创建 AIDL 接口
```
client/app/src/main/aidl/.../voice/
├── IVoiceInputPlugin.aidl       # 插件 → 主应用：启动/停止录音
└── IVoiceInputCallback.aidl     # 主应用 → 插件：状态/结果回调
```

#### 3.3 实现 Fcitx5 插件 Service
- `VoiceInputPluginService` — 继承 Service，注册为 Fcitx5 插件
- 实现 AIDL 接口，处理主应用的语音输入请求
- 复用 Phase 2 的 `TranscribeUseCase` 等核心逻辑

#### 3.4 实现 Fcitx5 上屏
- `Fcitx5CommitTextHandler` — 替换 `ClipboardCommitTextHandler`
- 通过 `InputConnection.commitText()` 实现真正的输入法上屏
- 支持 `setComposingText()` 实时预览

#### 3.5 主应用端修改（Fcitx5 Android 源码）
- 在键盘布局中添加"语音输入"按钮
- 点击按钮通过 AIDL 调用插件
- 接收回调更新 UI 状态

#### 3.6 关键文件
| 文件 | 说明 |
|------|------|
| `app/src/main/aidl/IVoiceInputPlugin.aidl` | 插件 AIDL 接口 |
| `app/src/main/aidl/IVoiceInputCallback.aidl` | 回调 AIDL 接口 |
| `app/src/main/java/.../bridge/VoiceInputPluginService.kt` | 插件 Service |
| `app/src/main/java/.../bridge/Fcitx5CommitTextHandler.kt` | Fcitx5 上屏实现 |
| `app/src/main/AndroidManifest.xml` | 添加插件 service 声明 |

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
