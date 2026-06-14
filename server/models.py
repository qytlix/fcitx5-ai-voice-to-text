from pydantic import BaseModel
from typing import Optional


class TranscribeRequest(BaseModel):
    """客户端发送的转录请求"""
    audio: str  # base64 编码的音频数据
    prompt: Optional[str] = None  # 自定义提示词
    sample: Optional[str] = None  # 用户提供的样例文本
    style: str = "正式"  # 风格：正式 / 精简 / 礼貌 / 翻译_英文 / 自定义


class TranscribeResponse(BaseModel):
    """服务端返回的转录结果"""
    text: str  # 风格化后的文字
    original_text: str  # ASR 原始转写文字
    duration_ms: Optional[int] = None  # 处理耗时
    error: Optional[str] = None  # 错误信息