package org.fcitx.fcitx5.android.voice.core.domain

import org.fcitx.fcitx5.android.voice.core.test.MockAudioRecorder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * RecordingUseCase 单元测试。
 *
 * 测试录音管理用例的权限检查、时长估算等功能。
 */
@DisplayName("RecordingUseCase")
class RecordingUseCaseTest {

    private lateinit var mockRecorder: MockAudioRecorder
    private lateinit var recordingUseCase: RecordingUseCase

    @BeforeEach
    fun setUp() {
        mockRecorder = MockAudioRecorder()
        recordingUseCase = RecordingUseCase(mockRecorder)
    }

    @Nested
    @DisplayName("时长估算")
    inner class DurationEstimation {

        @Test
        @DisplayName("空音频应返回 0")
        fun `empty audio should return 0`() {
            val duration = recordingUseCase.estimateDuration("")
            assertTrue(duration == 0L)
        }

        @Test
        @DisplayName("Mock WAV 数据（0.5秒）的估算时长应在合理范围内")
        fun `mock wav duration should be reasonable`() {
            // MockAudioRecorder.MOCK_WAV_BASE64 模拟 0.5 秒录音
            val base64Audio = MockAudioRecorder.MOCK_WAV_BASE64

            val duration = recordingUseCase.estimateDuration(base64Audio)

            // 约 500ms，允许 ±10% 误差
            assertTrue(duration in 450..550) {
                "Expected ~500ms but got ${duration}ms"
            }
        }

        @Test
        @DisplayName("无效 base64 应返回 0")
        fun `invalid base64 should return 0`() {
            // 非法的 base64 字符串
            val duration = recordingUseCase.estimateDuration("!!!invalid base64!!!")
            assertTrue(duration == 0L)
        }

        @Test
        @DisplayName("更长的音频数据应估算更长的时长")
        fun `longer audio should estimate longer duration`() {
            // 模拟 2 秒录音：1 个 WAV header + 64000 bytes PCM
            val shortBase64 = MockAudioRecorder.MOCK_WAV_BASE64

            // 构造更长的数据：重复 mock base64 两次
            val longBase64 = shortBase64 + shortBase64

            val shortDuration = recordingUseCase.estimateDuration(shortBase64)
            val longDuration = recordingUseCase.estimateDuration(longBase64)

            assertTrue(longDuration > shortDuration) {
                "Longer audio ($longDuration ms) should estimate longer than shorter ($shortDuration ms)"
            }
        }
    }

    @Nested
    @DisplayName("权限检查")
    inner class PermissionCheck {

        @Test
        @DisplayName("纯 Kotlin 层检查应默认通过")
        fun `core layer should return true for permission check`() {
            // 纯 Kotlin 层无法检查 Android 权限，
            // 始终返回 true，实际权限检查在 Android 层
            assertTrue(recordingUseCase.isPermissionGranted())
        }
    }
}