# Fcitx5 AI Voice-to-Text Core

用户语音输入 → ASR 转文字 → AI 风格化整理 → 输入法上屏。

例如讲话时想到什么说什么，AI 将其转化为更有逻辑、更正式的语言。

本项目是**核心协议与逻辑层**，外部可封装为 Fcitx5 Android / Linux / Windows 等平台的输入法插件。

## 架构

```mermaid
flowchart LR
    User["🎤 用户语音"] --> Client["Client (Android / Kotlin)"]
    Client -->|HTTP / JSON| Server["Server (Python / FastAPI)"]
    Server --> ASR["ASR API (讯飞等)"]
    ASR --> Server
    Server --> LLM["LLM API 风格化"]
    LLM --> Server
    Server --> Client
    Client --> IM["⌨️ Fcitx5 上屏"]
```

## 技术选型

| 层 | 语言/框架 | 说明 |
|---|---|---|
| **Client** | **Kotlin** | Fcitx5 Android 插件原生语言，直接调用 Android AudioRecord 录音 & Fcitx5 输入接口 |
| **Server** | **Python / FastAPI** | 原型阶段开发效率高，ASR + LLM 外部 API 封装方便；后续可迁移至 Go/Rust |
| **通信** | HTTP REST + JSON | 原型阶段最简方案，后续可升级为 gRPC / WebSocket |

## 项目结构

```
fcitx5-ai-voice-to-text-core/
├── client/                  # Android 客户端（Kotlin 模块）
│   └── (待开发)
├── server/                  # 服务端（Python / FastAPI）
│   ├── app.py              # FastAPI 入口 / 路由层（只接线，不含业务逻辑）
│   ├── pipeline.py         # 编排层：校验 → ASR → 风格化 → 组装响应 + 错误处理
│   ├── asr.py              # ASR 层：抽象基类 + Mock 实现 + 工厂（可插拔 provider）
│   ├── stylize.py          # 风格化层：抽象基类 + 规则实现 + 工厂（可插拔 provider）
│   ├── models.py           # Pydantic 数据模型 + Style 风格枚举
│   ├── config.py           # 配置层：读取 .env / 环境变量
│   └── environment.yml     # Conda 环境配置
├── .env.example            # 环境变量模板（复制为 .env 后填值）
└── README.md
```

### 分层设计与「可插拔 provider」

各层职责单一，依赖方向单向（app → pipeline → asr/stylize → config/models）：

- **config.py** — 所有可调参数（provider、API key、大小上限、CORS）收敛于此，从 `.env` 读取。
- **asr.py / stylize.py** — 各自定义一个抽象基类和一个原型阶段的 mock 实现，由工厂函数按配置选择 provider。
- **pipeline.py** — 把流程串成一条线，并把可预期错误转成响应里的 `error` 字段，而非 500。
- **app.py** — 仅负责路由与中间件，逻辑全部下沉。

> 接入真实 ASR / LLM 时：新增一个继承基类的 provider 类 → 在该模块的 `_PROVIDERS` 注册 → 改 `.env` 里的 `ASR_PROVIDER` / `LLM_PROVIDER`。**核心代码与接口定义都不用改。**

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

成功：

```json
{
  "text": "您有一个新的会议邀请，安排在下午三点……",
  "original_text": "那个…下午三点有个会",
  "duration_ms": 1240,
  "error": null
}
```

可预期错误（如 base64 非法、音频超限）——HTTP 仍为 200，`error` 非空：

```json
{
  "text": "",
  "original_text": "",
  "duration_ms": 0,
  "error": "audio 字段不是合法的 base64 数据"
}
```

> 字段校验类错误（如 `style` 取值非法）由 FastAPI 在进入逻辑前拦下，返回 **422**。

## 风格系统

| 风格 | 说明 |
|---|---|
| `正式` | 口语 → 书面语，去除语气词 |
| `精简` | 去除冗余，保留核心信息 |
| `礼貌` | 调整语气，添加敬语 |
| `翻译_英文` | 翻译为英文并优化表达 |
| `自定义` | 用户通过 `prompt` 参数自由定义 |

## Quick Start（原型阶段）

### 1. 创建并激活 Python 虚拟环境

Windows PowerShell:

```powershell
cd C:\Users\17879\Desktop\claude\zqProject
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

如果 `py` 没有注册，但知道 Python 安装路径，也可以使用完整路径：

```powershell
& 'C:\Users\17879\AppData\Local\Programs\Python\Python313\python.exe' -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

Conda:

```bash
conda env create -f server/environment.yml
conda activate fcitx5-voice
```

### 2. （可选）配置环境变量

```bash
cp .env.example .env
```

原型阶段无需任何配置即可运行（默认全部走 mock）。需要接真实 API 或收紧 CORS 时再编辑 `.env`。

### 3. 启动服务端

```bash
uvicorn server.app:app --reload --host 0.0.0.0 --port 8080
```

> `--reload` 仅在开发阶段使用，修改代码后自动重启。

### 4. 测试接口

```bash
# 健康检查
curl http://localhost:8080/health

# 转录测试 — 发送不同长度的音频数据，返回不同模拟结果
curl -X POST http://localhost:8080/v1/transcribe \
  -H "Content-Type: application/json" \
  -d '{"audio": "AAECAw==", "style": "精简"}'

# 返回示例：
# {"text":"好的。","original_text":"好的","duration_ms":0,"error":null}
```

## 后续规划（Roadmap）

- [ ] **Phase 1** — 服务端 ASR + 风格化逻辑可独立运行（当前原型目标）
- [ ] **Phase 2** — Kotlin Client 核心：录音 → 发送 → 接收 → 上屏
- [ ] **Phase 3** — 封装为 Fcitx5 Android 插件
- [ ] **Phase 4** — Fcitx5 Linux / Windows 插件封装
- [ ] **Phase 5** — 自定义提示词模板管理 UI
- [ ] **Phase 6** — 离线 ASR / 端侧小模型支持

## 说明

- 本项目为**核心协议与逻辑层**，不直接包含 Fcitx5 插件代码。
- 各平台插件作为独立仓库，通过本项目定义的 Client API 进行对接。
