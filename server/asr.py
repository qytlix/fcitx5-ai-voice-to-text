"""
固定 ASR 模块（原型阶段）

不调用外部 API，直接返回模拟的转写结果。
后续替换为真实 ASR 调用（讯飞 / 阿里云 / Whisper 等）。
"""

from typing import Optional


def transcribe(audio_base64: str) -> str:
    """
    将 base64 音频转写为文字。

    当前为固定实现，返回模拟文本。
    后续实现：解码音频 → 调用 ASR API → 返回文字。
    """
    # 模拟：根据音频数据长度返回不同文本
    audio_len = len(audio_base64)

    if audio_len < 100:
        return "好的"
    elif audio_len < 500:
        return "那个…下午三点有个会议你记得参加一下"
    elif audio_len < 2000:
        return "我觉得这个方案还可以但是有些地方需要再改一下比如那个用户体验的部分"
    else:
        return "嗯…关于刚才讨论的那个项目我觉得我们可以考虑从几个方面来改进首先用户体验方面需要重新设计一下其次性能优化也很重要然后还有那个测试覆盖率的问题"
