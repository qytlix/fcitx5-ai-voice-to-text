# Fcitx5 AI Voice-to-Text 服务端 API 文档

## 概览

本服务为 Fcitx5 输入法提供语音转文字和文本风格化的 REST API。
- **语音识别**：集成讯飞 IAT（语音听写流式版）WebSocket API
- **文本风格化**：支持正式、精简、礼貌、英文翻译、自定义等多种风格
- **部署**：Docker Compose 一键启动

---

## 快速开始

### Docker 部署（推荐）

```bash
# 1. 克隆项目
git clone https://github.com/your-username/zqProject.git
cd zqProject

# 2. 配置环境变量（.env）
cp .env.example .env
# 编辑 .env 填入你的讯飞凭证

# 3. 启动服务
docker-compose up -d

# 4. 验证服务
curl http://localhost:8080/health
```

### 本地开发

```bash
# 激活虚拟环境
python -m venv .venv
source .venv/bin/activate  # Linux/Mac
.venv\Scripts\activate      # Windows

# 安装依赖
pip install -r requirements.txt

# 启动开发服务器（带热重载）
PYTHONIOENCODING=utf-8 uvicorn server.app:app --reload --port 8080
```

详见 [DOCKER.md](DOCKER.md)。

---

## API 接口

### 1. 健康检查

```
GET /health
```

**响应 (200 OK):**
```json
{
  "status": "ok",
  "version": "0.1.0"
}
```

---

### 2. 语音转写 + 风格化

```
POST /v1/transcribe
Content-Type: application/json
```

**请求体：**
```json
{
  "audio": "<base64_encoded_audio>",
  "style": "正式",
  "prompt": "(可选) 自定义提示词",
  "sample": "(可选) 样例文本"
}
```

**字段说明：**

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `audio` | string | ✅ | Base64 编码的音频文件（WAV 或 PCM 格式） |
| `style` | enum | ✅ | 文本风格：`正式`\|`精简`\|`礼貌`\|`翻译_英文`\|`自定义` |
| `prompt` | string | ❌ | 自定义风格提示词（style=自定义 时使用） |
| `sample` | string | ❌ | 样例文本（未来用于微调） |

**风格说明：**

| 风格 | 描述 | 示例 |
|------|------|------|
| `正式` | 口语 → 正式书面语，移除语气词 | "改一下" → "进行调整" |
| `精简` | 保留核心信息，去掉修饰和冗余表达 | "我觉得这个方案可以" → "这个方案可以" |
| `礼貌` | 加入敬语，表现尊重 | "帮我查一下" → "麻烦您帮我查一下" |
| `翻译_英文` | 翻译为英文 | "下午三点有个会议" → "There is a meeting at 3 PM." |
| `自定义` | 按 `prompt` 关键字处理 | - |

**响应 (200 OK):**
```json
{
  "text": "进行调整系统配置",
  "original_text": "改一下系统配置",
  "duration_ms": 245,
  "error": null
}
```

**响应 (200 with error):**
```json
{
  "text": null,
  "original_text": null,
  "duration_ms": 0,
  "error": "音频文件过大（>10MB）"
}
```

**错误类型（均返回 200 + error 字段，不返回 5xx）：**

| 错误信息 | 原因 | 解决方案 |
|--------|------|--------|
| `音频文件过大` | 超过 MAX_AUDIO_MB 限制 | 减小音频文件大小 |
| `音频格式不支持` | 既不是 WAV 也不是 PCM | 使用 WAV 或 PCM 编码 |
| `ASR 超时` | 讯飞 API 响应超时 | 检查网络连接，重试请求 |
| `讯飞 API 错误` | 讯飞服务异常 | 检查 API 凭证和配额 |

---

### 3. 用户反馈（可选）

```
POST /v1/feedback
Content-Type: application/json
```

用于收集用户对转写结果的修改，帮助后续模型优化。

**请求体：**
```json
{
  "original_text": "改一下系统",
  "styled_text": "进行调整系统",
  "final_text": "调整一下系统配置",
  "style": "正式",
  "duration_ms": 245
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `original_text` | string | ASR 原始转写结果 |
| `styled_text` | string | 风格化后的结果 |
| `final_text` | string | 用户最终编辑的文本 |
| `style` | string | 使用的风格 |
| `duration_ms` | int | 音频时长（毫秒） |

**响应 (200 OK):**
```json
{
  "status": "recorded"
}
```

**存储位置：** `server/data/feedback.jsonl`（JSONL 格式，每行一条反馈）

---

### 4. 反馈统计

```
GET /v1/feedback/count
```

**响应 (200 OK):**
```json
{
  "count": 42,
  "last_updated": "2026-06-16T12:34:56"
}
```

---

## 环境配置

在 `.env` 或 Docker 环境变量中配置：

```ini
# 讯飞 IAT API 凭证（必需）
ASR_PROVIDER=xunfei
ASR_API_KEY=your_appid_here              # 讯飞应用 ID (app_id)
ASR_API_SECRET=your_api_key_here         # 讯飞 API Key (api_key)
ASR_API_PASSWORD=your_api_secret_here    # 讯飞 API Secret (api_secret)

