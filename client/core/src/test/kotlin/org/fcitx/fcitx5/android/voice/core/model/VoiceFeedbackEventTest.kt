package org.fcitx.fcitx5.android.voice.core.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * VoiceFeedbackEvent 与反馈模型单元测试。
 *
 * 测试反馈事件创建、上传请求转换、响应解析。
 */
@DisplayName("反馈数据模型")
class VoiceFeedbackEventTest {

    private lateinit var sampleEvent: VoiceFeedbackEvent

    @BeforeEach
    fun setUp() {
        sampleEvent = VoiceFeedbackEvent(
            id = "test-event-001",
            sessionId = "test-session-001",
            originalText = "那个 下午三点 有个会",
            styledText = "下午三点有个会。",
            finalText = "您有一个新的会议邀请，安排在下午三点。",
            style = "正式",
            prompt = "请写得更正式一些",
            durationMs = 1240L,
            timestamp = 1718500000000L
        )
    }

    @Nested
    @DisplayName("VoiceFeedbackEvent 基本属性")
    inner class VoiceFeedbackEventProperties {

        @Test
        @DisplayName("创建时应保留所有字段值")
        fun `should preserve all field values on creation`() {
            assertEquals("test-event-001", sampleEvent.id)
            assertEquals("test-session-001", sampleEvent.sessionId)
            assertEquals("那个 下午三点 有个会", sampleEvent.originalText)
            assertEquals("下午三点有个会。", sampleEvent.styledText)
            assertEquals("您有一个新的会议邀请，安排在下午三点。", sampleEvent.finalText)
            assertEquals("正式", sampleEvent.style)
            assertEquals("请写得更正式一些", sampleEvent.prompt)
            assertEquals(1240L, sampleEvent.durationMs)
            assertEquals(1718500000000L, sampleEvent.timestamp)
        }

        @Test
        @DisplayName("无自定义 ID 时应自动生成 UUID")
        fun `should auto-generate UUID when id is not provided`() {
            val event = VoiceFeedbackEvent(
                sessionId = "sess-1",
                originalText = "a",
                styledText = "b",
                finalText = "c",
                style = "正式",
                durationMs = 100L
            )

            assertNotNull(event.id)
            assertTrue(event.id.isNotEmpty())
            // UUID 长度为 36（标准 UUID 格式）
            assertEquals(36, event.id.length)
        }

        @Test
        @DisplayName("无自定义 timestamp 时应使用当前时间")
        fun `should use current time when timestamp is not provided`() {
            val before = System.currentTimeMillis()
            val event = VoiceFeedbackEvent(
                sessionId = "sess-1",
                originalText = "a",
                styledText = "b",
                finalText = "c",
                style = "正式",
                durationMs = 100L
            )
            val after = System.currentTimeMillis()

            assertTrue(event.timestamp >= before)
            assertTrue(event.timestamp <= after)
        }

        @Test
        @DisplayName("prompt 为 null 时应正确处理")
        fun `should handle null prompt`() {
            val event = VoiceFeedbackEvent(
                sessionId = "sess-1",
                originalText = "a",
                styledText = "b",
                finalText = "c",
                style = "正式",
                prompt = null,
                durationMs = 100L
            )

            assertNull(event.prompt)
        }
    }

    @Nested
    @DisplayName("toUploadRequest 转换")
    inner class ToUploadRequestConversion {

        @Test
        @DisplayName("应正确映射所有字段到 upload request")
        fun `should map all fields to upload request correctly`() {
            val req = sampleEvent.toUploadRequest()

            assertEquals("test-event-001", req.client_event_id)
            assertEquals("test-session-001", req.session_id)
            assertEquals("那个 下午三点 有个会", req.original_text)
            assertEquals("下午三点有个会。", req.styled_text)
            assertEquals("您有一个新的会议邀请，安排在下午三点。", req.final_text)
            assertEquals("正式", req.style)
            assertEquals("请写得更正式一些", req.prompt)
            assertEquals(1240L, req.duration_ms)
            assertEquals(1718500000000L, req.timestamp)
        }

        @Test
        @DisplayName("prompt 为 null 时应正确映射")
        fun `should map null prompt correctly`() {
            val event = sampleEvent.copy(prompt = null)
            val req = event.toUploadRequest()

            assertNull(req.prompt)
        }

        @Test
        @DisplayName("往返转换应保持数据一致")
        fun `round-trip conversion should preserve data`() {
            val req = sampleEvent.toUploadRequest()

            assertEquals(sampleEvent.id, req.client_event_id)
            assertEquals(sampleEvent.sessionId, req.session_id)
            assertEquals(sampleEvent.originalText, req.original_text)
            assertEquals(sampleEvent.styledText, req.styled_text)
            assertEquals(sampleEvent.finalText, req.final_text)
            assertEquals(sampleEvent.style, req.style)
            assertEquals(sampleEvent.prompt, req.prompt)
            assertEquals(sampleEvent.durationMs, req.duration_ms)
            assertEquals(sampleEvent.timestamp, req.timestamp)
        }
    }

    @Nested
    @DisplayName("FeedbackUploadResponse")
    inner class FeedbackUploadResponseTests {

        @Test
        @DisplayName("成功响应 isSuccess 应为 true")
        fun `success response should have isSuccess true`() {
            val response = FeedbackUploadResponse(
                status = "ok",
                event_id = "srv-ev-001"
            )
            assertTrue(response.isSuccess)
        }

        @Test
        @DisplayName("错误响应 isSuccess 应为 false")
        fun `error response should have isSuccess false`() {
            val response = FeedbackUploadResponse(
                status = "error",
                error = "存储失败"
            )
            assertFalse(response.isSuccess)
        }

        @Test
        @DisplayName("有 error 字段的响应 isSuccess 应为 false")
        fun `response with error field should have isSuccess false`() {
            val response = FeedbackUploadResponse(
                status = "ok",
                error = "部分数据丢失"
            )
            assertFalse(response.isSuccess)
        }
    }

    @Nested
    @DisplayName("MIN_DIFF_RATIO 常量")
    inner class MinDiffRatio {

        @Test
        @DisplayName("MIN_DIFF_RATIO 应为 0.1")
        fun `MIN_DIFF_RATIO should be 0_1`() {
            assertEquals(0.1, VoiceFeedbackEvent.MIN_DIFF_RATIO, 0.001)
        }
    }

    @Nested
    @DisplayName("data class 行为")
    inner class DataClassBehavior {

        @Test
        @DisplayName("相同字段的 events 应相等")
        fun `events with same fields should be equal`() {
            val e1 = VoiceFeedbackEvent(
                id = "id1", sessionId = "s1",
                originalText = "a", styledText = "b", finalText = "c",
                style = "正式", durationMs = 100L, timestamp = 1000L
            )
            val e2 = VoiceFeedbackEvent(
                id = "id1", sessionId = "s1",
                originalText = "a", styledText = "b", finalText = "c",
                style = "正式", durationMs = 100L, timestamp = 1000L
            )

            assertEquals(e1, e2)
            assertEquals(e1.hashCode(), e2.hashCode())
        }

        @Test
        @DisplayName("copy 应正确修改指定字段")
        fun `copy should correctly modify specified fields`() {
            val modified = sampleEvent.copy(style = "精简")

            assertEquals("精简", modified.style)
            // 其他字段不变
            assertEquals(sampleEvent.id, modified.id)
            assertEquals(sampleEvent.sessionId, modified.sessionId)
            assertEquals(sampleEvent.originalText, modified.originalText)
        }
    }
}
