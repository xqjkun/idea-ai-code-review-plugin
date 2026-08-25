package com.medcompany.aireview.commit

import com.medcompany.aireview.model.CommitPatch
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

    @Test
    fun `marks a commit reviewed without code changes`() {
        assertEquals(
            "fix: inspect transfer state\n\nAI-Review: reviewed-no-change",
            appendReviewedWithoutChangesTrailer("fix: inspect transfer state"),
        )
    }

    @Test
    fun `replaces a previous override when accepting an unchanged report`() {
        assertEquals(
            "fix: inspect transfer state\n\nAI-Review: reviewed-no-change",
            appendReviewedWithoutChangesTrailer(
                "fix: inspect transfer state\n\nAI-Review: overridden\nAI-Review-Reason: old reason",
            ),
        )
    }

    @Test
    fun `report bypass only applies to an unchanged diff before expiry`() {
        val original = CommitPatch("diff --git a/A.java b/A.java\n+first", listOf("A.java"), 45)
        val changed = CommitPatch("diff --git a/A.java b/A.java\n+second", listOf("A.java"), 46)
        val fingerprint = patchFingerprint(original)
        val bypass = ManualFixBypass(fingerprint, expiresAtMillis = 1_000)

        kotlin.test.assertTrue(bypass.appliesTo(patchFingerprint(original), 999))
        kotlin.test.assertFalse(bypass.appliesTo(patchFingerprint(changed), 999))
        kotlin.test.assertFalse(bypass.appliesTo(fingerprint, 1_001))
    }
}
