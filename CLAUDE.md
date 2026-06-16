# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fcitx5 AI Voice-to-Text Core — 语音输入 → ASR 转文字 → AI 风格化整理 → 输入法上屏。

本项目是核心协议与逻辑层，外部封装为 Fcitx5 各平台插件。当前处于**原型阶段**，ASR 和风格化均为固定模拟实现，后续接入真实 API。

## Architecture

分层设计，依赖单向（app → pipeline → asr/stylize → config/models）：

```
用户 → [Client: Kotlin / Android] → HTTP/JSON → [Server: Python / FastAPI]
                                                  app.py      (路由层，只接线)
                                                    └→ pipeline.py  (编排：校验→ASR→风格化→响应+错误处理)
                                                         ├── asr.py      (ASRProvider 抽象 + Mock + 工厂)
                                                         └── stylize.py  (Stylizer 抽象 + 规则实现 + 工厂)
                                                  config.py   (读取 .env 配置)
                                                  models.py   (Pydantic 模型 + Style 枚举)
```

- **Client**: Kotlin（Android），对接 Fcitx5 输入法接口
- **Server**: Python / FastAPI，提供 REST API
- **通信**: HTTP REST + JSON

## Server 模块职责

| 模块 | 职责 |
|---|---|
| `app.py` | FastAPI 入口，/health GET + /v1/transcribe POST + /v1/feedback POST + /v1/feedback/count GET，仅路由与中间件 |
| `pipeline.py` | 编排一次请求的完整流程，并把可预期错误转成响应 error 字段 |
| `config.py` | 从 .env / 环境变量读取配置（provider、key、大小上限、CORS） |
| `models.py` | TranscribeRequest / TranscribeResponse / FeedbackRequest / FeedbackResponse Pydantic 模型 + Style 风格枚举 |
| `asr.py` | 音频 → 文字：ASRProvider 抽象基类 + MockASRProvider + get_asr_provider 工厂 |
| `stylize.py` | 文字 → 风格化：Stylizer 抽象基类 + RuleBasedStylizer + get_stylizer 工厂（正式/精简/礼貌/翻译_英文/自定义） |
| `feedback_store.py` | JSONL 文件存储（server/data/feedback.jsonl，append / list / rotate） |

## Environment

- **Android SDK**: `~/Android/Sdk` (Windows: `%APPDATA%/Local/Android/Sdk`)
- **Java**: Java 17 LTS (Adoptium/Eclipse Temurin)
- **Python**: 3.8+, recommend using venv
- **Project Path**: Current working directory

## Commands

```bash
# 激活环境
.\.venv\Scripts\Activate.ps1

# 启动服务端（开发模式，带热重载）
uvicorn server.app:app --reload --host 0.0.0.0 --port 8080

# 构建 Android APK
cd client
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug

# 运行单元测试
cd client
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test

# 安装 APK 到设备
adb install client/app/build/outputs/apk/debug/app-debug.apk

# USB 反向代理（真机测试用）
adb reverse tcp:8080 tcp:8080

# 测试健康检查
curl http://localhost:8080/health

# 测试转录接口
curl -X POST http://localhost:8080/v1/transcribe \
  -H "Content-Type: application/json" \
  -d '{"audio": "AAECAw==", "style": "正式"}'

# 测试反馈接口
curl -X POST http://localhost:8080/v1/feedback \
  -H "Content-Type: application/json" \
  -d '{"original_text":"test","styled_text":"test","final_text":"edited","style":"正式","duration_ms":100}'

# 查看反馈计数
curl http://localhost:8080/v1/feedback/count
```


## API 接口

### POST /v1/transcribe

Request:
```json
{
  "audio": "<base64_audio>",
  "prompt": "(可选) 自定义提示词",
  "sample": "(可选) 样例文本",
  "style": "正式 | 精简 | 礼貌 | 翻译_英文 | 自定义"
}
```

Response:
```json
{
  "text": "风格化后的文字",
  "original_text": "ASR 原始转写",
  "duration_ms": 0,
  "error": null
}
```

## Migration Path

当前是原型阶段，后续接入真实 API **无需改动核心代码或接口定义**，只需三步：

1. 新增一个继承 `ASRProvider` / `Stylizer` 的类，实现其抽象方法；
2. 在对应模块的 `_PROVIDERS` 字典里注册名称；
3. 改 `.env` 的 `ASR_PROVIDER` / `LLM_PROVIDER` 为新名字，并填好 key。

例如：
- `asr.py` → 新增 `XunfeiASRProvider` / `WhisperASRProvider`
- `stylize.py` → 新增 `DeepSeekStylizer` / `QwenStylizer` / `ClaudeStylizer`
