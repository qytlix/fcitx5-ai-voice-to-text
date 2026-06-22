"""
ASR（自动语音识别）层：音频 → 文字。

设计为「可插拔 provider」：
    - ASRProvider 是抽象基类，定义统一接口 transcribe()。
    - MockASRProvider 是原型阶段的假实现（不调用外部 API）。
    - XunfeiASRProvider 是讯飞「语音听写（流式版）v2」API 实现。
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
import threading
import time
from abc import ABC, abstractmethod
from email.utils import formatdate
from urllib.parse import quote

import websocket

from .audio_utils import AudioProcessError, decode_audio
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
    讯飞「语音听写（流式版）v2」WebSocket API 实现。

    接口: wss://iat-api.xfyun.cn/v2/iat
    文档: https://www.xfyun.cn/doc/asr/voicedictation/API.html

    说明：此前用的是「中文识别大模型」接口（iat.xf-yun.com/v1，domain=slm），
    但该服务需单独开通授权，未开通会返回 11201 licc failed。改用「语音听写
    流式版」接口，对应控制台「语音听写」服务（默认带免费额度）。

    配置项（来自 .env，字段名沿用历史命名）：
        - ASR_API_KEY: APP_ID
        - ASR_API_SECRET: APIKey
        - ASR_API_PASSWORD: APISecret
    """

    XUNFEI_HOST = "iat-api.xfyun.cn"
    XUNFEI_PATH = "/v2/iat"
    XUNFEI_URL = f"wss://{XUNFEI_HOST}{XUNFEI_PATH}"

    # 流式分包：40ms/次，每次 1280 字节（16kHz 16bit 单声道）
    FRAME_SIZE = 1280
    INTERVAL_MS = 40
    MAX_AUDIO_SECONDS = 60
    RECV_TIMEOUT = 30

    def __init__(self, app_id: str, api_key: str, api_secret: str):
        """
        初始化讯飞 IAT ASR。

        Args:
            app_id: 讯飞应用 APPID
            api_key: 讯飞 APIKey
            api_secret: 讯飞 APISecret
        """
        if not all([app_id, api_key, api_secret]):
            raise ASRError(
                "讯飞 IAT 认证信息不完整。"
                "请在 .env 中配置 ASR_API_KEY (APPID), "
                "ASR_API_SECRET (APIKey), ASR_API_PASSWORD (APISecret)"
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
        调用讯飞 IAT v2 WebSocket API 完整流程。

        1. 建立 wss 连接并握手鉴权；
        2. 起一个接收线程边收结果（动态修正 wpgs）；
        3. 主线程按首帧 / 中间帧 / 末帧分包发送音频；
        4. 汇总各分片结果，按序号拼接成最终文本。

        采用「边发边收」而非「先发完再收」：讯飞在识别过程中就会持续推送结果，
        若中途出错也会立即下推错误码并关闭连接，独立接收线程能拿到错误原因，
        而不是让发送循环因连接被中止抛出无意义的 WinError 10053。
        """
        if sample_rate not in (8000, 16000):
            raise ASRError(
                f"讯飞 IAT 仅支持 8kHz 或 16kHz 采样率，当前为 {sample_rate}Hz"
            )

        max_bytes = self.MAX_AUDIO_SECONDS * sample_rate * 2
        if len(pcm_bytes) > max_bytes:
            raise ASRError(f"音频时长超过 {self.MAX_AUDIO_SECONDS} 秒限制")

        ws_url = self._build_auth_url()
        ws = websocket.create_connection(
            ws_url,
            sslopt={"cert_reqs": ssl.CERT_NONE},
            timeout=self.RECV_TIMEOUT,
        )

        try:
            return self._stream(ws, pcm_bytes, sample_rate)
        finally:
            try:
                ws.close()
            except Exception:
                pass

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

    def _stream(
        self, ws: websocket.WebSocket, pcm_bytes: bytes, sample_rate: int
    ) -> str:
        """边发边收：发送音频帧的同时由接收线程收集识别结果。"""
        # sn（结果序号）→ 该片文本。用 dict 以便动态修正（wpgs）时按序号替换。
        result_map: dict[int, str] = {}
        error: dict[str, str] = {}
        audio_format = f"audio/L16;rate={sample_rate}"

        def receiver() -> None:
            while True:
                try:
                    message = ws.recv()
                except websocket.WebSocketTimeoutException:
                    error["msg"] = "等待识别结果超时"
                    return
                except Exception as e:
                    # 连接被服务端关闭等：若已收到错误码则以错误码为准
                    if not error:
                        error["msg"] = f"接收识别结果失败: {e}"
                    return

                if not message:
                    return
                if isinstance(message, bytes):
                    message = message.decode("utf-8")

                data = json.loads(message)
                code = data.get("code")
                if code != 0:
                    error["msg"] = (
                        f"识别错误: code={code}, "
                        f"message={data.get('message', '未知错误')}"
                    )
                    return

                payload = data.get("data") or {}
                result = payload.get("result") or {}
                if result:
                    self._merge_result(result, result_map)

                if payload.get("status") == 2:
                    return

        rt = threading.Thread(target=receiver, daemon=True)
        rt.start()

        try:
            self._send_audio_frames(ws, pcm_bytes, audio_format)
        except Exception as e:
            # 发送中断时，优先让接收线程把服务端的错误原因拿回来
            rt.join(timeout=self.RECV_TIMEOUT)
            if error:
                raise ASRError(error["msg"])
            raise ASRError(f"发送音频失败: {e}")

        rt.join(timeout=self.RECV_TIMEOUT)
        if rt.is_alive():
            raise ASRError("等待识别结果超时")
        if error:
            raise ASRError(error["msg"])

        return "".join(result_map[sn] for sn in sorted(result_map))

    def _send_audio_frames(
        self, ws: websocket.WebSocket, pcm_bytes: bytes, audio_format: str
    ) -> None:
        """按 v2 协议分包发送音频帧（首帧带参数，末帧 status=2）。"""
        total = len(pcm_bytes)

        # 首帧：携带 common / business 参数
        first_frame = {
            "common": {"app_id": self.app_id},
            "business": {
                "language": "zh_cn",
                "domain": "iat",
                "accent": "mandarin",
                "vad_eos": 10000,
                "dwa": "wpgs",  # 开启动态修正
            },
            "data": {
                "status": 0,
                "format": audio_format,
                "encoding": "raw",
                "audio": base64.b64encode(pcm_bytes[: self.FRAME_SIZE]).decode(
                    "utf-8"
                ),
            },
        }
        ws.send(json.dumps(first_frame))

        # 中间帧 / 末帧
        offset = self.FRAME_SIZE
        while offset < total:
            end = min(offset + self.FRAME_SIZE, total)
            is_last = end == total
            status = 2 if is_last else 1
            frame = {
                "data": {
                    "status": status,
                    "format": audio_format,
                    "encoding": "raw",
                    "audio": base64.b64encode(pcm_bytes[offset:end]).decode(
                        "utf-8"
                    ),
                }
            }
            ws.send(json.dumps(frame))
            offset = end
            if not is_last:
                time.sleep(self.INTERVAL_MS / 1000)

        # 音频不足一帧（首帧已发完整段）时，补一个空末帧通知结束
        if total <= self.FRAME_SIZE:
            ws.send(
                json.dumps(
                    {
                        "data": {
                            "status": 2,
                            "format": audio_format,
                            "encoding": "raw",
                            "audio": "",
                        }
                    }
                )
            )

    def _merge_result(self, result: dict, result_map: dict[int, str]) -> None:
        """
        把一片识别结果并入 result_map，处理动态修正（wpgs）。

        - pgs="apd"：追加，直接以 sn 存入；
        - pgs="rpl"：替换 rg=[start, end] 范围内的历史分片，再存入当前 sn。
        未开 wpgs 时没有 pgs 字段，按追加处理。
        """
        sn = result.get("sn")
        if sn is None:
            return

        text = self._extract_text(result)

        if result.get("pgs") == "rpl":
            rg = result.get("rg")
            if rg and len(rg) == 2:
                for i in range(rg[0], rg[1] + 1):
                    result_map.pop(i, None)

        result_map[sn] = text

    def _extract_text(self, result: dict) -> str:
        """从一片结果的 ws 字段提取字词文本。"""
        words = []
        for ws_item in result.get("ws", []):
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
    logger.info(f"[ASR Factory] provider_name={provider_name!r}")

    if provider_name == "xunfei":
        logger.info("[ASR Factory] Creating XunfeiASRProvider")
        return XunfeiASRProvider(
            app_id=settings.asr_api_key or "",
            api_key=settings.asr_api_secret or "",
            api_secret=settings.asr_api_password or "",
        )

    # 回退到 mock
    logger.info("[ASR Factory] Creating MockASRProvider")
    return MockASRProvider()
