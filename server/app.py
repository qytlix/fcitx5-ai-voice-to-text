"""
Fcitx5 AI Voice-to-Text Core — Server

FastAPI 服务入口（路由层）。

只负责三件事：创建 app、配置中间件、定义路由。
所有业务逻辑下沉到 pipeline.py，所有可调参数来自 config.py。
"""

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from typing import Optional

from .config import get_settings
from .models import TranscribeRequest, TranscribeResponse
from .pipeline import run_transcribe

settings = get_settings()

app = FastAPI(
    title="Fcitx5 AI Voice-to-Text Core",
    description="语音转文字 + AI 风格化核心服务",
    version="0.1.0",
)

# CORS。来源列表来自配置；当配置为具体域名时才开启 credentials。
# 注意：allow_origins=["*"] 与 allow_credentials=True 不能并用——
# 浏览器会拒绝带凭证的通配请求，所以这里做了互斥处理。
_allow_all = settings.cors_origins_list == ["*"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_credentials=not _allow_all,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health():
    """健康检查。返回服务状态与版本，供客户端 / 负载均衡探活。"""
    return {"status": "ok", "version": app.version}


def require_token(x_api_token: Optional[str] = Header(None)) -> None:
    """
    可选的接口鉴权。

    settings.api_token 为空（默认）时直接放行，行为与原型一致；
    配置了 token 后，请求头 X-API-Token 不匹配则返回 401。
    """
    if settings.api_token and x_api_token != settings.api_token:
        raise HTTPException(status_code=401, detail="无效或缺失的 API token")


@app.post("/v1/transcribe", response_model=TranscribeResponse)
def transcribe_audio(
    req: TranscribeRequest,
    _: None = Depends(require_token),
) -> TranscribeResponse:
    """
    转录主接口：音频 → ASR 转文字 → 风格化 → 返回结果。

    请求体由 TranscribeRequest 校验，流程由 pipeline.run_transcribe 执行。
    可预期错误以 200 + error 字段返回，便于客户端统一处理。
    """
    return run_transcribe(req, settings)
