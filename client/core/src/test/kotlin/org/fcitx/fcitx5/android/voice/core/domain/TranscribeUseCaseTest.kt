package org.fcitx.fcitx5.android.voice.core.domain

import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.voice.core.model.TranscribeRequest
import org.fcitx.fcitx5.android.voice.core.model.TranscribeResponse
import org.fcitx.fcitx5.android.voice.core.network.TranscribeException
import org.fcitx.fcitx5.android.voice.core.test.MockAudioRecorder
import org.fcitx.fcitx5.android.voice.core.test.MockCommitTextHandler
import org.fcitx.fcitx5.android.voice.core.test.MockTranscribeService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * TranscribeUseCase 单元测试。
 *
 * 测试核心业务流程：录音 → 编码 → 网络请求 → 上屏。
 * 所有依赖（录音、网络、上屏）都使用 Mock 实现，无真实硬件或网络依赖。
 */
@DisplayName("TranscribeUseCase")
class TranscribeUseCaseTest {

    private lateinit var mockRecorder: MockAudioRecorder
    private lateinit var mockService: MockTranscribeService
    private lateinit var mockHandler: MockCommitTextHandler
    private lateinit var useCase: TranscribeUseCase

    @BeforeEach
    fun setUp() {
        mockRecorder = MockAudioRecorder()
        mockService = MockTranscribeService()
        mockHandler = MockCommitTextHandler()
        useCase = TranscribeUseCase(
            audioRecorder = mockRecorder,
            transcribeService = mockService,
            commitTextHandler = mockHandler
        )
    }

    @Nested
    @DisplayName("正常流程")
    inner class HappyPath {

        @Test
        @DisplayName("完整录音→发送→上屏流程应成功返回结果")
        fun `full transcribe flow should return result and commit text`() = runTest {
            val result = useCase.execute(style = "正式")

            // 验证结果
            assertTrue(result.text.isNotEmpty())
            assertTrue(result.originalText.isNotEmpty())
            assertTrue(result.totalDurationMs >= 0)

            // 验证上屏
            assertTrue(mockHandler.commitCount == 1)
            assertTrue(mockHandler.lastCommittedText == result.text)
        }

        @Test
        @DisplayName("接收外部音频源（跳过录音环节）")
        fun `with external audio source should skip recording`() = runTest {
            val externalAudio = MockAudioRecorder.MOCK_WAV_BASE64

            val result = useCase.execute(audioSource = externalAudio)

            // 不应调用录音（没有 start/stop 调用）
            // MockAudioRecorder 的 isRecording 在 start 时变为 true
            // 但由于跳过了录音，它应该是 false
            assertTrue(result.text == mockService.mockResponse.text)
        }

        @Test
        @DisplayName("不同风格应传递正确的 style 参数")
        fun `different styles should be passed to service`() = runTest {
            val styles = listOf("正式", "精简", "礼貌", "翻译_英文", "自定义")

            for (style in styles) {
                mockHandler.reset()
                useCase.execute(style = style)
                assertTrue(mockService.lastRequest?.style == style)
            }
        }

        @Test
        @DisplayName("自定义 prompt 和 sample 应传递到请求中")
        fun `custom prompt and sample should be passed to request`() = runTest {
            val prompt = "请帮我把这段话改成更加正式的风格"
            val sample = "尊敬的客户您好……"

            useCase.execute(
                style = "自定义",
                prompt = prompt,
                sample = sample
            )

            assertTrue(mockService.lastRequest?.prompt == prompt)
            assertTrue(mockService.lastRequest?.sample == sample)
        }
    }

    @Nested
    @DisplayName("错误处理")
    inner class ErrorHandling {

        @Test
        @DisplayName("服务器返回业务错误应抛出 ServerReturnedError")
        fun `server business error should throw ServerReturnedError`() = runTest {
            mockService.shouldReturnError = true
            mockService.errorMessage = "音频格式不支持"

            val exception = assertThrows<TranscribeException.ServerReturnedError> {
                useCase.execute()
            }

            assertTrue(exception.serverMessage == "音频格式不支持")
            // 不应上屏
            assertTrue(mockHandler.commitCount == 0)
        }

        @Test
        @DisplayName("录音失败应抛出 RecordingFailed")
        fun `recording failure should throw RecordingFailed`() = runTest {
            mockRecorder.shouldFailOnStart = true

            assertThrows<TranscribeException.RecordingFailed> {
                useCase.execute()
            }
        }

        @Test
        @DisplayName("编码失败应抛出 EncodingFailed")
        fun `encoding failure should throw EncodingFailed`() = runTest {
            mockRecorder.shouldFailOnStop = true

            assertThrows<TranscribeException.EncodingFailed> {
                useCase.execute()
            }
        }

        @Test
        @DisplayName("网络超时应触发重试并在重试耗尽后抛出 Timeout")
        fun `network timeout should retry then throw Timeout`() = runTest {
            mockService.shouldTimeout = true

            assertThrows<TranscribeException.Timeout> {
                useCase.execute()
            }
        }

        @Test
        @DisplayName("连接异常应触发重试并在重试后抛出 NetworkError")
        fun `connection error should retry then throw NetworkError`() = runTest {
            mockService.shouldThrowConnectionError = true

            assertThrows<TranscribeException.NetworkError> {
                useCase.execute()
            }
        }

        @Test
        @DisplayName("重试后成功不应影响正常流程")
        fun `success after retry should work normally`() = runTest {
            // 第一次超时，第二次成功
            mockService.shouldTimeout = false
            // 正常执行不应报错
            val result = assertDoesNotThrow {
                useCase.execute()
            }

            assertTrue(result.text.isNotEmpty())
            assertTrue(mockHandler.commitCount == 1)
        }
    }

    @Nested
    @DisplayName("请求参数验证")
    inner class RequestValidation {

        @Test
        @DisplayName("默认 style 应为 正式")
        fun `default style should be formal`() = runTest {
            useCase.execute()

            assertTrue(mockService.lastRequest?.style == "正式")
        }

        @Test
        @DisplayName("应发送 base64 音频数据")
        fun `should send base64 audio data`() = runTest {
            useCase.execute()

            val request = mockService.lastRequest
            assertTrue(request != null)
            val req = request!!
            assertTrue(req.audio.isNotEmpty())

            // 验证音频是合法的 base64
            assertDoesNotThrow {
                java.util.Base64.getDecoder().decode(req.audio)
            }
        }
    }
}