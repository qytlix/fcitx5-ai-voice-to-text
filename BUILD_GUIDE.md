# Fcitx5 AI Voice-to-Text 构建指南

## 项目概述

Fcitx5 AI Voice-to-Text 是一个语音输入系统，包含：
- **Client**: Android/Kotlin 客户端，基于 Fcitx5 输入法框架
- **Server**: Python FastAPI 后端，处理语音识别和文本风格化

架构流程：
```
用户语音 → Client(Android) → HTTP/JSON → Server(Python)
           ↓                            ↓
        Retrofit                   ASR → Stylize
        (网络请求)              (语音转文字) (风格化处理)
           ↑                            ↓
        收集响应                  返回处理后的文本
```

---

## 环境要求

### 全局要求
- Git
- Python 3.8+
- Java JDK 17+

### Client 构建（Android APK）
- **Android SDK** （API 26+，推荐 API 34）
- **Gradle 8.9+** （项目自动下载）
- **Kotlin 1.9.22+**

### Server 运行（开发环境）
- FastAPI
- Uvicorn

---

## 一键安装（推荐）

### 1. 安装 Android Studio

为最简单和稳定，**强烈推荐安装 Android Studio**（自动配置所有 Android 环境）。

**下载链接**: https://developer.android.com/studio

**安装步骤**:
1. 下载 Android Studio 安装包
2. 运行安装程序
3. 选择 "Standard" 安装（包含 Android SDK）
4. 在 SDK Components Setup 中保持默认选项
5. 完成后，Android Studio 会自动配置 `ANDROID_HOME`

**验证安装**:
```bash
echo %ANDROID_HOME%  # Windows
echo $ANDROID_HOME   # Mac/Linux
```

### 2. 安装 Java JDK 17

**下载链接**: https://adoptium.net/temurin/releases

- 选择 **JDK 17 LTS**
- 选择 **Windows MSI 64-bit** 格式
- 安装后，MSI 会自动设置 `JAVA_HOME`

**验证**:
```bash
java -version
javac -version
```

### 3. 克隆项目

```bash
git clone https://github.com/qytlix/fcitx5-ai-voice-to-text.git
cd fcitx5-ai-voice-to-text
git checkout client  # 切换到 client 分支（包含完整构建配置）
```

---

## 目录结构

```
fcitx5-ai-voice-to-text/
├── client/                          # Android 客户端
│   ├── app/                         # 应用主模块
│   │   ├── src/main/java/...        # Kotlin 源代码
│   │   │   └── voice/data/RetrofitTranscribeService.kt  # 网络接口
│   │   └── build.gradle.kts         # 应用依赖配置
│   ├── core/                        # 核心库（通用模块）
│   ├── build.gradle.kts             # 项目级配置
│   ├── settings.gradle.kts          # 模块管理（已配置Aliyun镜像）
│   ├── gradle.properties            # Gradle 属性
│   ├── gradle/wrapper/              # Gradle 包装器
│   └── local.properties             # Android SDK 路径（自动配置）
│
├── server/                          # Python FastAPI 后端
│   ├── app.py                       # 主应用入口（路由层）
│   ├── pipeline.py                  # 请求处理管道
│   ├── asr.py                       # 语音识别（ASRProvider）
│   ├── stylize.py                   # 文本风格化
│   ├── config.py                    # 配置读取
│   ├── models.py                    # Pydantic 数据模型
│   └── requirements.txt             # Python 依赖
│
├── .env.example                     # 环境变量模板
├── CLAUDE.md                        # 项目开发指南
├── README.md                        # 项目简介
├── BUILD_GUIDE.md                   # 本文件
└── test_client.py                   # Python 测试客户端

```

---

## 构建 Android APK

### 第一次构建（完整过程）

