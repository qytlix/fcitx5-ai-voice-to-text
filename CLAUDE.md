# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fcitx5 AI Voice-to-Text Core — 语音输入 → ASR 转文字 → AI 风格化整理 → 输入法上屏。

本项目是核心协议与逻辑层，外部封装为 Fcitx5 各平台插件。当前处于**原型阶段**，ASR 和风格化均为固定模拟实现，后续接入真实 API。

## Architecture

```
用户 → [Client: Kotlin / Android] → HTTP/JSON → [Server: Python / FastAPI]
                                                        ├── asr.py      (固定模拟 → 讯飞/Whisper)
                                                        ├── stylize.py  (固定模拟 → LLM API)
                                                        └── app.py      (FastAPI 路由入口)
```

- **Client**: Kotlin（Android），对接 Fcitx5 输入法接口
- **Server**: Python / FastAPI，提供 REST API
- **通信**: HTTP REST + JSON

## Server 模块职责

| 模块 | 职责 |
|---|---|
| `app.py` | FastAPI 入口，/health GET + /v1/transcribe POST |
| `models.py` | TranscribeRequest / TranscribeResponse Pydantic 模型 |
| `asr.py` | 音频 → 文字（当前固定模拟） |
| `stylize.py` | 文字 → 风格化（当前规则模拟，支持正式/精简/礼貌/翻译_英文/自定义） |

## Commands

```bash
# 激活环境
conda activate v2t

# 启动服务端（开发模式，带热重载）
uvicorn server.app:app --reload --host 0.0.0.0 --port 8080

# 测试健康检查
curl http://localhost:8080/health

# 测试转录接口
curl -X POST http://localhost:8080/v1/transcribe \
  -H "Content-Type: application/json" \
  -d '{"audio": "AAECAw==", "style": "正式"}'
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

当前是原型阶段，后续接入真实 API 只需修改内部实现，**接口定义不变**：

- `asr.py` → `transcribe()` 替换为讯飞 / Whisper / 阿里云 ASR 调用
- `stylize.py` → `stylize()` 替换为 DeepSeek / 通义千问 / Claude LLM 调用