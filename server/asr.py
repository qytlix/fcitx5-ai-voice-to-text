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
import ssl
import time
from abc import ABC, abstractmethod
from email.utils import formatdate
from typing import Optional
from urllib.parse import quote

import websocket

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
    讯飞 IAT（语音听写流式版）WebSocket API 实现。

    文档: xfyun使用说明.md

    配置项（来自 .env）：
        - ASR_API_KEY: APP_ID
        - ASR_API_SECRET: API_KEY
        - ASR_API_PASSWORD: API_SECRET
    """

    XUNFEI_HOST = "iat.xf-yun.com"
    XUNFEI_PATH = "/v1"
    XUNFEI_URL = f"wss://{XUNFEI_HOST}{XUNFEI_PATH}"

    # 流式分包：40ms/次，每次 1280 字节（16kHz 16bit 单声道）
    FRAME_SIZE = 1280
    INTERVAL_MS = 40
    MAX_AUDIO_SECONDS = 60

    def __init__(self, app_id: str, api_key: str, api_secret: str):
        """
        初始化讯飞 IAT ASR。

        Args:
            app_id: 讯飞应用 ID
            api_key: 讯飞 API Key
            api_secret: 讯飞 API Secret
        """
        if not all([app_id, api_key, api_secret]):
            raise ASRError(
                "讯飞 IAT 认证信息不完整。"
                "请在 .env 中配置 ASR_API_KEY (app_id), "
                "ASR_API_SECRET (api_key), ASR_API_PASSWORD (api_secret)"
            )
        self.app_id = app_id
        self.api_key = api_key
        self.api_secret = api_secret

    def transcribe(self, audio_base64: str) -> str:
        """调用讯飞 IAT WebSocket API 进行语音识别。"""
        try:
            pcm_bytes, sample_rate = decode_audio(audio_base64)
            logger.debug(
                f"[Xunfei] 解码音频完成: {len(pcm_bytes)} bytes @ {sample_rate} Hz"
            )

            result = self._call_xunfei_iat_api(pcm_bytes, sample_rate)
            logger.info(f"[Xunfei] 识别完成: {result[:100]}")
            return result

        except AudioProcessError as e:
            raise ASRError(f"音频解码失败: {e}")
        except ASRError:
            raise
        except websocket.WebSocketException as e:
            raise ASRError(f"讯飞 WebSocket 连接失败: {e}")
        except Exception as e:
            raise ASRError(f"讯飞 IAT 调用失败: {e}")

    def _call_xunfei_iat_api(self, pcm_bytes: bytes, sample_rate: int) -> str:
        """
        调用讯飞 IAT WebSocket API 完整流程。

        1. 建立 wss 连接并握手鉴权；
        2. 按首帧 / 中间帧 / 末帧分包发送音频；
        3. 接收服务端推送的识别结果并拼接文本。
        """
        if sample_rate not in (8000, 16000):
            raise ASRError(
                f"讯飞 IAT 仅支持 8kHz 或 16kHz 采样率，当前为 {sample_rate}Hz"
            )

        max_bytes = self.MAX_AUDIO_SECONDS * sample_rate * 2
        if len(pcm_bytes) > max_bytes:
            raise ASRError(
                f"音频时长超过 {self.MAX_AUDIO_SECONDS} 秒限制"
            )

        ws_url = self._build_auth_url()
        ws = websocket.create_connection(
            ws_url,
            sslopt={"cert_reqs": ssl.CERT_NONE},
            timeout=30,
        )

        try:
            self._send_audio_frames(ws, pcm_bytes, sample_rate)
            return self._receive_results(ws)
        finally:
            ws.close()

    def _build_auth_url(self) -> str:
        """按文档生成带鉴权参数的 WebSocket URL。"""
        date = formatdate(timeval=None, localtime=False, usegmt=True)

        signature_origin = (
            f"host: {self.XUNFEI_HOST}\n"
            f"date: {date}\n"
            f"GET {self.XUNFEI_PATH} HTTP/1.1"
        )
        signature_sha = hmac.new(
            self.api_secret.encode("utf-8"),
            signature_origin.encode("utf-8"),
            hashlib.sha256,
        ).digest()
        signature = base64.b64encode(signature_sha).decode("utf-8")

        authorization_origin = (
            f'api_key="{self.api_key}", '
            f'algorithm="hmac-sha256", '
            f'headers="host date request-line", '
            f'signature="{signature}"'
        )
        authorization = base64.b64encode(
            authorization_origin.encode("utf-8")
        ).decode("utf-8")

        return (
            f"{self.XUNFEI_URL}?"
            f"authorization={authorization}"
            f"&date={quote(date, safe='')}"
            f"&host={self.XUNFEI_HOST}"
        )

    def _send_audio_frames(
        self, ws: websocket.WebSocket, pcm_bytes: bytes, sample_rate: int
    ) -> None:
        """按文档规范分包发送音频帧。"""
        seq = 1
        total = len(pcm_bytes)

        # 首帧：携带服务参数
        first_frame = {
            "header": {
                "app_id": self.app_id,
                "status": 0,
            },
            "parameter": {
                "iat": {
                    "domain": "slm",
                    "language": "zh_cn",
                    "accent": "mandarin",
                    "eos": 6000,
                    "dwa": "wpgs",
                    "result": {
                        "encoding": "utf8",
                        "compress": "raw",
                        "format": "json",
                    },
                }
            },
            "payload": {
                "audio": {
                    "encoding": "raw",
                    "sample_rate": sample_rate,
                    "channels": 1,
                    "bit_depth": 16,
                    "seq": seq,
                    "status": 0,
                    "audio": base64.b64encode(
                        pcm_bytes[: self.FRAME_SIZE]
                    ).decode("utf-8"),
                }
            },
        }
        ws.send(json.dumps(first_frame))
        seq += 1

        # 中间帧
        offset = self.FRAME_SIZE
        while offset < total:
            end = min(offset + self.FRAME_SIZE, total)
            is_last = end == total
            status = 2 if is_last else 1

            frame = {
                "header": {"app_id": self.app_id, "status": status},
                "payload": {
                    "audio": {
                        "encoding": "raw",
                        "sample_rate": sample_rate,
                        "channels": 1,
                        "bit_depth": 16,
                        "seq": seq,
                        "status": status,
                        "audio": base64.b64encode(pcm_bytes[offset:end]).decode(
                            "utf-8"
                        ),
                    }
                },
            }
            ws.send(json.dumps(frame))
            seq += 1
            offset = end

            if not is_last:
                time.sleep(self.INTERVAL_MS / 1000)

        # 如果音频为空或首帧已发完，补发空末帧
        if total == 0 or total <= self.FRAME_SIZE:
            last_frame = {
                "header": {"app_id": self.app_id, "status": 2},
                "payload": {
                    "audio": {
                        "encoding": "raw",
                        "sample_rate": sample_rate,
                        "channels": 1,
                        "bit_depth": 16,
                        "seq": seq,
                        "status": 2,
                        "audio": "",
                    }
                },
            }
            ws.send(json.dumps(last_frame))

    def _receive_results(self, ws: websocket.WebSocket) -> str:
        """接收识别结果，处理动态修正（wpgs）并拼接最终文本。"""
        result_parts: list[str] = []
        while True:
            try:
                message = ws.recv()
            except websocket.WebSocketTimeoutException:
                raise ASRError("等待识别结果超时")
            
            if isinstance(message, bytes):
                message = message.decode("utf-8")

            data = json.loads(message)
            header = data.get("header", {})
            code = header.get("code")
            if code != 0:
                raise ASRError(
                    f"识别错误: code={code}, message={header.get('message', '未知错误')}"
                )

            status = header.get("status")
            payload = data.get("payload", {})
            result = payload.get("result", {})

            if "text" in result:
                text_b64 = result["text"]
                text_json = base64.b64decode(text_b64).decode("utf-8")
                text_data = json.loads(text_json)

                partial = self._extract_text(text_data)
                pgs = text_data.get("pgs")

                if pgs == "rpl":
                    # 替换前面若干段结果
                    rg = text_data.get("rg", [1, len(result_parts)])
                    start, end = max(0, rg[0] - 1), max(0, rg[1])
                    result_parts = result_parts[:start]
                    result_parts.append(partial)
                else:
                    # apd 或普通结果，直接追加
                    result_parts.append(partial)

            if status == 2:
                return "".join(result_parts)

    def _extract_text(self, text_data: dict) -> str:
        """从 text 字段解码后的 JSON 中提取字词文本。"""
        words = []
        for ws_item in text_data.get("ws", []):
            for cw in ws_item.get("cw", []):
                w = cw.get("w")
                if w:
                    words.append(w)
        return "".join(words)


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
