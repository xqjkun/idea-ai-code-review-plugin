package com.medcompany.aireview.model

import com.medcompany.aireview.settings.AiReviewConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewReportTest {
    private fun config(blockMedium: Boolean = false) = AiReviewConfig(
        enabled = true,
        apiUrl = "https://api.deepseek.com/chat/completions",
        model = "deepseek-chat",
        maxDiffBytes = 180 * 1024,
        maxContextBytes = 240 * 1024,
        maxFiles = 80,
        maxRelatedFiles = 12,
        blockingSeverities = buildSet {
            add("CRITICAL")
            add("HIGH")
            if (blockMedium) add("MEDIUM")
        },
        customFocus = emptyList(),
    )

    private fun report(severity: Severity) = ReviewReport(
        summary = "test",
        findings = listOf(ReviewFinding(severity, "src/a.ts", 1, "title", "description", "suggestion")),
        testSuggestions = emptyList(),
        model = "deepseek-chat",
        fileCount = 1,
        diffBytes = 10,
    )

    @Test
    fun `high severity blocks by default`() {
        assertTrue(report(Severity.HIGH).hasBlockingFindings(config()))
    }

    @Test
    fun `medium severity is configurable`() {
        assertFalse(report(Severity.MEDIUM).hasBlockingFindings(config()))
        assertTrue(report(Severity.MEDIUM).hasBlockingFindings(config(blockMedium = true)))
    }

    @Test
    fun `failure report retains commit metadata and does not claim no findings`() {
        val report = ReviewReport.failure(
            message = "JSON 被截断",
            model = "deepseek-v4-flash",
            fileCount = 6,
            diffBytes = 4096,
            contextFileCount = 9,
            contextBytes = 8192,
        )

        assertEquals(6, report.fileCount)
        assertEquals(9, report.contextFileCount)
        val markdown = report.toMarkdown(config())
        assertContains(markdown, "文件数：6")
        assertContains(markdown, "审核未完成")
        assertFalse(markdown.contains("\n未发现问题。"))
    }
}