1. **打开项目**:
   
   方式 A（推荐）：用 Android Studio 打开
   ```bash
   # 在项目根目录
   start .  # 打开文件浏览器
   # 拖入 Android Studio，或菜单 File > Open > 选择 client/ 文件夹
   ```
   
   方式 B：命令行构建
   ```bash
   cd client
   ./gradlew assembleDebug   # 构建 Debug APK
   # 或
   ./gradlew assembleRelease # 构建 Release APK
   ```

2. **等待构建完成**

   首次构建会下载 Gradle、Android SDK、依赖等，可能需要 5-15 分钟（取决于网络）。
   
   **中国用户注意**：项目已配置 Aliyun 镜像加速（`settings.gradle.kts`），如果还是很慢可以：
   - 检查网络连接
   - 或在 Android Studio 中配置代理

3. **获取 APK**

   构建成功后，APK 文件位置：
   ```
   client/app/build/outputs/apk/debug/app-debug.apk    (Debug)
   client/app/build/outputs/apk/release/app-release.apk (Release)
   ```

4. **部署到设备**

   ```bash
   cd client
   ./gradlew installDebug    # 直接安装到连接的 Android 设备
   ```

### 增量构建（后续）

修改代码后，只需重新构建改动部分（更快）：
```bash
./gradlew build -x test
```

---

## 运行 Server

Server 处理 ASR 和文本风格化请求。

### 1. 安装依赖

```bash
cd server  # 或项目根目录
python -m venv .venv  # 创建虚拟环境
.venv\Scripts\activate  # Windows
# 或
source .venv/bin/activate  # Mac/Linux

pip install -r requirements.txt
```

### 2. 配置环境

创建 `.env` 文件（基于 `.env.example`）:
```bash
cp .env.example .env
```

编辑 `.env`，配置必要参数：
```ini
# ASR 提供商
ASR_PROVIDER=mock  # 当前用 mock（演示），后续改为 whisper/xunfei/etc

# LLM 提供商（文本风格化）
LLM_PROVIDER=mock  # 当前用 mock，后续改为 deepseek/qwen/etc

# 服务器配置
HOST=0.0.0.0
PORT=8080
```

### 3. 启动服务

```bash
uvicorn server.app:app --reload --host 0.0.0.0 --port 8080
```

输出示例：
```
INFO:     Uvicorn running on http://0.0.0.0:8080
INFO:     Application startup complete
```

---

## API 接口

### Health Check

```bash
curl http://localhost:8080/health
```

### 转录 (Transcribe)

**请求**:
```bash
curl -X POST http://localhost:8080/v1/transcribe \
  -H "Content-Type: application/json" \
  -d '{
    "audio": "AAECAw==",
    "style": "正式",
    "prompt": "可选的上下文提示",
    "sample": "可选的样例文本"
  }'
```

**响应**:
```json
{
  "text": "这是风格化后的文本",
  "original_text": "这是 ASR 原始转写的文本",
  "duration_ms": 1234,
  "error": null
}
```

**参数**:
- `audio` (必需): Base64 编码的音频数据
- `style` (必需): 风格，选择：
  - `正式` — 正式书面语
  - `精简` — 简洁版本
  - `礼貌` — 加敬语
  - `翻译_英文` — 翻译成英文
  - `自定义` — 自定义提示词
- `prompt` (可选): 自定义提示词（当 style=自定义 时使用）
- `sample` (可选): 参考样本文本

---

## 代码模块说明

### Client 端 (`client/`)

#### RetrofitTranscribeService.kt
- **职责**: 与 Server 通信的网络接口
- **功能**:
  - 创建 Retrofit 实例（连接到 Server）
  - 定义 `/v1/transcribe` API 接口
  - 处理 JSON 序列化
- **使用**:
  ```kotlin
  val service = RetrofitTranscribeService.create("http://10.0.2.2:8080")
  val response = service.transcribe(TranscribeRequest(...))
  ```

#### 依赖
- Retrofit 2.9.0 — 网络请求库
- OkHttp 4.12.0 — HTTP 客户端
- Gson — JSON 序列化
- Coroutines — 异步编程

### Server 端 (`server/`)

