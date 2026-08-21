package com.medcompany.aireview.service

import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.CommitContext
import com.medcompany.aireview.model.AiReviewException
import com.medcompany.aireview.model.CommitPatch
import com.medcompany.aireview.settings.AiReviewConfig
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

object CommitPatchBuilder {
    private val excludedNames = setOf(
        "package-lock.json",
        "pnpm-lock.yaml",
        "yarn.lock",
    )
    private val excludedExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "jar", "class", "map",
    )

    fun build(
        project: Project,
        changes: Collection<Change>,
        commitContext: CommitContext,
        config: AiReviewConfig,
    ): CommitPatch {
        val basePath = project.basePath ?: throw AiReviewException("当前项目没有可用的根目录")
        val included = changes.filterNot(::shouldExclude)
        if (included.size > config.maxFiles) {
            throw AiReviewException(
                "本次提交包含 ${included.size} 个文本文件，超过上限 ${config.maxFiles}。请拆分提交后重新审核。",
            )
        }
        if (included.isEmpty()) return CommitPatch("", emptyList(), 0)

        val writer = StringWriter()
        try {
            val patches = IdeaTextPatchBuilder.buildPatch(
                project,
                included,
                Paths.get(basePath),
                false,
                true,
            )
            UnifiedDiffWriter.write(project, patches, writer, "\n", commitContext)
        } catch (error: VcsException) {
            throw AiReviewException("无法生成本次提交的 patch：${error.message}", error)
        } catch (error: Exception) {
            throw AiReviewException("生成 patch 失败：${error.message}", error)
        }

        val patchText = writer.toString()
        val bytes = patchText.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > config.maxDiffBytes) {
            throw AiReviewException(
                "本次提交的 Diff 为 $bytes 字节，超过上限 ${config.maxDiffBytes}。请拆分提交，避免 AI 截断漏审。",
            )
        }
        val files = included.map { change -> relativePath(basePath, ChangesUtil.getFilePath(change).ioFile) }.distinct()
        val context = ReviewContextCollector.collect(basePath, included, patchText, config)
        return CommitPatch(
            text = patchText,
            files = files,
            bytes = bytes,
            contextText = context.text,
            contextFiles = context.files,
            contextBytes = context.bytes,
        )
    }

    private fun shouldExclude(change: Change): Boolean {
        if (IdeaTextPatchBuilder.isBinaryRevision(change.beforeRevision) ||
            IdeaTextPatchBuilder.isBinaryRevision(change.afterRevision)
        ) {
            return true
        }
        val filePath = ChangesUtil.getFilePath(change)
        val name = filePath.name.lowercase()
        val extension = name.substringAfterLast('.', "")
        val normalized = filePath.path.replace('\\', '/')
        return name in excludedNames ||
            extension in excludedExtensions ||
            "/node_modules/" in normalized ||
            "/dist/" in normalized ||
            "/coverage/" in normalized
    }

    private fun relativePath(basePath: String, file: File): String =
        FileUtil.getRelativePath(File(basePath), file)?.replace(File.separatorChar, '/') ?: file.path
}
