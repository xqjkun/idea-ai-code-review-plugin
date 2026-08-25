package com.medcompany.aireview.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.medcompany.aireview.model.ReviewReport
import com.medcompany.aireview.settings.AiReviewConfig
import java.awt.Dimension
import javax.swing.JComponent

class AiReviewReportDialog(
    private val project: Project,
    private val report: ReviewReport,
    private val config: AiReviewConfig,
) : DialogWrapper(project) {
    init {
        isModal = false
        title = "AI 代码审核报告"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent =
        AiReviewReportPanel(project, report, config).apply { preferredSize = Dimension(820, 580) }

    override fun createActions() = arrayOf(okAction)
}
