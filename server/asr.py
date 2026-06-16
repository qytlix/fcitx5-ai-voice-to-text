"""
ASR（自动语音识别）层：音频 → 文字。

设计为「可插拔 provider」：
    - ASRProvider 是抽象基类，定义统一接口 transcribe()。
    - MockASRProvider 是原型阶段的假实现（不调用外部 API）。
    - XunfeiASRProvider 是讯飞 IAT API 实现。
    - get_asr_provider() 是工厂，根据配置里的 asr_provider 返回对应实例。

接入真实 ASR 时，只需：
    1. 新增一个继承 ASRProvider 的类，实现 transcribe()；
    2. 在 _PROVIDERS 里注册；
    3. 把 .env 的 ASR_PROVIDER 改成新名字。
核心调用方（pipeline）完全不用改。
"""

import base64
import hashlib
import hmac
import json
import logging
import time
from abc import ABC, abstractmethod
from datetime import datetime
from typing import Optional
from urllib.parse import urlencode

import requests

from .audio_utils import AudioProcessError, decode_audio, get_audio_duration_ms
from .config import Settings

logger = logging.getLogger(__name__)


class ASRError(Exception):
    """ASR 过程中的可预期错误。由 pipeline 捕获转成响应里的 error。"""


class ASRProvider(ABC):
    """ASR 提供方的统一接口。"""

    @abstractmethod
    def transcribe(self, audio_base64: str) -> str:
        """把 base64 音频转写为文字。失败时抛 ASRError。"""
        raise NotImplementedError


