package com.medcompany.aireview.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.diagnostic.Logger
import com.medcompany.aireview.model.ReviewReport
import com.medcompany.aireview.settings.AiReviewConfig
import com.medcompany.aireview.ui.AiReviewOverrideDialog
import com.medcompany.aireview.ui.AiReviewReportDialog

class AiReviewCommitProblem(
    private val report: ReviewReport,
    private val config: AiReviewConfig,
    private val onOverrideAccepted: (String) -> Unit,
) : CommitProblemWithDetails {
    override val text: String = report.error?.let { "AI 审核失败：$it" }
        ?: "AI 审核发现 ${report.blockingFindings(config).size} 个阻断问题，提交已取消"

    override val showDetailsAction: String = "查看 AI 审核报告"

    override fun showDetails(project: Project) {
        AiReviewReportDialog(project, report, config).show()
    }

    override fun showModalSolution(project: Project, commitInfo: CommitInfo): CheckinHandler.ReturnResult {
        val dialog = AiReviewOverrideDialog(project, report, config)
        if (!dialog.showAndGet()) return CheckinHandler.ReturnResult.CANCEL

        onOverrideAccepted(dialog.reason)
        LOG.warn(
            buildString {
                append("AI Code Review override accepted")
                append("; model=${report.model}")
                append("; blockingFindings=${report.blockingFindings(config).size}")
                append("; reviewFailed=${report.error != null}")
                append("; reason=${dialog.reason.replace(Regex("[\\r\\n]+"), " ").take(300)}")
            },
        )
        return CheckinHandler.ReturnResult.COMMIT
    }

    companion object {
        private val LOG = Logger.getInstance(AiReviewCommitProblem::class.java)
    }
}

internal fun appendOverrideTrailers(commitMessage: String, reason: String): String {
    val normalizedReason = reason.trim()
        .replace(Regex("\\s+"), " ")
        .take(500)
    val retainedLines = commitMessage.lineSequence()
        .filterNot { line ->
            line.startsWith("AI-Review:", ignoreCase = true) ||
                line.startsWith("AI-Review-Reason:", ignoreCase = true)
        }
        .toList()
        .dropLastWhile(String::isBlank)
    val base = retainedLines.joinToString("\n")
    val trailer = "AI-Review: overridden\nAI-Review-Reason: $normalizedReason"
    if (base.isBlank()) return trailer

    val lastBlankLine = retainedLines.indexOfLast(String::isBlank)
    val continuesExistingTrailerBlock = lastBlankLine >= 0 &&
        retainedLines.drop(lastBlankLine + 1).all { it.matches(Regex("[A-Za-z0-9-]+: .+")) }
    return base + if (continuesExistingTrailerBlock) "\n$trailer" else "\n\n$trailer"
}
