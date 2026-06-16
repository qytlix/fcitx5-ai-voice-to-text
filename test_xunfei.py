#!/usr/bin/env python3
"""测试讯飞 RAASR ASR 集成。"""

import json
import requests
import sys

BASE_URL = "http://localhost:8080"

# 模拟音频（WAV 格式，极小）
TEST_AUDIO_BASE64 = "UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQIAAAAAAA=="

def test_transcribe():
    """测试 /v1/transcribe 端点。"""
    print("=== Test Xunfei RAASR ASR ===")

    payload = {
        "audio": TEST_AUDIO_BASE64,
        "style": "正式",
    }

    try:
        print(f"POST {BASE_URL}/v1/transcribe")

        resp = requests.post(
            f"{BASE_URL}/v1/transcribe",
            json=payload,
            timeout=60,
        )

        print(f"Status: {resp.status_code}")
        result = resp.json()
        print(f"Response:")
        print(json.dumps(result, ensure_ascii=False, indent=2))

        if result.get("error"):
            print(f"[ERROR] {result['error']}")
            return False
        else:
            print(f"[OK] Success!")
            return True

    except Exception as e:
        print(f"[ERROR] Request failed: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = test_transcribe()
    sys.exit(0 if success else 1)
