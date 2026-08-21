package com.medcompany.aireview.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiReviewOverrideDialogTest {
    @Test
    fun `requires a meaningful override reason`() {
        assertEquals("请填写至少 5 个字符的强制提交原因", overrideValidationMessage("误报", true))
    }

    @Test
    fun `requires manual review acknowledgement`() {
        assertEquals("请确认已经人工复核本次改动", overrideValidationMessage("已经人工确认", false))
    }

    @Test
    fun `allows an acknowledged override with a reason`() {
        assertNull(overrideValidationMessage("已经人工确认是误报", true))
    }
}
