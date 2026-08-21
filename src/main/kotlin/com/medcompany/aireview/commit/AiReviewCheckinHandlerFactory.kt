package com.medcompany.aireview.commit

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.medcompany.aireview.model.AiReviewException
import com.medcompany.aireview.model.CommitPatch
import com.medcompany.aireview.model.DeepSeekQuotaException
import com.medcompany.aireview.model.ReviewReport
import com.medcompany.aireview.service.CommitPatchBuilder
import com.medcompany.aireview.service.DeepSeekReviewClient
import com.medcompany.aireview.settings.AiReviewConfig
import com.medcompany.aireview.settings.AiReviewSettings
import com.medcompany.aireview.ui.AiReviewReportDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiReviewCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        AiReviewCheckinHandler(panel, commitContext)
}

private class AiReviewCheckinHandler(
    private val panel: CheckinProjectPanel,
    private val commitContext: CommitContext,
) : CheckinHandler(), CommitCheck, DumbAware {
    private val settings = AiReviewSettings.getInstance()

    override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.LATE

    override fun isEnabled(): Boolean = settings.state.enabled && panel.vcsIsAffected("Git")

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val config = settings.snapshot()
        val apiKey = settings.getApiKey()
        if (apiKey.isBlank()) {
            return failureProblem("尚未配置 DeepSeek API Key，请打开 Settings → Tools → AI Code Review。", config)
        }

        var reviewedPatch: CommitPatch? = null
        return try {
            val result = withContext(Dispatchers.IO) {
                val patch = CommitPatchBuilder.build(
                    project = panel.project,
                    changes = commitInfo.committedChanges,
                    commitContext = commitContext,
                    config = config,
                )
                reviewedPatch = patch
                if (patch.text.isBlank()) {
                    ReviewReport(
                        summary = "本次提交没有需要审核的文本代码改动",
                        findings = emptyList(),
                        testSuggestions = emptyList(),
                        model = config.model,
                        fileCount = 0,
                        diffBytes = 0,
                    )
                } else {
                    DeepSeekReviewClient().review(patch, commitInfo.commitMessage, config, apiKey)
                }
            }

            if (result.hasBlockingFindings(config)) {
                commitProblem(result, config)
            } else {
                showPassedNotification(result, config)
                null
            }
        } catch (error: DeepSeekQuotaException) {
            showQuotaExceededNotification(error.message.orEmpty())
            null
        } catch (error: Exception) {
            val message = when (error) {
                is AiReviewException -> error.message.orEmpty()
                else -> "${error::class.simpleName}: ${error.message}"
            }
            failureProblem(message.ifBlank { "未知错误" }, config, reviewedPatch)
        }
    }

    private fun failureProblem(
        message: String,
        config: AiReviewConfig,
        patch: CommitPatch? = null,
    ): AiReviewCommitProblem = commitProblem(
        report = ReviewReport.failure(
            message = message,
            model = config.model,
            fileCount = patch?.files?.size ?: 0,
            diffBytes = patch?.bytes ?: 0,
            contextFileCount = patch?.contextFiles?.size ?: 0,
            contextBytes = patch?.contextBytes ?: 0,
        ),
        config = config,
    )

    private fun commitProblem(report: ReviewReport, config: AiReviewConfig): AiReviewCommitProblem =
        AiReviewCommitProblem(report, config) { reason ->
            panel.setCommitMessage(appendOverrideTrailers(panel.commitMessage, reason))
        }

    private fun showPassedNotification(report: ReviewReport, config: AiReviewConfig) {
        val type = if (report.findings.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
        val title = if (report.findings.isEmpty()) "AI 审核通过" else "AI 审核通过，存在非阻断建议"
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Code Review")
            .createNotification(title, report.summary, type)
        if (report.findings.isNotEmpty()) {
            notification.addAction(NotificationAction.createSimple("查看报告") {
                AiReviewReportDialog(panel.project, report, config).show()
            })
        }
        notification.notify(panel.project)
    }

    private fun showQuotaExceededNotification(detail: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Code Review")
            .createNotification(
                "DeepSeek 额度不足，已跳过 AI 审核",
                buildString {
                    append("本次提交将继续执行，但代码未经过 AI 审核。请充值后恢复审核。")
                    detail.takeIf(String::isNotBlank)?.let { append("\n$it") }
                },
                NotificationType.WARNING,
            )
            .notify(panel.project)
    }
}
