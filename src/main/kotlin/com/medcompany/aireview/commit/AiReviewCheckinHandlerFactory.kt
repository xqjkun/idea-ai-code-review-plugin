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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AiReviewCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        AiReviewCheckinHandler(panel, commitContext)
}

private class AiReviewCheckinHandler(
    private val panel: CheckinProjectPanel,
    private val commitContext: CommitContext,
) : CheckinHandler(), CommitCheck, DumbAware {
    private val settings = AiReviewSettings.getInstance()
    private var manualFixBypass: ManualFixBypass? = null

    override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.LATE

    override fun isEnabled(): Boolean = settings.state.enabled && panel.vcsIsAffected("Git")

    override fun checkinSuccessful() {
        manualFixBypass = null
    }

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val config = settings.snapshot()
        var reviewedPatch: CommitPatch? = null
        return try {
            val patch = withContext(Dispatchers.IO) {
                CommitPatchBuilder.build(
                    project = panel.project,
                    changes = commitInfo.committedChanges,
                    commitContext = commitContext,
                    config = config,
                )
            }
            reviewedPatch = patch

            val bypass = manualFixBypass
            if (bypass != null && bypass.appliesTo(patchFingerprint(patch), System.currentTimeMillis())) {
                showManualFixBypassNotification()
                return null
            }
            if (bypass != null) {
                manualFixBypass = null
                panel.setCommitMessage(removeAiReviewTrailers(panel.commitMessage))
                showRereviewNotification(
                    changed = bypass.reviewedPatchFingerprint != patchFingerprint(patch),
                )
            }

            val apiKey = settings.getApiKey()
            if (apiKey.isBlank()) {
                return failureProblem("尚未配置 DeepSeek API Key，请打开 Settings → Tools → AI Code Review。", config, patch)
            }

            val result = withContext(Dispatchers.IO) {
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
                commitProblem(result, config, patch)
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

    private fun commitProblem(
        report: ReviewReport,
        config: AiReviewConfig,
        reviewedPatch: CommitPatch? = null,
    ): AiReviewCommitProblem = AiReviewCommitProblem(
        report = report,
        config = config,
        onOverrideAccepted = { reason ->
            panel.setCommitMessage(appendOverrideTrailers(panel.commitMessage, reason))
        },
        onEditRequested = {
            if (report.error == null && reviewedPatch != null && reviewedPatch.text.isNotBlank()) {
                manualFixBypass = ManualFixBypass(
                    reviewedPatchFingerprint = patchFingerprint(reviewedPatch),
                    expiresAtMillis = System.currentTimeMillis() + MANUAL_FIX_BYPASS_MILLIS,
                )
                panel.setCommitMessage(appendReviewedWithoutChangesTrailer(panel.commitMessage))
            }
        },
    )

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

    private fun showManualFixBypassNotification() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Code Review")
            .createNotification(
                "代码未发生变化，已采用上次 AI 报告",
                "本次不再调用 DeepSeek，正在继续 IDEA 其他提交检查；提交成功后恢复正常 AI 审核。",
                NotificationType.INFORMATION,
            )
            .notify(panel.project)
    }

    private fun showRereviewNotification(changed: Boolean) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Code Review")
            .createNotification(
                if (changed) "检测到提交代码已变化，重新执行 AI 审核" else "上次报告查看凭证已过期，重新执行 AI 审核",
                if (changed) "上次报告已失效，正在把最新 Diff 发送给 DeepSeek。"
                else "查看报告已超过 30 分钟，正在重新确认当前 Diff。",
                NotificationType.INFORMATION,
            )
            .notify(panel.project)
    }

    companion object {
        private const val MANUAL_FIX_BYPASS_MILLIS = 30 * 60 * 1000L
    }
}

internal data class ManualFixBypass(
    val reviewedPatchFingerprint: String,
    val expiresAtMillis: Long,
) {
    fun appliesTo(currentPatchFingerprint: String, nowMillis: Long): Boolean =
        reviewedPatchFingerprint.isNotBlank() &&
            nowMillis <= expiresAtMillis &&
            reviewedPatchFingerprint == currentPatchFingerprint
}

internal fun patchFingerprint(patch: CommitPatch): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(patch.text.toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    digest.update(patch.files.sorted().joinToString("\n").toByteArray(StandardCharsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }
}
