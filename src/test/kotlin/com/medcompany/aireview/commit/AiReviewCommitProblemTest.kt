package com.medcompany.aireview.commit

import kotlin.test.Test
import kotlin.test.assertEquals

class AiReviewCommitProblemTest {
    @Test
    fun `appends override reason as git trailers`() {
        assertEquals(
            "feat: update transfer\n\nAI-Review: overridden\nAI-Review-Reason: 已人工确认为误报",
            appendOverrideTrailers("feat: update transfer", "已人工确认为误报"),
        )
    }

    @Test
    fun `normalizes multiline reason and replaces existing AI trailers`() {
        assertEquals(
            "fix: state check\n\nAI-Review: overridden\nAI-Review-Reason: 已人工 检查测试",
            appendOverrideTrailers(
                "fix: state check\n\nAI-Review: overridden\nAI-Review-Reason: old reason",
                "  已人工\n检查测试  ",
            ),
        )
    }

    @Test
    fun `keeps AI trailers in an existing git trailer block`() {
        assertEquals(
            "fix: state check\n\nSigned-off-by: Dev <dev@example.com>\nAI-Review: overridden\nAI-Review-Reason: 已复核",
            appendOverrideTrailers(
                "fix: state check\n\nSigned-off-by: Dev <dev@example.com>",
                "已复核",
            ),
        )
    }
}
