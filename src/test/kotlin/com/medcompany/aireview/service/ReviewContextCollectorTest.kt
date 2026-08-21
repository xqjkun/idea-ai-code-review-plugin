package com.medcompany.aireview.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewContextCollectorTest {
    @Test
    fun `extracts frontend and JVM imports`() {
        val imports = ReviewContextCollector.extractImports(
            """
                import { calculate } from './billing/calculate'
                const rules = require("../rules")
                import com.medcompany.charge.ChargeService;
                from domain.patient import Patient
            """.trimIndent(),
        )

        assertEquals(
            setOf("./billing/calculate", "../rules", "com.medcompany.charge.ChargeService", "domain.patient"),
            imports,
        )
    }

    @Test
    fun `renders complete before and after content with line numbers`() {
        val rendered = ReviewContextCollector.renderSnapshot(
            label = "提交后",
            path = "src/charge.ts",
            content = "const amount = 10\nreturn amount",
            changedRanges = listOf(1..2),
        )

        assertContains(rendered, "### 提交后：src/charge.ts")
        assertContains(rendered, "1 | const amount = 10")
        assertContains(rendered, "2 | return amount")
        assertContains(rendered, "完整内容")
    }

    @Test
    fun `large snapshots keep changed area and omit distant lines`() {
        val content = (1..3_000).joinToString("\n") { line -> "line-$line-${"x".repeat(20)}" }
        val rendered = ReviewContextCollector.renderSnapshot(
            label = "提交前",
            path = "src/large.java",
            content = content,
            changedRanges = listOf(1_500..1_502),
        )

        assertTrue("line-1500" in rendered)
        assertFalse("line-10-" in rendered)
        assertFalse("line-2990-" in rendered)
        assertContains(rendered, "变更点前后 80 行摘要")
    }
}
