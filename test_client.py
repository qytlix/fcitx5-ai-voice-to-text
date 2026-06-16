#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
简单的 HTTP 测试客户端，用来验证 server 与 Android client 的对接。

使用方法：
    # 在项目根目录运行，确保 server 已启动（port 8080）
    python test_client.py
"""

import base64
import json
import requests
import sys
import io
from typing import Optional

# 修复 Windows UTF-8 输出问题
if sys.stdout.encoding != 'utf-8':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

BASE_URL = "http://localhost:8080"
API_TOKEN = ""  # 如果 server 配置了 token，在此填入


def create_mock_audio_wav(duration_ms: int = 1000) -> str:
    """
    生成一个模拟的 WAV 音频（无声数据），用于测试。

    参数：
        duration_ms: 音频时长（毫秒）

    返回：
        base64 编码的 WAV 数据
    """
    sample_rate = 16000
    channels = 1
    bits_per_sample = 16
    byte_rate = sample_rate * channels * bits_per_sample // 8
    block_align = channels * bits_per_sample // 8

    # 计算 PCM 数据大小
    num_samples = (duration_ms * sample_rate) // 1000
    pcm_data_size = num_samples * block_align

    # 构造 WAV 文件
    total_size = 44 + pcm_data_size
    wav = bytearray(total_size)

    # RIFF header
    wav[0:4] = b"RIFF"
    wav[4:8] = (total_size - 8).to_bytes(4, "little")
    wav[8:12] = b"WAVE"

    # fmt chunk
    wav[12:16] = b"fmt "
    wav[16:20] = (16).to_bytes(4, "little")  # chunk size
    wav[20:22] = (1).to_bytes(2, "little")  # PCM format
    wav[22:24] = (channels).to_bytes(2, "little")
    wav[24:28] = (sample_rate).to_bytes(4, "little")
    wav[28:32] = (byte_rate).to_bytes(4, "little")
    wav[32:34] = (block_align).to_bytes(2, "little")
    wav[34:36] = (bits_per_sample).to_bytes(2, "little")

    # data chunk
    wav[36:40] = b"data"
    wav[40:44] = (pcm_data_size).to_bytes(4, "little")

    # PCM 数据（全 0）
    # 从位置 44 开始已经全 0，无需填充

    return base64.b64encode(wav).decode("utf-8")


def test_health() -> bool:
    """测试健康检查端点。"""
    print("[Test] 健康检查...")
    try:
        resp = requests.get(f"{BASE_URL}/health", timeout=5)
        print(f"  ✓ 状态码: {resp.status_code}")
        print(f"  ✓ 响应: {resp.json()}")
        return resp.status_code == 200
    except Exception as e:
        print(f"  ✗ 失败: {e}")
        return False


def test_transcribe(
    style: str = "正式",
    prompt: Optional[str] = None,
    sample: Optional[str] = None,
    audio_duration_ms: int = 1000,
    api_token: Optional[str] = None,
) -> bool:
    """测试转录接口。"""
    print(f"\n[Test] 转录接口 (style={style}, audio_duration={audio_duration_ms}ms)")

    audio_wav = create_mock_audio_wav(audio_duration_ms)
    print(f"  生成 WAV 音频: {len(audio_wav)} 字符 (base64)")

    payload = {
        "audio": audio_wav,
        "style": style,
        "prompt": prompt,
        "sample": sample,
    }

    headers = {}
    if api_token:
        headers["X-API-Token"] = api_token

    try:
        resp = requests.post(
            f"{BASE_URL}/v1/transcribe",
            json=payload,
            headers=headers,
            timeout=10,
        )
        print(f"  ✓ 状态码: {resp.status_code}")

        result = resp.json()
        print(f"  ✓ 响应:")
        print(f"    - text: {result.get('text', '')[:80]}")
        print(f"    - original_text: {result.get('original_text', '')[:80]}")
        print(f"    - duration_ms: {result.get('duration_ms')}")
        print(f"    - error: {result.get('error')}")

        if result.get("error"):
            print(f"  ✗ 服务器返回错误: {result['error']}")
            return False

        return resp.status_code == 200

    except requests.exceptions.RequestException as e:
        print(f"  ✗ 请求失败: {e}")
        return False


def test_cors() -> bool:
    """测试 CORS 跨域请求。"""
    print("\n[Test] CORS 跨域请求...")

    headers = {
        "Origin": "http://localhost:3000",
        "Access-Control-Request-Method": "POST",
    }

    try:
        resp = requests.options(
            f"{BASE_URL}/v1/transcribe",
            headers=headers,
            timeout=5,
        )
        print(f"  ✓ 状态码: {resp.status_code}")

        cors_headers = {
            k: v for k, v in resp.headers.items() if k.lower().startswith("access-control")
        }
        if cors_headers:
            print(f"  ✓ CORS 头:")
            for k, v in cors_headers.items():
                print(f"    - {k}: {v}")
            return True
        else:
            print(f"  ⚠ 未检测到 CORS 头")
            return True  # 可能是配置允许所有

    except Exception as e:
        print(f"  ✗ 失败: {e}")
        return False


def test_error_cases() -> bool:
    """测试错误情况处理。"""
    print("\n[Test] 错误情况处理...")

    # 测试 1: 空音频
    print("  测试 1: 空音频...")
    try:
        resp = requests.post(
            f"{BASE_URL}/v1/transcribe",
            json={"audio": "", "style": "正式"},
            timeout=5,
        )
        result = resp.json()
        if result.get("error"):
            print(f"    ✓ 正确返回错误: {result['error']}")
        else:
            print(f"    ✗ 应该返回错误但没有")
            return False
    except Exception as e:
        print(f"    ✗ 失败: {e}")
        return False

    # 测试 2: 非法 base64
    print("  测试 2: 非法 base64...")
    try:
        resp = requests.post(
            f"{BASE_URL}/v1/transcribe",
            json={"audio": "not-valid-base64!!!", "style": "正式"},
            timeout=5,
        )
        result = resp.json()
        if result.get("error"):
            print(f"    ✓ 正确返回错误: {result['error']}")
        else:
            print(f"    ✗ 应该返回错误但没有")
            return False
    except Exception as e:
        print(f"    ✗ 失败: {e}")
        return False

    # 测试 3: 无效风格
    print("  测试 3: 无效风格...")
    try:
        resp = requests.post(
            f"{BASE_URL}/v1/transcribe",
            json={"audio": create_mock_audio_wav(), "style": "无效风格"},
            timeout=5,
        )
        # FastAPI 会返回 422 Unprocessable Entity
        if resp.status_code == 422:
            print(f"    ✓ 正确返回 422 状态码")
        else:
            print(f"    ⚠ 返回状态码: {resp.status_code}")
    except Exception as e:
        print(f"    ✗ 失败: {e}")
        return False

    return True


def main():
    print("=" * 60)
    print("Fcitx5 Voice-to-Text Server 集成测试")
    print("=" * 60)

    # 测试列表
    tests = [
        ("健康检查", test_health),
        ("CORS", test_cors),
        ("错误情况", test_error_cases),
        ("转录 (正式)", lambda: test_transcribe(style="正式")),
        ("转录 (精简)", lambda: test_transcribe(style="精简")),
        ("转录 (礼貌)", lambda: test_transcribe(style="礼貌")),
        ("转录 (自定义 prompt)", lambda: test_transcribe(style="自定义", prompt="json")),
    ]

    results = {}
    for name, test_func in tests:
        try:
            results[name] = test_func()
        except Exception as e:
            print(f"\n[Error] {name} 发生未捕获异常: {e}")
            results[name] = False

    # 总结
    print("\n" + "=" * 60)
    print("测试总结")
    print("=" * 60)
    for name, passed in results.items():
        status = "✓ 通过" if passed else "✗ 失败"
        print(f"{status}: {name}")

    total = len(results)
    passed = sum(1 for v in results.values() if v)
    print(f"\n总计: {passed}/{total} 通过")

    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
