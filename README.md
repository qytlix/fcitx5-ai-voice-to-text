# Fcitx5 AI Voice-to-Text Core

用户语音输入 → ASR 转文字 → AI 风格化整理 → 输入法上屏。

例如讲话时想到什么说什么，AI 将其转化为更有逻辑、更正式的语言。

本项目是**核心协议与逻辑层**，外部可封装为 Fcitx5 Android / Linux / Windows 等平台的输入法插件。

## 架构

```mermaid
flowchart LR
    User["🎤 用户语音"] --> Fcitx5["Fcitx5 Android 主应用"]
    Fcitx5 -->|AIDL IPC 触发| Plugin["语音插件 (本仓库 client)"]
    Plugin -->|HTTP / JSON| Server["Server (Python / FastAPI)"]
    Server --> ASR["ASR API (讯飞等)"]
    ASR --> Server
    Server --> LLM["LLM API 风格化"]
    LLM --> Server
    Server --> Plugin
    Plugin -->|AIDL 回调| Fcitx5
    Fcitx5 -->|InputConnection| IM["⌨️ 目标应用上屏"]
```

## 集成路径选择

已确定采用 **A 路径：Fcitx5 Android 插件**。

| 方案 | 说明 | 选择原因 |
|---|---|---|
| **A. Fcitx5 插件（AIDL IPC）** | 作为独立 APK 插件，通过 AIDL 与 Fcitx5 主应用通信 | ✅ 主应用持有 `InputConnection`，可直接上屏；便于收集用户修改反馈，形成数据闭环 |
| B. AnySoftKeyboard | 通过系统 `RecognizerIntent` 调用外部语音 IME | ❌ 无法控制上屏流程，难以获取用户后续修改 |
| C. 独立 App + 剪贴板 | 录音后写入剪贴板，用户手动粘贴 | ❌ 体验割裂，无反馈闭环 |

插件侧代码（AIDL 接口、`VoiceInputPluginService`、录音与网络核心）已完成；后续主要工作在 **Fcitx5 Android 主应用侧** 集成语音按钮与回调处理。

## 技术选型

| 层 | 语言/框架 | 说明 |
|---|---|---|
| **Client** | **Kotlin** | Fcitx5 Android 插件原生语言，直接调用 Android AudioRecord 录音 & Fcitx5 输入接口 |
| **Server** | **Python / FastAPI** | 原型阶段开发效率高，ASR + LLM 外部 API 封装方便；后续可迁移至 Go/Rust |
| **通信** | HTTP REST + JSON | 原型阶段最简方案，后续可升级为 gRPC / WebSocket |

## 项目结构

```
fcitx5-ai-voice-to-text-core/
├── client/                  # Android 客户端（Fcitx5 插件）
│   ├── app/                 # Android 应用模块（插件 APK）
│   │   ├── src/main/
│   │   │   ├── aidl/        # AIDL 接口：IVoiceInputPlugin / IVoiceInputCallback
│   │   │   ├── java/        # bridge / data / ui
│   │   │   └── res/         # 布局、字符串、Manifest
│   │   └── build.gradle.kts
│   ├── core/                # 纯 Kotlin 核心库（可单元测试）
│   │   └── src/main/kotlin/...
│   ├── PROGRESS.md          # 开发进度与路线规划
│   └── ARCHITECTURE.md      # 客户端架构设计文档
├── server/                  # 服务端（Python / FastAPI）
│   ├── app.py               # FastAPI 入口 / API 路由
│   ├── asr.py               # ASR 转文字模块（当前固定模拟）
│   ├── stylize.py           # 风格化模块（当前固定模拟）
│   ├── models.py            # Pydantic 数据模型
│   └── environment.yml      # Conda 环境配置
├── docs/
│   └── protocol.md          # 通信协议说明
└── README.md
```

## 通信协议

### 请求（Client → Server）

```json
{
  "audio": "<base64 编码的音频数据 / PCM / WAV>",
  "prompt": "请把这段话改得更正式",
  "sample": "您有一个新的会议邀请安排在下午三点……",
  "style": "正式"
}
```

### 响应（Server → Client）

```json
{
  "text": "您有一个新的会议邀请，安排在下午三点……",
  "original_text": "那个…下午三点有个会",
  "duration_ms": 1240,
  "error": null
}
```

## 风格系统

| 风格 | 说明 |
|---|---|
| `正式` | 口语 → 书面语，去除语气词 |
| `精简` | 去除冗余，保留核心信息 |
| `礼貌` | 调整语气，添加敬语 |
| `翻译_英文` | 翻译为英文并优化表达 |
| `自定义` | 用户通过 `prompt` 参数自由定义 |

## Quick Start（原型阶段）

### 1. 创建并激活 Conda 环境

```bash
cd server
conda env create -f environment.yml
conda activate v2t
```

### 2. 启动服务端

```bash
# 确保 conda 环境已激活 (v2t)
uvicorn server.app:app --reload --host 0.0.0.0 --port 8080
```

> `--reload` 仅在开发阶段使用，修改代码后自动重启。

### 3. 构建并安装插件 APK

```bash
cd client
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. 测试接口

```bash
# 健康检查
curl http://localhost:8080/health

# 转录测试
curl -X POST http://localhost:8080/v1/transcribe \
  -H "Content-Type: application/json" \
  -d '{"audio": "AAECAw==", "style": "精简"}'

# 返回示例：
# {"text":"[录音时长: 0ms] 好的。","original_text":"好的","duration_ms":0,"error":null}
```

## 后续规划（Roadmap）

- [x] **Phase 1** — 服务端 ASR + 风格化逻辑可独立运行
- [x] **Phase 2** — Kotlin Client 核心：录音 → 发送 → 接收 → 上屏
- [x] **Phase 3** — 插件侧 AIDL 接口与 `VoiceInputPluginService` 实现
- [ ] **Phase 4** — Fcitx5 Android 主应用集成：键盘按钮、bindService、回调上屏
- [ ] **Phase 5** — 用户修改反馈闭环：采集上屏后编辑 → 优化风格化 prompt
- [ ] **Phase 6** — Fcitx5 Linux / Windows 插件封装
- [ ] **Phase 7** — 自定义提示词模板管理 UI
- [ ] **Phase 8** — 离线 ASR / 端侧小模型支持

## 说明

- 本项目为**核心协议与逻辑层** + **Fcitx5 Android 插件 APK**。
- 当前插件侧代码可直接编译安装，但尚需 Fcitx5 Android 主应用配合才能在上屏流程中生效。
- 各平台插件（Linux / Windows）作为独立仓库，通过本项目定义的 Client API 进行对接。