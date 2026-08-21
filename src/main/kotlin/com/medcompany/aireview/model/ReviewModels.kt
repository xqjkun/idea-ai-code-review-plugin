package com.medcompany.aireview.model

import com.medcompany.aireview.settings.AiReviewConfig

enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun from(value: String?): Severity =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: LOW
    }
}

data class ReviewFinding(
    val severity: Severity,
    val file: String,
    val line: Int,
    val title: String,
    val description: String,
    val suggestion: String,
)

data class ReviewReport(
    val summary: String,
    val findings: List<ReviewFinding>,
    val testSuggestions: List<String>,
    val model: String,
    val fileCount: Int,
    val diffBytes: Int,
    val contextFileCount: Int = 0,
    val contextBytes: Int = 0,
    val error: String? = null,
) {
    fun blockingFindings(config: AiReviewConfig): List<ReviewFinding> =
        findings.filter { it.severity.name in config.blockingSeverities }

    fun hasBlockingFindings(config: AiReviewConfig): Boolean = blockingFindings(config).isNotEmpty()

    fun toMarkdown(config: AiReviewConfig): String = buildString {
        val blocking = blockingFindings(config)
        appendLine("# AI 代码审核报告")
        appendLine()
        appendLine("- 结论：${if (error != null || blocking.isNotEmpty()) "⛔ 阻止提交" else "✅ 允许提交"}")
        appendLine("- 模型：$model")
        appendLine("- 文件数：$fileCount")
        appendLine("- Diff：$diffBytes 字节")
        appendLine("- 逻辑上下文：$contextFileCount 个文件，$contextBytes 字节")
        appendLine()
        appendLine(error ?: summary)
        appendLine()
        appendLine("## 审核发现")
        appendLine()
        if (error != null) {
            appendLine("审核未完成，不能得出“未发现问题”的结论。")
        } else if (findings.isEmpty()) {
            appendLine("未发现问题。")
        }
        findings.forEach { finding ->
            appendLine("### [${finding.severity}] ${finding.file}:${finding.line} — ${finding.title}")
            appendLine()
            appendLine(finding.description.ifBlank { "无补充说明。" })
            appendLine()
            appendLine("建议：${finding.suggestion.ifBlank { "请结合上下文修复。" }}")
            appendLine()
        }
        appendLine("## 建议补充的测试")
        appendLine()
        if (testSuggestions.isEmpty()) appendLine("无。")
        testSuggestions.forEach { appendLine("- $it") }
    }

    companion object {
        fun failure(
            message: String,
            model: String = "",
            fileCount: Int = 0,
            diffBytes: Int = 0,
            contextFileCount: Int = 0,
            contextBytes: Int = 0,
        ): ReviewReport = ReviewReport(
            summary = "AI 审核执行失败",
            findings = emptyList(),
            testSuggestions = emptyList(),
            model = model,
            fileCount = fileCount,
            diffBytes = diffBytes,
            contextFileCount = contextFileCount,
            contextBytes = contextBytes,
            error = message,
        )
    }
}

data class CommitPatch(
    val text: String,
    val files: List<String>,
    val bytes: Int,
    val contextText: String = "",
    val contextFiles: List<String> = emptyList(),
    val contextBytes: Int = 0,
)

open class AiReviewException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class DeepSeekQuotaException(message: String) : AiReviewException(message)
