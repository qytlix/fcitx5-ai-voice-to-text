"""
数据模型层。

定义 HTTP 请求 / 响应的结构，以及「风格」枚举。
所有进出服务的 JSON 都由这里的 Pydantic 模型校验，
非法字段会在进入业务逻辑前就被 FastAPI 拦下并返回 422。
"""

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class Style(str, Enum):
    """
    支持的风格化风格。

    用枚举而非裸字符串：客户端传入未定义的值时，
    FastAPI 会直接返回 422 并列出合法取值，而不是悄悄走 default 分支。
    继承 str 是为了让它在 JSON 里仍序列化成普通字符串。
    """

    FORMAL = "正式"        # 口语 → 书面语，去语气词
    CONCISE = "精简"       # 去冗余，保留核心
    POLITE = "礼貌"        # 调整语气，加敬语
    TRANSLATE_EN = "翻译_英文"  # 翻译为英文
    CUSTOM = "自定义"      # 由 prompt 自由定义


class TranscribeRequest(BaseModel):
    """客户端 → 服务端：转录请求。"""

    audio: str = Field(..., description="base64 编码的音频数据（PCM / WAV）")
    prompt: Optional[str] = Field(None, description="自定义风格时的提示词")
    sample: Optional[str] = Field(None, description="用户提供的样例文本（预留）")
    style: Style = Field(Style.FORMAL, description="风格化风格")


class TranscribeResponse(BaseModel):
    """服务端 → 客户端：转录结果。"""

    text: str = Field(..., description="风格化后的最终文字")
    original_text: str = Field(..., description="ASR 原始转写文字")
    duration_ms: Optional[int] = Field(None, description="服务端处理耗时（毫秒）")
    error: Optional[str] = Field(None, description="错误信息，成功时为 null")
