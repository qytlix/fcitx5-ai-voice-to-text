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
import logging
import re
from abc import ABC, abstractmethod
from typing import Optional

from openai import OpenAI

from .config import Settings
from .models import Style

logger = logging.getLogger(__name__)


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


class DeepSeekStylizer(Stylizer):
    """真实 LLM 风格化：调用 DeepSeek API（v4flash 模型）。"""

    def __init__(self, api_key: str):
        self.client = OpenAI(
            api_key=api_key,
            base_url="https://api.deepseek.com",
        )

    def stylize(self, text: str, style: Style, prompt: Optional[str] = None) -> str:
        """使用 DeepSeek 进行风格化。"""
        style_prompt = self._build_style_prompt(text, style, prompt)

        try:
            response = self.client.chat.completions.create(
                model="deepseek-chat",
                messages=[{"role": "user", "content": style_prompt}],
                temperature=0.3,
                max_tokens=500,
            )
            result = response.choices[0].message.content.strip()
            logger.debug(f"[DeepSeek] 风格化完成: {result[:100]}")
            return result
        except Exception as e:
            error_msg = f"DeepSeek API 调用失败: {str(e)}"
            logger.error(f"[DeepSeek] {error_msg}")
            raise StylizeError(error_msg)

    def _build_style_prompt(self, text: str, style: Style, custom_prompt: Optional[str]) -> str:
        """根据风格构造 prompt。"""
        style_instructions = {
            Style.FORMAL: "请将以下文本转换为正式书面语，移除口语表达和语气词，使用规范的词汇和表达方式。",
            Style.CONCISE: "请精简以下文本，保留核心信息，移除冗余和修饰词。",
            Style.POLITE: "请使用礼貌用语重新表述以下文本，加入敬语（如"您"），表现出尊重和礼貌。",
            Style.TRANSLATE_EN: "请将以下中文文本翻译成英文，保留原意。",
            Style.CUSTOM: f"请按照以下要求处理文本：{custom_prompt or '无特殊要求'}",
        }

        instruction = style_instructions.get(style, "请处理以下文本。")
        return f"{instruction}\n\n文本：{text}"


# provider 名称 → 构造函数。新增真实 LLM provider 时在此注册。
_PROVIDERS = {
    "mock": RuleBasedStylizer,
    "deepseek": DeepSeekStylizer,
}


def get_stylizer(settings: Settings) -> Stylizer:
    """根据配置返回 Stylizer 实例。"""
    if settings.llm_provider == "deepseek":
        if not settings.llm_api_key:
            raise StylizeError(
                "DeepSeek provider 已启用但 LLM_API_KEY 未配置。"
                "请在 .env 中设置 LLM_API_KEY。"
            )
        return DeepSeekStylizer(api_key=settings.llm_api_key)

    # 回退到 mock 或其他 provider
    provider_cls = _PROVIDERS.get(settings.llm_provider, RuleBasedStylizer)
    return provider_cls()
