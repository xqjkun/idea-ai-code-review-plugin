package com.medcompany.aireview.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.medcompany.aireview.model.ReviewFinding
import com.medcompany.aireview.model.ReviewReport
import com.medcompany.aireview.model.Severity
import com.medcompany.aireview.settings.AiReviewConfig
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.html.HTMLEditorKit

class AiReviewReportPanel(
    private val project: Project,
    private val report: ReviewReport,
    private val config: AiReviewConfig,
    private val beforeNavigate: () -> Unit = {},
    private val navigationLabel: String = "打开并定位代码",
) : JBScrollPane() {
    init {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        viewport.view = buildContent()
    }

    private fun buildContent(): JComponent {
        val content = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        var row = 0

        fun add(component: JComponent, top: Int = 0) {
            content.add(component, constraints(row++, top))
        }

        val blocking = report.blockingFindings(config)
        val conclusion = when {
            report.error != null -> "⚠ AI 审核未完成"
            blocking.isNotEmpty() -> "⛔ 发现 ${blocking.size} 个阻断问题"
            else -> "✅ AI 审核通过"
        }
        add(wrappedText(conclusion, bold = true))
        add(
            wrappedText(
                "模型：${report.model}    文件：${report.fileCount}    Diff：${report.diffBytes} 字节    " +
                    "本次逻辑上下文：${report.contextFileCount} 个文件 / ${report.contextBytes} 字节",
            ),
            top = 4,
        )
        add(markdownText(report.error ?: report.summary), top = 8)

        add(sectionLabel("审核发现"), top = 14)
        when {
            report.error != null -> add(markdownText("审核未完成，不能得出“未发现问题”的结论。"), top = 6)
            report.findings.isEmpty() -> add(wrappedText("未发现问题。"), top = 6)
            else -> report.findings.forEach { finding -> add(findingCard(finding), top = 8) }
        }

        add(sectionLabel("建议补充的测试"), top = 14)
        add(
            markdownText(
                if (report.testSuggestions.isEmpty()) "无。"
                else report.testSuggestions.joinToString("\n") { "- $it" },
            ),
            top = 6,
        )

        content.add(JPanel().apply { isOpaque = false }, constraints(row, 8).apply {
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })
        return content
    }

    private fun findingCard(finding: ReviewFinding): JComponent {
        val card = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(severityColor(finding.severity)),
                BorderFactory.createEmptyBorder(9, 10, 9, 10),
            )
        }
        var row = 0

        fun add(component: JComponent, top: Int = 0) {
            card.add(component, constraints(row++, top))
        }

        add(markdownText("[${finding.severity}] ${finding.title}", bold = true))
        add(wrappedText("位置：${finding.file}:${finding.line}"), top = 4)
        add(ActionLink("$navigationLabel · ${displayFileName(finding.file)}:${finding.line}") {
            openFinding(finding)
        }.apply {
            toolTipText = "${finding.file}:${finding.line}"
        }, top = 3)
        add(markdownText("**问题：** ${finding.description.ifBlank { "无补充说明。" }}"), top = 7)
        add(markdownText("**建议：** ${finding.suggestion.ifBlank { "请结合上下文修复。" }}"), top = 6)
        return card
    }

    private fun openFinding(finding: ReviewFinding) {
        val path = resolveFindingPath(project.basePath, finding.file)
        val virtualFile = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (virtualFile == null) {
            Messages.showWarningDialog(
                project,
                "无法在当前项目中找到 ${finding.file}，文件可能已删除或 AI 返回了不准确的路径。",
                "AI 代码审核",
            )
            return
        }
        val requestedLine = (finding.line - 1).coerceAtLeast(0)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        val targetLine = document?.let { requestedLine.coerceAtMost((it.lineCount - 1).coerceAtLeast(0)) }
            ?: requestedLine
        val descriptor = OpenFileDescriptor(project, virtualFile, targetLine, 0)
        beforeNavigate()
        SwingUtilities.invokeLater {
            if (!project.isDisposed) {
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)?.let { editor ->
                    editor.caretModel.moveToLogicalPosition(LogicalPosition(targetLine, 0))
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                    editor.contentComponent.requestFocusInWindow()
                }
            }
        }
    }

    private fun wrappedText(text: String, bold: Boolean = false): JBTextArea = JBTextArea(text, 1, 72).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder()
        if (bold) font = font.deriveFont(Font.BOLD)
        caretPosition = 0
    }

    private fun markdownText(markdown: String, bold: Boolean = false): JEditorPane {
        val textColor = UIManager.getColor("Label.foreground") ?: foreground
        val codeBackground = UIManager.getColor("TextField.background") ?: background
        val editorKit = HTMLEditorKit().apply {
            val fontWeight = if (bold) "bold" else "normal"
            styleSheet.addRule("body { margin: 0; color: ${textColor.toCssColor()}; font-weight: $fontWeight; }")
            styleSheet.addRule("p { margin-top: 2px; margin-bottom: 5px; }")
            styleSheet.addRule("h1, h2, h3, h4, h5, h6 { margin-top: 7px; margin-bottom: 4px; }")
            styleSheet.addRule("ul, ol { margin-top: 3px; margin-bottom: 5px; }")
            styleSheet.addRule("blockquote { margin-left: 12px; color: ${textColor.toCssColor()}; }")
            styleSheet.addRule("code { font-family: monospace; background-color: ${codeBackground.toCssColor()}; }")
            styleSheet.addRule("pre { margin-top: 5px; margin-bottom: 7px; padding: 6px; background-color: ${codeBackground.toCssColor()}; }")
        }
        return WrappingHtmlPane().apply {
            contentType = "text/html"
            this.editorKit = editorKit
            isEditable = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
            font = UIManager.getFont("Label.font") ?: font
            foreground = textColor
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            text = "<html><body>${SafeMarkdownRenderer.render(markdown)}</body></html>"
            caretPosition = 0
        }
    }

    private fun sectionLabel(text: String): JBLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
    }

    private fun constraints(row: Int, top: Int): GridBagConstraints = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.NORTHWEST
        insets = Insets(top, 0, 0, 0)
    }

    private fun severityColor(severity: Severity): Color = when (severity) {
        Severity.CRITICAL -> JBColor(Color(170, 35, 35), Color(255, 105, 105))
        Severity.HIGH -> JBColor(Color(205, 75, 45), Color(255, 135, 95))
        Severity.MEDIUM -> JBColor(Color(190, 135, 25), Color(235, 185, 70))
        Severity.LOW -> JBColor(Color(75, 120, 165), Color(115, 165, 215))
    }
}