#### app.py
- **职责**: FastAPI 应用入口、路由定义
- **端点**:
  - `GET /health` — 健康检查
  - `POST /v1/transcribe` — 语音转文字

#### pipeline.py
- **职责**: 编排请求的完整流程
- **流程**:
  1. 验证请求（大小、格式）
  2. 调用 ASR 获取文本
  3. 调用 Stylizer 风格化
  4. 返回结果或错误
- **错误处理**: 预期错误转为响应 `error` 字段

#### asr.py
- **职责**: 语音识别接口
- **实现**:
  - `ASRProvider` 抽象基类
  - `MockASRProvider` 演示实现
  - `get_asr_provider()` 工厂函数
- **扩展**: 后续添加新提供商（如 Xunfei）只需继承 `ASRProvider`

#### stylize.py
- **职责**: 文本风格化处理
- **实现**:
  - `Stylizer` 抽象基类
  - `RuleBasedStylizer` 规则引擎实现
  - `get_stylizer()` 工厂函数
- **支持的风格**: 正式、精简、礼貌、翻译_英文、自定义

#### config.py
- **职责**: 从 `.env` 和环境变量读取配置
- **提供的配置**:
  - ASR 提供商 (`ASR_PROVIDER`)
  - LLM 提供商 (`LLM_PROVIDER`)
  - API keys、模型参数等

#### models.py
- **职责**: Pydantic 数据模型
- **模型**:
  - `TranscribeRequest` — 请求体
  - `TranscribeResponse` — 响应体
  - `Style` — 枚举

---

## 常见问题

### Q: 构建时卡在依赖下载？

**A**: 这通常是网络问题。
- 检查网络连接
- 确认已使用 Aliyun 镜像（项目已配置）
- 如果还是慢，在 Android Studio 中配置 HTTP 代理或 VPN

### Q: `ANDROID_HOME` 未设置？

**A**: 
- 检查 Android SDK 是否正确安装
- 手动设置环境变量：
  ```bash
  # Windows (PowerShell)
  [Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\<你的用户名>\AppData\Local\Android\Sdk", "User")
  ```
- 或在 `client/local.properties` 中指定 `sdk.dir`

### Q: 如何指定不同的 Server 地址？

**A**: 在 `RetrofitTranscribeService.kt` 中修改：
```kotlin
val service = RetrofitTranscribeService.create("http://your-server:8080")
```

或作为配置参数传入。

### Q: Server 启动失败，提示缺少模块？

**A**: 确保已安装依赖：
```bash
pip install -r requirements.txt
```

---

## 后续开发

### 接入真实 ASR 提供商

1. 在 `asr.py` 中新增提供商类（继承 `ASRProvider`）
2. 在 `.env` 中配置提供商名称和 API key
3. 无需改动其他代码

例如，接入 Xunfei ASR：
```python
# asr.py
class XunfeiASRProvider(ASRProvider):
    def transcribe(self, audio_bytes: bytes) -> str:
        # 调用讯飞 API
        pass

# .env
ASR_PROVIDER=xunfei
XUNFEI_API_KEY=your-key
```

### 接入真实 LLM 提供商

同理在 `stylize.py` 中新增 Stylizer 实现。

### 部署到生产

当前是原型阶段，部署时需要：
1. 配置生产环境变量（.env）
2. 使用 HTTPS
3. 配置 CORS（CLAUDE.md 中有说明）
4. 部署到云服务器（云函数、容器等）

---

## 其他资源

- **项目文档**: 参考 `CLAUDE.md` 获取详细开发指南
- **API 文档**: Server 启动后访问 http://localhost:8080/docs（FastAPI 自动生成的 Swagger UI）
- **测试**: 运行 `python test_client.py` 测试 API

---

## 联系和反馈

项目地址: https://github.com/qytlix/fcitx5-ai-voice-to-text

有问题？
- 检查 Issues
- 或提交新 Issue 描述问题

祝开发愉快！ 🚀
