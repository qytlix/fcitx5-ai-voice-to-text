# 使用官方 Python 3.11 镜像作为基础
FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 复制 requirements.txt 并安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制项目文件
COPY . .

# 设置环境变量
ENV PYTHONIOENCODING=utf-8
ENV PYTHONUNBUFFERED=1
ENV APP_ENV=production

# 暴露端口
EXPOSE 8080

# 启动服务
CMD ["uvicorn", "server.app:app", "--host", "0.0.0.0", "--port", "8080"]
