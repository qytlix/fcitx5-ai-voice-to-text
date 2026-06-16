"""
风格化层：文字 → 风格化文字。

与 asr.py 同构的「可插拔 provider」设计：
    - Stylizer 是抽象基类，定义统一接口 stylize()。
    - RuleBasedStylizer 是原型阶段的规则实现（不调用 LLM）。
    - get_stylizer() 是工厂，根据配置里的 llm_provider 返回实例。

接入真实 LLM（DeepSeek / 通义千问 / Claude）时，新增一个继承 Stylizer 的类即可，
pipeline 调用方不变。
"""

import json
import re
from abc import ABC, abstractmethod
from typing import Optional

from .config import Settings
from .models import Style


class StylizeError(Exception):
    """风格化过程中的可预期错误。由 pipeline 捕获转成响应里的 error。"""


# 口语语气词 / 冗余连接词，正式与精简风格都会去除。
FILLER_WORDS = ("那个那个", "那个", "这个", "就是", "然后", "其实", "反正", "嗯")


def _remove_fillers(text: str) -> str:
    """去除语气词，并归并多余空格与重复标点。"""
    for word in FILLER_WORDS:
        text = text.replace(word, "")
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"[，,]+", "，", text)
    text = re.sub(r"[。.]+", "。", text)
    # 去词后句首可能残留省略号或标点（如 "嗯…关于…" → "…关于…"），一并清掉。
    text = text.lstrip("…．。.，,、　 ")
    return text


def _ensure_end_punct(text: str, tail: str = "。") -> str:
    """若结尾没有句末标点，补上 tail。"""
    if text and not text.endswith(("。", "！", "？")):
        text += tail
    return text


class Stylizer(ABC):
    """风格化提供方的统一接口。"""

    @abstractmethod
    def stylize(self, text: str, style: Style, prompt: Optional[str] = None) -> str:
        """按指定风格处理文本。失败时抛 StylizeError。"""
        raise NotImplementedError


class RuleBasedStylizer(Stylizer):
    """原型阶段的规则实现：不调用 LLM，用字符串规则做风格转换。"""

    def stylize(self, text: str, style: Style, prompt: Optional[str] = None) -> str:
        if style is Style.FORMAL:
            return self._to_formal(text)
        if style is Style.CONCISE:
            return self._to_concise(text)
        if style is Style.POLITE:
            return self._to_polite(text)
        if style is Style.TRANSLATE_EN:
            return self._to_english(text)
        if style is Style.CUSTOM:
            return self._custom(text, prompt)
        return text  # 理论上不会到这（枚举已校验），兜底原样返回

    def _to_formal(self, text: str) -> str:
        """口语 → 正式书面语。"""
        text = _remove_fillers(text)
        replacements = {
            "觉得": "认为",
            "改一下": "进行调整",
            "搞一下": "进行处理",
            "弄一下": "进行处理",
            "看一下": "进行审查",
        }
        for old, new in replacements.items():
            text = text.replace(old, new)
        return _ensure_end_punct(text)

    def _to_concise(self, text: str) -> str:
        """精简：去修饰、保留核心。"""
        text = _remove_fillers(text)
        text = re.sub(r"我觉得|我认为|我个人觉得|在我看来", "", text)
        text = re.sub(r"其实|反正|怎么说呢|就是说|可以说是", "", text)
        text = re.sub(r"\s+", " ", text).strip()
        return _ensure_end_punct(text)

    def _to_polite(self, text: str) -> str:
        """礼貌：加敬语。"""
        text = _remove_fillers(text)
        text = text.replace("你", "您")
        text = text.replace("帮我", "麻烦您帮我")
        text = text.replace("我要", "我想")
        if not text.startswith(("请", "麻烦", "您好")):
            text = f"您好，{text}"
        return _ensure_end_punct(text, tail="。谢谢！")

    def _to_english(self, text: str) -> str:
        """模拟翻译为英文（原型用固定映射）。"""
        mock_translations = {
            "好的": "Okay.",
            "下午三点有个会议": "There is a meeting at 3 PM.",
            "我觉得这个方案还可以": "I think this plan is acceptable.",
        }
        for zh, en in mock_translations.items():
            if zh in text:
                return en
        return f"[English translation]: {text}"

    def _custom(self, text: str, prompt: Optional[str]) -> str:
        """自定义：按 prompt 关键字做简单处理。"""
        text = _remove_fillers(text)
        if prompt and "json" in prompt.lower():
            return json.dumps({"text": text, "note": "custom styled"}, ensure_ascii=False)
        if prompt and "大写" in prompt:
            return text.upper()
        return f"[{prompt or '自定义'}]: {text}"


# provider 名称 → 构造函数。新增真实 LLM provider 时在此注册。
_PROVIDERS = {
    "mock": RuleBasedStylizer,
}


def get_stylizer(settings: Settings) -> Stylizer:
    """根据配置返回 Stylizer 实例。未知名称时回退到规则实现。"""
    provider_cls = _PROVIDERS.get(settings.llm_provider, RuleBasedStylizer)
    return provider_cls()
