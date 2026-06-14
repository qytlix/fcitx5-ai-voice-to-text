"""
固定风格化模块（原型阶段）

不调用外部 LLM API，直接用规则做简单的风格转换。
后续替换为真实 LLM 调用。
"""

import re
from typing import Optional


# 语气词集合
FILLER_WORDS = {"嗯", "那个", "这个", "就是", "然后", "其实", "反正", "那个那个"}


def stylize(text: str, style: str = "正式", prompt: Optional[str] = None) -> str:
    """
    对 ASR 转写文本进行风格化处理。

    当前为固定实现，使用规则做简单转换。
    后续实现：调用 LLM API 进行真实风格化。
    """
    if style == "正式":
        return _to_formal(text)
    elif style == "精简":
        return _to_concise(text)
    elif style == "礼貌":
        return _to_polite(text)
    elif style == "翻译_英文":
        return _to_english(text)
    elif style == "自定义":
        return _custom_style(text, prompt)
    else:
        return text


def _remove_fillers(text: str) -> str:
    """去除语气词和冗余连接词"""
    for word in FILLER_WORDS:
        text = text.replace(word, "")
    # 去除多余空格和重复标点
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"[，,]+", "，", text)
    text = re.sub(r"[。.]+", "。", text)
    return text


def _to_formal(text: str) -> str:
    """口语 → 正式书面语"""
    text = _remove_fillers(text)

    # 简单规则替换
    replacements = {
        "觉得": "认为",
        "可以": "可以",
        "改一下": "进行调整",
        "搞一下": "进行处理",
        "弄一下": "进行处理",
        "看一下": "进行审查",
        "那个": "",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)

    # 补全标点
    if text and not text.endswith(("。", "！", "？")):
        text += "。"

    return text


def _to_concise(text: str) -> str:
    """精简：去冗余，保留核心"""
    text = _remove_fillers(text)

    # 去掉修饰性词语
    text = re.sub(r"我觉得|我认为|我个人觉得|在我看来", "", text)
    text = re.sub(r"其实|反正|怎么说呢|就是说|可以说是", "", text)
    text = re.sub(r"\s+", " ", text).strip()

    if text and not text.endswith(("。", "！", "？")):
        text += "。"

    return text


def _to_polite(text: str) -> str:
    """礼貌：添加敬语"""
    text = _remove_fillers(text)

    if text.startswith("你"):
        text = "您" + text[1:]
    text = text.replace("你", "您")
    text = text.replace("帮我", "麻烦您帮我")
    text = text.replace("我要", "我想")

    if not text.startswith(("请", "麻烦", "您好")):
        text = f"您好，{text}"

    if text and not text.endswith(("。", "！", "？")):
        text += "。谢谢！"

    return text


def _to_english(text: str) -> str:
    """模拟翻译为英文"""
    # 固定映射（原型用）
    mock_translations = {
        "好的": "Okay.",
        "下午三点有个会议": "There is a meeting at 3 PM.",
        "我觉得这个方案还可以": "I think this plan is acceptable.",
    }

    for zh, en in mock_translations.items():
        if zh in text:
            return en

    return f"[English translation]: {text}"


def _custom_style(text: str, prompt: Optional[str]) -> str:
    """自定义风格：根据 prompt 做简单处理"""
    text = _remove_fillers(text)

    if prompt and "json" in prompt.lower():
        import json as _json
        return _json.dumps({"text": text, "note": "custom styled"}, ensure_ascii=False)

    if prompt and "大写" in prompt:
        return text.upper()

    return f"[{prompt or '自定义'}]: {text}"