class MockASRProvider(ASRProvider):
    """
    原型阶段的固定实现。

    不真正识别音频，仅按 base64 长度返回预设的口语句子。
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


class XunfeiASRProvider(ASRProvider):
    """
    讯飞 RAASR（录音文件转写）HTTP REST API 实现。

    文档: https://www.xfyun.cn/doc/asr/voicetranscrip_api.html

    配置项（来自 .env）：
        - ASR_API_KEY: APP_ID
        - ASR_API_SECRET: API_KEY
        - ASR_API_PASSWORD: API_SECRET
    """

    # 讯飞 RAASR API 端点
    XUNFEI_UPLOAD_URL = "https://raasr.xfyun.cn/v2/api/upload"
    XUNFEI_QUERY_URL = "https://raasr.xfyun.cn/v2/api/getResult"

    def __init__(self, app_id: str, api_key: str, api_secret: str):
        """
        初始化讯飞 RAASR ASR。

        Args:
            app_id: 讯飞应用 ID
            api_key: 讯飞 API Key
            api_secret: 讯飞 API Secret
        """
        if not all([app_id, api_key, api_secret]):
            raise ASRError(
                "讯飞 RAASR 认证信息不完整。"
                "请在 .env 中配置 ASR_API_KEY (app_id), "
                "ASR_API_SECRET (api_key), ASR_API_PASSWORD (api_secret)"
            )
        self.app_id = app_id
        self.api_key = api_key
        self.api_secret = api_secret

    def transcribe(self, audio_base64: str) -> str:
        """调用讯飞 RAASR API 进行语音识别。"""
        try:
            # 解码音频（验证格式、获取 PCM 数据）
            pcm_array, sample_rate = decode_audio(audio_base64)
            logger.debug(f"[Xunfei] 解码音频完成: {len(pcm_array)} samples @ {sample_rate} Hz")

            # 获取识别用的原始音频（讯飞需要原始的 bytes）
            raw_bytes = base64.b64decode(audio_base64)

            # 调用 RAASR API（上传 → 查询）
            result = self._call_xunfei_raasr_api(raw_bytes)
            logger.info(f"[Xunfei] 识别完成: {result[:100]}")
            return result

        except AudioProcessError as e:
            raise ASRError(f"音频解码失败: {e}")
        except Exception as e:
            raise ASRError(f"讯飞 RAASR 调用失败: {e}")

    def _call_xunfei_raasr_api(self, audio_bytes: bytes) -> str:
        """
        调用讯飞 RAASR API 完整流程：上传 → 查询。

        讯飞 RAASR 采用异步模式：先上传音频文件，获得 file_id，然后轮询查询结果。
        """
        # 步骤 1: 上传音频文件
        logger.debug(f"[Xunfei] 上传音频文件 ({len(audio_bytes)} bytes)")
        upload_result = self._upload_audio(audio_bytes)
        file_id = upload_result.get("file_id")
        order_id = upload_result.get("order_id")

        if not file_id or not order_id:
            raise ASRError(f"上传失败：未获得 file_id/order_id")

        logger.debug(f"[Xunfei] 上传成功: file_id={file_id}, order_id={order_id}")

        # 步骤 2: 轮询查询结果
        max_retries = 120  # 最多等待 120 秒（每秒查询一次）
        for attempt in range(max_retries):
            query_result = self._query_result(file_id, order_id)
            status = query_result.get("status")

            if status == 9:  # 识别完成
                text = query_result.get("lattice", "")
                if text:
                    logger.debug(f"[Xunfei] 识别完成 (attempt {attempt + 1})")
                    return text
                else:
                    raise ASRError("识别完成但返回空文本")

            elif status in [0, 1, 2]:  # 处理中
                if attempt % 10 == 0:
                    logger.debug(f"[Xunfei] 处理中... (attempt {attempt + 1})")
                time.sleep(1)

            else:  # 其他错误状态
                raise ASRError(f"识别失败: status={status}, {query_result.get('failreason', '')}")

        raise ASRError("识别超时（120秒内未返回结果）")

    def _upload_audio(self, audio_bytes: bytes) -> dict:
        """上传音频文件到讯飞。"""
        timestamp = str(int(time.time()))
        auth_header = self._generate_auth_header("POST", self.XUNFEI_UPLOAD_URL, timestamp)

        headers = {
            "Authorization": auth_header,
            "X-Appid": self.app_id,
            "Content-Type": "application/octet-stream",
        }

        # 构造请求参数
        params = {
            "app_id": self.app_id,
            "file_len": len(audio_bytes),
            "file_name": "audio.wav",
            "slice_num": 1,
            "slice_id": 0,
        }

        try:
            response = requests.post(
                self.XUNFEI_UPLOAD_URL,
                params=params,
                data=audio_bytes,
                headers=headers,
                timeout=30,
            )
            response.raise_for_status()

            result_data = response.json()
            logger.debug(f"[Xunfei] 上传响应: {result_data}")

            if result_data.get("code") != 0:
                error_msg = result_data.get("message", "未知错误")
                raise ASRError(f"上传失败: {error_msg}")

            return {
                "file_id": result_data.get("data", {}).get("file_id"),
                "order_id": result_data.get("data", {}).get("order_id"),
            }

        except requests.RequestException as e:
            raise ASRError(f"上传网络请求失败: {e}")

    def _query_result(self, file_id: str, order_id: str) -> dict:
        """查询识别结果。"""
        timestamp = str(int(time.time()))
        auth_header = self._generate_auth_header("POST", self.XUNFEI_QUERY_URL, timestamp)

        headers = {
            "Authorization": auth_header,
            "X-Appid": self.app_id,
            "Content-Type": "application/x-www-form-urlencoded",
        }

        params = {
            "app_id": self.app_id,
            "file_id": file_id,
            "order_id": order_id,
            "status": 3,  # 3 表示查询识别结果
        }

        try:
            response = requests.post(
                self.XUNFEI_QUERY_URL,
                params=params,
                headers=headers,
                timeout=30,
            )
            response.raise_for_status()

            result_data = response.json()
            logger.debug(f"[Xunfei] 查询响应: status={result_data.get('code')}")

            if result_data.get("code") != 0:
                error_msg = result_data.get("message", "未知错误")
                raise ASRError(f"查询失败: {error_msg}")

            return result_data.get("data", {})

        except requests.RequestException as e:
            raise ASRError(f"查询网络请求失败: {e}")

    def _generate_auth_header(self, method: str, url: str, timestamp: str) -> str:
        """
        生成讯飞认证头（HMAC-SHA1）。

        对于 RAASR API，签名内容为：
        method + \n + url_path + \n + app_id + timestamp
        """
        # 提取 URL path
        from urllib.parse import urlparse
        parsed = urlparse(url)
        url_path = parsed.path

        # 构造签名字符串
        sign_str = f"{method}\n{url_path}\n{self.app_id}{timestamp}"

        # HMAC-SHA1 签名
        sign_bytes = hmac.new(
            self.api_secret.encode("utf-8"),
            sign_str.encode("utf-8"),
            hashlib.sha1,
        ).digest()

        # Base64 编码
        sign_b64 = base64.b64encode(sign_bytes).decode("utf-8")

        # 构造认证头
        auth_header = f"api_key=\"{self.api_key}\",algorithm=\"hmac-sha1\",timestamp=\"{timestamp}\",signature=\"{sign_b64}\""
        return auth_header


# provider 名称 → (构造函数, 参数列表)
_PROVIDERS = {
    "mock": (MockASRProvider, []),
    "xunfei": (XunfeiASRProvider, ["app_id", "api_key", "api_secret"]),
}


def get_asr_provider(settings: Settings) -> ASRProvider:
    """根据配置返回 ASR provider 实例。"""
    provider_name = settings.asr_provider

    if provider_name == "xunfei":
        return XunfeiASRProvider(
            app_id=settings.asr_api_key or "",
            api_key=settings.asr_api_secret or "",
            api_secret=settings.asr_api_password or "",
        )

    # 回退到 mock
    return MockASRProvider()