private class WrappingHtmlPane : JEditorPane() {
    override fun getPreferredSize(): Dimension {
        val availableWidth = parent?.width?.minus(parent.insets.left + parent.insets.right)
            ?.takeIf { it > 0 }
            ?: 720
        setSize(availableWidth, Short.MAX_VALUE.toInt())
        return super.getPreferredSize().let { Dimension(availableWidth, it.height) }
    }

    override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
}

private fun Color.toCssColor(): String = "#%02x%02x%02x".format(red, green, blue)

internal fun resolveFindingPath(basePath: String?, reportedPath: String): Path? {
    val root = basePath?.takeIf(String::isNotBlank)?.let {
        runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull()
    } ?: return null
    val cleaned = reportedPath.trim()
        .trim('`', '"', '\'')
        .replace('\\', '/')
        .removePrefix("./")
        .removePrefix("a/")
        .removePrefix("b/")
        .takeIf(String::isNotBlank)
        ?: return null
    val reported = runCatching { Paths.get(cleaned) }.getOrNull() ?: return null
    val candidate = (if (reported.isAbsolute) reported else root.resolve(reported)).toAbsolutePath().normalize()
    return candidate.takeIf { it.startsWith(root) }
}

internal fun displayFileName(path: String): String =
    path.replace('\\', '/').substringAfterLast('/').ifBlank { path }
