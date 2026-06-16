"""
ASR（自动语音识别）层：音频 → 文字。

设计为「可插拔 provider」：
    - ASRProvider 是抽象基类，定义统一接口 transcribe()。
    - MockASRProvider 是原型阶段的假实现（不调用外部 API）。
    - get_asr_provider() 是工厂，根据配置里的 asr_provider 返回对应实例。

接入真实 ASR（讯飞 / Whisper / 阿里云）时，只需：
    1. 新增一个继承 ASRProvider 的类，实现 transcribe()；
    2. 在 _PROVIDERS 里注册；
    3. 把 .env 的 ASR_PROVIDER 改成新名字。
核心调用方（pipeline）完全不用改。
"""

from abc import ABC, abstractmethod

from .config import Settings


class ASRError(Exception):
    """ASR 过程中的可预期错误（如音频无法解码）。由 pipeline 捕获转成响应里的 error。"""


class ASRProvider(ABC):
    """ASR 提供方的统一接口。"""

    @abstractmethod
    def transcribe(self, audio_base64: str) -> str:
        """把 base64 音频转写为文字。失败时抛 ASRError。"""
        raise NotImplementedError


class MockASRProvider(ASRProvider):
    """
    原型阶段的固定实现。

    不真正识别音频，仅按 base64 长度返回预设的口语句子，
    用于把整条流水线（路由 → ASR → 风格化 → 响应）跑通。
    """

    def transcribe(self, audio_base64: str) -> str:
        audio_len = len(audio_base64)

        if audio_len < 100:
            return "好的"
        elif audio_len < 500:
            return "那个…下午三点有个会议你记得参加一下"
        elif audio_len < 2000:
            return "我觉得这个方案还可以但是有些地方需要再改一下比如那个用户体验的部分"
        else:
            return (
                "嗯…关于刚才讨论的那个项目我觉得我们可以考虑从几个方面来改进"
                "首先用户体验方面需要重新设计一下其次性能优化也很重要"
                "然后还有那个测试覆盖率的问题"
            )


# provider 名称 → 构造函数。新增真实 provider 时在此注册。
_PROVIDERS = {
    "mock": MockASRProvider,
}


def get_asr_provider(settings: Settings) -> ASRProvider:
    """根据配置返回 ASR provider 实例。未知名称时回退到 mock 并不报错。"""
    provider_cls = _PROVIDERS.get(settings.asr_provider, MockASRProvider)
    return provider_cls()
