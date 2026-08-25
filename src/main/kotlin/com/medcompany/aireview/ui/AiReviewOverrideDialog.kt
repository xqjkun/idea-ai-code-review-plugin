package com.medcompany.aireview.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.medcompany.aireview.model.ReviewReport
import com.medcompany.aireview.settings.AiReviewConfig
import java.awt.Dimension
import javax.swing.JComponent

class AiReviewOverrideDialog(
    private val project: Project,
    private val report: ReviewReport,
    private val config: AiReviewConfig,
    private val onEditRequested: () -> Unit,
) : DialogWrapper(project) {
    private val reasonField = JBTextArea(3, 72).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "例如：AI 对框架约束判断错误，已人工检查调用链和测试结果"
    }
    private val acknowledged = JBCheckBox("我已人工复核本次改动，并知晓忽略 AI 审核结果的风险")

    val reason: String
        get() = reasonField.text.trim()

    init {
        title = if (report.error != null) "AI 审核未完成" else "AI 审核发现高风险问题"
        setOKButtonText("仍然提交")
        setCancelButtonText("取消提交")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("可保留报告查看代码；代码未变化时下次 Commit 直接继续，发生修改则重新 AI 审核。"))
            .addComponent(
                AiReviewReportPanel(
                    project = project,
                    report = report,
                    config = config,
                    beforeNavigate = {
                        onEditRequested()
                        close(CANCEL_EXIT_CODE)
                        AiReviewReportDialog(project, report, config).show()
                    },
                    navigationLabel = "查看代码并保留报告",
                ).apply { preferredSize = Dimension(820, 380) },
            )
            .addLabeledComponent(JBLabel("强制提交原因："), JBScrollPane(reasonField), 1, true)
            .addComponent(JBLabel("确认后原因会以 AI-Review Trailer 写入 Git 提交信息，并在 GitLab 中可见。"))
            .addComponent(acknowledged)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = reasonField

    override fun doValidate(): ValidationInfo? =
        overrideValidationMessage(reason, acknowledged.isSelected)?.let { message ->
            ValidationInfo(message, if (reason.length < MIN_REASON_LENGTH) reasonField else acknowledged)
        }

    companion object {
        internal const val MIN_REASON_LENGTH = 5
    }
}

internal fun overrideValidationMessage(reason: String, acknowledged: Boolean): String? = when {
    reason.trim().length < AiReviewOverrideDialog.MIN_REASON_LENGTH ->
        "请填写至少 ${AiReviewOverrideDialog.MIN_REASON_LENGTH} 个字符的强制提交原因"
    !acknowledged -> "请确认已经人工复核本次改动"
    else -> null
}
