"""
编排层（pipeline）。

把一次转录请求的完整流程串起来，并集中处理错误：
    1. 校验音频（base64 合法性 + 大小上限）
    2. ASR：音频 → 原始文字
    3. 风格化：原始文字 → 最终文字
    4. 组装 TranscribeResponse（含耗时；出错时填 error 而非抛 500）

路由层（app.py）只管接收请求、调用这里、返回结果，不含业务逻辑。
这样「流程」只有一处定义，测试和未来替换都方便。
"""

import base64
import binascii
import logging
import time

from .asr import ASRError, get_asr_provider
from .config import Settings
from .models import TranscribeRequest, TranscribeResponse
from .stylize import StylizeError, get_stylizer

logger = logging.getLogger(__name__)


def _validate_audio(audio_base64: str, max_audio_mb: int) -> None:
    """
    校验 base64 音频：能否解码 + 解码后是否超过大小上限。

    失败抛 ASRError，由 run_transcribe 捕获。
    """
    if not audio_base64:
        raise ASRError("audio 字段为空，缺少音频数据")

    try:
        # validate=True 让非法 base64 字符直接报错，而不是被静默忽略
        raw = base64.b64decode(audio_base64, validate=True)
    except (binascii.Error, ValueError):
        raise ASRError("audio 字段不是合法的 base64 数据")

    if len(raw) > max_audio_mb * 1024 * 1024:
        raise ASRError(f"音频超过大小上限 {max_audio_mb} MB")


def run_transcribe(req: TranscribeRequest, settings: Settings) -> TranscribeResponse:
    """
    执行完整转录流程并返回响应。

    可预期的错误（音频非法、ASR/风格化失败）被捕获后写入 response.error，
    HTTP 状态码仍为 200——让客户端用统一的 JSON 结构处理成功与失败，
    而不必区分 4xx/5xx。非预期异常仍向上抛出，由 FastAPI 转 500。
    """
    start = time.time()
    audio_len = len(req.audio) if req.audio else 0

    logger.info(
        f"[Transcribe] 开始处理: style={req.style}, audio_len={audio_len}, "
        f"has_prompt={bool(req.prompt)}, has_sample={bool(req.sample)}"
    )

    try:
        # Step 1: 校验音频
        logger.debug(f"[Transcribe] Step 1: 校验音频 (大小限制: {settings.max_audio_mb}MB)")
        _validate_audio(req.audio, settings.max_audio_mb)
        logger.debug(f"[Transcribe] 音频校验通过")

        # Step 2: ASR 转写（provider 由配置决定）
        logger.debug(f"[Transcribe] Step 2: 调用 ASR provider: {settings.asr_provider}")
        asr = get_asr_provider(settings)
        original_text = asr.transcribe(req.audio)
        logger.debug(f"[Transcribe] ASR 结果: {original_text[:100]}")

        # Step 3: 风格化（provider 由配置决定）
        logger.debug(f"[Transcribe] Step 3: 调用 Stylizer provider: {settings.llm_provider}")
        stylizer = get_stylizer(settings)
        styled_text = stylizer.stylize(original_text, style=req.style, prompt=req.prompt)
        logger.debug(f"[Transcribe] 风格化结果: {styled_text[:100]}")

        elapsed = int((time.time() - start) * 1000)
        logger.info(f"[Transcribe] 成功完成，耗时: {elapsed}ms")

        return TranscribeResponse(
            text=styled_text,
            original_text=original_text,
            duration_ms=elapsed,
            error=None,
        )

    except (ASRError, StylizeError) as exc:
        # 可预期错误：返回结构化失败，文本留空，附带 error 说明
        elapsed = int((time.time() - start) * 1000)
        error_msg = str(exc)
        logger.warning(f"[Transcribe] 处理失败 ({type(exc).__name__}): {error_msg}, 耗时: {elapsed}ms")
        return TranscribeResponse(
            text="",
            original_text="",
            duration_ms=elapsed,
            error=error_msg,
        )
