"""
Fcitx5 AI Voice-to-Text Core — Server

FastAPI 服务入口。原型阶段提供固定接口，不调用外部 API。
"""

import time
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .models import TranscribeRequest, TranscribeResponse
from .asr import transcribe
from .stylize import stylize

app = FastAPI(
    title="Fcitx5 AI Voice-to-Text Core",
    description="语音转文字 + AI 风格化核心服务",
    version="0.1.0",
)

# 允许所有来源（原型阶段方便测试）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health():
    """健康检查"""
    return {"status": "ok", "version": "0.1.0"}


@app.post("/v1/transcribe", response_model=TranscribeResponse)
def transcribe_audio(req: TranscribeRequest):
    """
    接收音频 → ASR 转文字 → 风格化 → 返回结果。

    当前为固定实现，不调用外部 API。
    """
    start = time.time()

    # Step 1: ASR 转写
    original_text = transcribe(req.audio)

    # Step 2: 风格化
    styled_text = stylize(original_text, style=req.style, prompt=req.prompt)

    elapsed = int((time.time() - start) * 1000)

    return TranscribeResponse(
        text=styled_text,
        original_text=original_text,
        duration_ms=elapsed,
        error=None,
    )