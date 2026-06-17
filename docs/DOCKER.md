# Docker 部署指南

## 前置要求

- Docker 桌面版 或 Docker Engine
- Docker Compose（通常包含在 Docker Desktop 中）
- 配置好的 `.env` 文件（包含 Xunfei 凭证）

## 快速启动

### 方式 1：使用 Docker Compose（推荐）

```bash
# 1. 克隆项目
git clone <your-repo-url>
cd zqProject

# 2. 创建 .env 文件（或使用现有的）
cat > .env << EOF
ASR_PROVIDER=xunfei
ASR_API_KEY=your_appid_here
ASR_API_SECRET=your_secret_key_here
ASR_API_PASSWORD=your_secret_key_here
LLM_PROVIDER=mock
MAX_AUDIO_MB=10
REQUEST_TIMEOUT_SECONDS=30
CORS_ORIGINS=*
EOF

# 3. 启动服务
docker-compose up -d

# 4. 检查服务状态
curl http://localhost:8080/health

# 查看日志
docker-compose logs -f server
```

### 方式 2：仅使用 Docker

```bash
# 构建镜像
docker build -t fcitx5-asr:latest .

# 运行容器
docker run -d \
  --name fcitx5-asr \
  -p 8080:8080 \
  --env-file .env \
  -v $(pwd)/server/data:/app/server/data \
  fcitx5-asr:latest

# 查看日志
docker logs -f fcitx5-asr

# 停止容器
docker stop fcitx5-asr
```

## 环境变量

在 `.env` 中配置：

```ini
# ASR 配置
ASR_PROVIDER=xunfei          # 固定值
ASR_API_KEY=0e463fe2          # 你的 APPID
ASR_API_SECRET=85b6e4abe...   # 你的 SecretKey
ASR_API_PASSWORD=85b6e4abe... # 你的 SecretKey（同上）

# LLM 配置
LLM_PROVIDER=mock              # mock | deepseek

# 服务配置
MAX_AUDIO_MB=10
REQUEST_TIMEOUT_SECONDS=30
CORS_ORIGINS=*                 # 逗号分隔的域名列表
API_TOKEN=                      # 可选，留空表示不启用鉴权

# 应用环境
APP_ENV=production             # production | development
```

## 验证服务

```bash
# 健康检查
curl http://localhost:8080/health

# 测试转录接口
curl -X POST http://localhost:8080/v1/transcribe \
  -H "Content-Type: application/json" \
  -d '{
    "audio": "UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQIAAAAAAA==",
    "style": "正式"
  }'
```

## 常见问题

### 端口已被占用

```bash
# 修改 docker-compose.yml 中的端口映射
# 将 "8080:8080" 改为 "8081:8080"
```

### 容器内找不到 .env 文件

确保 `.env` 在项目根目录，Docker Compose 会自动加载。

### 反馈数据持久化

`server/data` 目录已通过 volume 挂载到容器，反馈数据会自动保存。

### 查看容器日志

```bash
docker-compose logs -f server        # 实时日志
docker-compose logs --tail 100       # 最后 100 行
```

### 停止和清理

```bash
# 停止运行
docker-compose down

# 完全清理（包括数据）
docker-compose down -v
```

## 生产部署建议

1. **镜像优化**：当前 Dockerfile 使用 `python:3.11-slim`，已经很轻量。如需进一步优化，可使用多阶段构建。

2. **日志**：将日志输出到文件或日志聚合系统（如 ELK、Datadog）。

3. **健康检查**：docker-compose.yml 可添加：
   ```yaml
   healthcheck:
     test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
     interval: 30s
     timeout: 10s
     retries: 3
   ```

4. **资源限制**：
   ```yaml
   deploy:
     resources:
       limits:
         cpus: '1'
         memory: 512M
       reservations:
         cpus: '0.5'
         memory: 256M
   ```

5. **反向代理**：部署前加上 Nginx 做反向代理和 SSL 终止。

## GitHub Actions 自动构建（可选）

在 `.github/workflows/docker.yml` 中添加，自动构建并推送镜像到 Docker Hub：

```yaml
name: Build and Push Docker Image
on:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: docker/setup-buildx-action@v2
      - uses: docker/build-push-action@v4
        with:
          context: .
          push: true
          tags: your-username/fcitx5-asr:latest
```
