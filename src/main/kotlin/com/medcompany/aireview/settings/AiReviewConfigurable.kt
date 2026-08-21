package com.medcompany.aireview.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.medcompany.aireview.service.DeepSeekReviewClient
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class AiReviewConfigurable : Configurable {
    private val settings = AiReviewSettings.getInstance()
    private var rootPanel: JPanel? = null
    private val enabled = JBCheckBox("提交前自动执行 AI 审核")
    private val apiUrl = JBTextField()
    private val model = JBTextField()
    private val apiKey = JBPasswordField()
    private val maxDiffKb = JSpinner(SpinnerNumberModel(180, 16, 1024, 16))
    private val maxContextKb = JSpinner(SpinnerNumberModel(240, 32, 1024, 16))
    private val maxFiles = JSpinner(SpinnerNumberModel(80, 1, 500, 10))
    private val maxRelatedFiles = JSpinner(SpinnerNumberModel(12, 0, 50, 1))
    private val blockMedium = JBCheckBox("MEDIUM 级别也阻止提交")
    private val customFocus = JBTextArea(6, 50)

    override fun getDisplayName(): String = "AI Code Review"

    override fun createComponent(): JComponent {
        val testButton = JButton("测试连接")
        testButton.addActionListener { testConnection(testButton) }
        val keyPanel = JPanel(BorderLayout(8, 0)).apply {
            add(apiKey, BorderLayout.CENTER)
            add(testButton, BorderLayout.EAST)
        }
        customFocus.lineWrap = true
        customFocus.wrapStyleWord = true

        rootPanel = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent(JBLabel("DeepSeek API 地址："), apiUrl, 1, false)
            .addLabeledComponent(JBLabel("模型："), model, 1, false)
            .addLabeledComponent(JBLabel("API Key："), keyPanel, 1, false)
            .addComponent(JBLabel("Key 保存在 IntelliJ Password Safe，不写入项目文件。"))
            .addLabeledComponent(JBLabel("最大 Diff（KB）："), maxDiffKb, 1, false)
            .addLabeledComponent(JBLabel("最大逻辑上下文（KB）："), maxContextKb, 1, false)
            .addLabeledComponent(JBLabel("最大文件数："), maxFiles, 1, false)
            .addLabeledComponent(JBLabel("最大关联文件数："), maxRelatedFiles, 1, false)
            .addComponent(JBLabel("上下文包含修改前/后逻辑、直接依赖和调用方；不会无限扫描或上传整个仓库。"))
            .addComponent(blockMedium)
            .addLabeledComponent(JBLabel("项目审核重点（每行一项）："), JBScrollPane(customFocus), 1, true)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return rootPanel!!
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return enabled.isSelected != state.enabled ||
            apiUrl.text.trim() != state.apiUrl ||
            model.text.trim() != state.model ||
            String(apiKey.password).trim() != settings.getApiKey() ||
            maxDiffKb.value as Int != state.maxDiffKb ||
            maxContextKb.value as Int != state.maxContextKb ||
            maxFiles.value as Int != state.maxFiles ||
            maxRelatedFiles.value as Int != state.maxRelatedFiles ||
            blockMedium.isSelected != state.blockMedium ||
            customFocus.text.trim() != state.customFocus.trim()
    }

    override fun apply() {
        validationError()?.let { throw ConfigurationException(it) }
        settings.state.apply {
            enabled = this@AiReviewConfigurable.enabled.isSelected
            apiUrl = this@AiReviewConfigurable.apiUrl.text.trim()
            model = this@AiReviewConfigurable.model.text.trim()
            maxDiffKb = this@AiReviewConfigurable.maxDiffKb.value as Int
            maxContextKb = this@AiReviewConfigurable.maxContextKb.value as Int
            maxFiles = this@AiReviewConfigurable.maxFiles.value as Int
            maxRelatedFiles = this@AiReviewConfigurable.maxRelatedFiles.value as Int
            blockMedium = this@AiReviewConfigurable.blockMedium.isSelected
            customFocus = this@AiReviewConfigurable.customFocus.text.trim()
        }
        settings.setApiKey(String(apiKey.password))
    }

    override fun reset() {
        val state = settings.state
        enabled.isSelected = state.enabled
        apiUrl.text = state.apiUrl
        model.text = state.model
        apiKey.text = settings.getApiKey()
        maxDiffKb.value = state.maxDiffKb
        maxContextKb.value = state.maxContextKb
        maxFiles.value = state.maxFiles
        maxRelatedFiles.value = state.maxRelatedFiles
        blockMedium.isSelected = state.blockMedium
        customFocus.text = state.customFocus
    }

    override fun disposeUIResources() {
        rootPanel = null
        apiKey.text = ""
    }

    private fun validateInputs() {
        validationError()?.let { throw IllegalArgumentException(it) }
    }

    private fun validationError(): String? = when {
        !apiUrl.text.trim().startsWith("https://") -> "API 地址必须使用 HTTPS"
        model.text.isBlank() -> "模型不能为空"
        String(apiKey.password).isBlank() -> "API Key 不能为空"
        else -> null
    }

    private fun testConnection(button: JButton) {
        try {
            validateInputs()
        } catch (error: IllegalArgumentException) {
            Messages.showErrorDialog(error.message, "AI Code Review")
            return
        }

        button.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                DeepSeekReviewClient().testConnection(
                    apiUrl.text.trim(),
                    model.text.trim(),
                    String(apiKey.password).trim(),
                )
            }
            SwingUtilities.invokeLater {
                button.isEnabled = true
                result.onSuccess {
                    Messages.showInfoMessage("DeepSeek 连接成功。", "AI Code Review")
                }.onFailure {
                    Messages.showErrorDialog(it.message ?: "连接失败", "AI Code Review")
                }
            }
        }
    }
}
