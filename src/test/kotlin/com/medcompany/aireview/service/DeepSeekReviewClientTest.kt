package com.medcompany.aireview.service

import com.google.gson.JsonParser
import com.medcompany.aireview.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeepSeekReviewClientTest {
    private val client = DeepSeekReviewClient()

    @Test
    fun `parses structured findings and test suggestions`() {
        val report = client.parseReview(
            content = """
                {
                  "summary": "发现重复收费风险",
                  "findings": [{
                    "severity": "high",
                    "file": "src/charge.ts",
                    "line": 42,
                    "title": "重复累计费用",
                    "description": "同一费用在循环中被累计两次",
                    "suggestion": "按费用 ID 去重"
                  }],
                  "testSuggestions": ["增加重复费用测试"]
                }
            """.trimIndent(),
            model = "deepseek-chat",
            fileCount = 1,
            diffBytes = 1024,
            contextFileCount = 3,
            contextBytes = 8192,
        )

        assertEquals("发现重复收费风险", report.summary)
        assertEquals(Severity.HIGH, report.findings.single().severity)
        assertEquals(42, report.findings.single().line)
        assertEquals(listOf("增加重复费用测试"), report.testSuggestions)
        assertEquals(3, report.contextFileCount)
        assertEquals(8192, report.contextBytes)
    }

    @Test
    fun `accepts markdown fenced JSON`() {
        val report = client.parseReview(
            "```json\n{\"summary\":\"通过\",\"findings\":[],\"testSuggestions\":[]}\n```",
            "deepseek-chat",
            0,
            0,
        )
        assertEquals("通过", report.summary)
    }

    @Test
    fun `rejects invalid model output`() {
        assertFailsWith<RuntimeException> {
            client.parseReview("not json", "deepseek-chat", 1, 10)
        }
    }

    @Test
    fun `reads finish reason so truncated JSON can be retried`() {
        val completion = client.parseChatCompletion(
            """
                {
                  "choices": [{
                    "message": {"content": "{\"summary\":\"incomplete"},
                    "finish_reason": "length"
                  }]
                }
            """.trimIndent(),
        )

        assertEquals("{\"summary\":\"incomplete", completion.content)
        assertEquals("length", completion.finishReason)
    }

    @Test
    fun `empty V4 content retains reasoning and token diagnostics`() {
        val completion = client.parseChatCompletion(
            """
                {
                  "choices": [{
                    "message": {"content": "", "reasoning_content": "internal reasoning"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 91234, "completion_tokens": 321}
                }
            """.trimIndent(),
        )

        assertEquals("", completion.content)
        assertEquals(18, completion.reasoningCharacters)
        assertEquals(91234, completion.promptTokens)
        assertEquals(321, completion.completionTokens)
        kotlin.test.assertContains(completion.emptyContentMessage(), "prompt_tokens=91234")
    }

    @Test
    fun `V4 JSON requests explicitly disable thinking mode`() {
        val body = JsonParser.parseString(
            client.buildRequestBody("deepseek-v4-flash", "json system", "json user", 8192),
        ).asJsonObject

        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").asString)
        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").asString)
        assertEquals(8192, body.get("max_tokens").asInt)
    }

    @Test
    fun `recognizes official insufficient balance response`() {
        assertTrue(isQuotaExceededResponse(402, "{\"error\":{\"message\":\"Insufficient Balance\"}}"))
    }

    @Test
    fun `recognizes compatible gateway quota errors`() {
        assertTrue(isQuotaExceededResponse(429, "{\"code\":\"insufficient_quota\"}"))
        assertTrue(isQuotaExceededResponse(400, "账户余额不足，请充值"))
    }

    @Test
    fun `does not treat ordinary rate limiting as exhausted quota`() {
        assertFalse(isQuotaExceededResponse(429, "Rate limit reached. Please retry later."))
    }
}
