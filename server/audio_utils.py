"""
音频处理工具：解码 base64 音频为 WAV/PCM。

使用标准库 wave 模块，无需 numpy。
"""

import base64
import io
import logging
import struct
import wave

logger = logging.getLogger(__name__)


class AudioProcessError(Exception):
    """音频处理错误。"""


def decode_audio(audio_base64: str) -> tuple[bytes, int]:
    """
    解码 base64 音频为 PCM 字节和采样率。

    支持格式：
    - WAV: 直接读取 sample_rate 和 PCM 数据
    - PCM（原始）: 假定采样率 16000 Hz

    返回：(pcm_bytes, sample_rate)
    """
    try:
        raw_bytes = base64.b64decode(audio_base64, validate=True)
    except Exception as e:
        raise AudioProcessError(f"base64 解码失败: {e}")

    if len(raw_bytes) < 4:
        raise AudioProcessError("音频数据过短")

    # 尝试按 WAV 格式读取
    if raw_bytes[:4] == b"RIFF":
        return _decode_wav(raw_bytes)

    # 降级为原始 PCM（16-bit, 单声道, 16kHz）
    return _decode_raw_pcm(raw_bytes)


def _decode_wav(wav_bytes: bytes) -> tuple[bytes, int]:
    """从 WAV 字节读取 PCM 数据和采样率。"""
    try:
        wav_io = io.BytesIO(wav_bytes)
        with wave.open(wav_io, "rb") as wav_file:
            n_channels = wav_file.getnchannels()
            sample_width = wav_file.getsampwidth()
            sample_rate = wav_file.getframerate()

            frames = wav_file.readframes(wav_file.getnframes())

            # 如果是立体声，转单声道（取平均）
            if n_channels == 2:
                # 每帧 2 字节（16-bit）
                if sample_width == 2:
                    pcm_stereo = struct.unpack(f"<{len(frames)//2}h", frames)
                    pcm_mono = [
                        int((pcm_stereo[i] + pcm_stereo[i + 1]) / 2)
                        for i in range(0, len(pcm_stereo), 2)
                    ]
                    frames = struct.pack(f"<{len(pcm_mono)}h", *pcm_mono)
            elif n_channels != 1:
                raise AudioProcessError(f"不支持的声道数: {n_channels}")

            logger.debug(
                f"[Audio] WAV 解码: channels={n_channels}, "
                f"sample_rate={sample_rate}, size={len(frames)}"
            )
            return frames, sample_rate

    except wave.Error as e:
        raise AudioProcessError(f"WAV 格式错误: {e}")
    except Exception as e:
        raise AudioProcessError(f"WAV 解码异常: {e}")


def _decode_raw_pcm(pcm_bytes: bytes) -> tuple[bytes, int]:
    """从原始 PCM 字节读取（假定 16-bit, 16kHz）。"""
    if len(pcm_bytes) % 2 != 0:
        raise AudioProcessError("PCM 字节数必须为偶数（16-bit 采样）")

    sample_rate = 16000  # 默认假定 16kHz

    logger.debug(
        f"[Audio] 原始 PCM 解码: sample_rate={sample_rate}, size={len(pcm_bytes)}"
    )
    return pcm_bytes, sample_rate


def get_audio_duration_ms(pcm_bytes: bytes, sample_rate: int) -> int:
    """计算音频时长（毫秒）。"""
    if sample_rate <= 0 or not pcm_bytes:
        return 0
    # 16-bit = 2 bytes per sample
    num_samples = len(pcm_bytes) // 2
    return int(num_samples / sample_rate * 1000)