# LLM 风格化提供方
LLM_PROVIDER=mock                    # mock | deepseek
# LLM_API_KEY=sk-xxx                 # 仅 deepseek 需要

# 服务参数
MAX_AUDIO_MB=10                      # 最大音频文件大小（MB）
REQUEST_TIMEOUT_SECONDS=30           # 请求超时（秒）
CORS_ORIGINS=*                       # CORS 允许的来源

# 可选：API 鉴权
# API_TOKEN=your_token_here

# 应用环境
APP_ENV=production                   # production | development
```

### 获取讯飞凭证

1. 访问 [讯飞开放平台](https://www.xfyun.cn/)
2. 创建应用，选择 **语音听写（流式版）** 服务
3. 获取 `APPID`、`API_KEY`、`API_SECRET`
4. 填入 `.env`

---

## 示例请求

### Python

```python
import base64
import requests
import json

# 读取音频文件
with open("sample.wav", "rb") as f:
    audio_b64 = base64.b64encode(f.read()).decode()

# 发送请求
response = requests.post(
    "http://localhost:8080/v1/transcribe",
    json={
        "audio": audio_b64,
        "style": "正式"
    }
)

result = response.json()
print(f"原文: {result['original_text']}")
print(f"风格化后: {result['text']}")
if result['error']:
    print(f"错误: {result['error']}")
```

### cURL

```bash
# 将音频转成 Base64
AUDIO_B64=$(base64 -w0 sample.wav)

# 发送请求
curl -X POST http://localhost:8080/v1/transcribe \
  -H "Content-Type: application/json" \
  -d "{\"audio\": \"$AUDIO_B64\", \"style\": \"正式\"}"
```

### Android (Kotlin)

```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.io.File

val client = OkHttpClient()
val audioFile = File("sample.wav")
val audioB64 = Base64.getEncoder().encodeToString(audioFile.readBytes())

val requestBody = """
    {
        "audio": "$audioB64",
        "style": "正式"
    }
""".trim().toRequestBody("application/json".toMediaType())

val request = Request.Builder()
    .url("http://localhost:8080/v1/transcribe")
    .post(requestBody)
    .build()

client.newCall(request).execute().use { response ->
    if (response.isSuccessful) {
        val result = response.body?.string()
        // 解析 JSON
    }
}
```

---

## 性能指标

| 指标 | 值 |
|------|-----|
| 单次请求延迟 | ~2-5s（取决于讯飞 API） |
| 最大音频时长 | 受讯飞 IAT 限制，最长 60 秒；同时受 MAX_AUDIO_MB 限制，默认 10MB |
| 并发处理能力 | 取决于 Docker 内存/CPU 分配 |
| 反馈存储 | 无限制，JSONL 文件追加模式 |

---

## 故障排查

### 服务无法启动

**问题：** `ERROR: [WinError 10013] ...`

**原因：** 端口被占用或权限不足

**解决：**
```bash
# 检查端口占用
lsof -i :8080  # Linux/Mac
netstat -ano | findstr :8080  # Windows

# 或修改 docker-compose.yml 中的端口
```

### 转录超时

**问题：** `ASR 超时`

**原因：** 讯飞 API 响应慢或网络不稳定

**解决：**
- 检查网络连接
- 增加 `REQUEST_TIMEOUT_SECONDS` 配置
- 检查讯飞 API 配额

### 音频格式错误

**问题：** `音频格式不支持`

**原因：** 音频既不是 WAV 也不是 PCM

**解决：** 转换为 WAV 格式
```bash
ffmpeg -i input.mp3 -acodec pcm_s16le -ar 16000 output.wav
```

### 讯飞 API 认证失败

**问题：** `讯飞 API 错误: 无效的签名`

**原因：** API 凭证错误或过期

**解决：**
- 验证 `.env` 中的 `ASR_API_KEY`、`ASR_API_SECRET`、`ASR_API_PASSWORD`
- 检查凭证是否过期，重新获取

---

## 文档和资源

| 资源 | 位置 |
|------|------|
| 项目说明 | [CLAUDE.md](CLAUDE.md) |
| Docker 部署 | [DOCKER.md](DOCKER.md) |
| 本 API 文档 | [API.md](API.md) |
| 项目架构 | [README.md](README.md) |
| 讯飞 IAT 文档 | https://www.xfyun.cn/doc/asr/voicedictation.html |

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 0.1.0 | 2026-06-16 | 初始版本，Xunfei IAT WebSocket 集成完成 |

---

## 联系方式

- GitHub Issues：[zqProject/issues](https://github.com/your-username/zqProject/issues)
- 讯飞支持：https://www.xfyun.cn/
