package com.medcompany.aireview.service

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.medcompany.aireview.settings.AiReviewConfig
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.name

internal data class ReviewContext(
    val text: String,
    val files: List<String>,
    val bytes: Int,
)

internal object ReviewContextCollector {
    private const val FULL_SNAPSHOT_BYTES = 32 * 1024
    private const val RELATED_FILE_BYTES = 48 * 1024
    private const val CONTEXT_LINES = 80
    private const val MAX_SCANNED_FILES = 5_000
    private const val MAX_SCANNED_CONTENT_BYTES = 12 * 1024 * 1024
    private const val MAX_SCANNED_FILE_BYTES = 128 * 1024

    private val sourceExtensions = setOf(
        "java", "kt", "kts", "groovy", "xml", "yaml", "yml", "json",
        "js", "jsx", "ts", "tsx", "vue", "css", "scss", "less",
        "py", "go", "rs", "c", "cc", "cpp", "h", "hpp", "cs", "sql",
    )
    private val ignoredSegments = setOf(
        ".git", ".idea", ".gradle", "node_modules", "build", "dist", "coverage", "target", "out", "vendor",
    )
    private val sensitiveNames = setOf(
        ".env", ".credentials", "credentials.json", "secrets.json", "id_rsa", "id_ed25519",
    )
    private val sensitiveExtensions = setOf("pem", "key", "p12", "pfx", "jks", "keystore")
    private val importPatterns = listOf(
        Regex("""(?m)\b(?:import|export)\s+(?:[^\n;]*?\s+from\s+)?[\"']([^\"']+)[\"']"""),
        Regex("""(?m)\brequire\s*\(\s*[\"']([^\"']+)[\"']\s*\)"""),
        Regex("""(?m)^\s*import\s+([A-Za-z_][\w.]*(?:\.\*)?)\s*;?\s*$"""),
        Regex("""(?m)^\s*from\s+([\w.]+)\s+import\s+"""),
    )
    private val hunkPattern = Regex("""@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@""")

    fun collect(
        basePath: String,
        changes: Collection<Change>,
        patchText: String,
        config: AiReviewConfig,
    ): ReviewContext {
        if (config.maxContextBytes <= 0) return ReviewContext("", emptyList(), 0)

        val root = Paths.get(basePath).toAbsolutePath().normalize()
        val hunks = parseHunks(patchText)
        val blocks = mutableListOf<Pair<String, String>>()
        val changedAfter = linkedMapOf<String, String>()
        val changedPaths = linkedSetOf<String>()

        changes.forEach { change ->
            val relativePath = relativePath(basePath, ChangesUtil.getFilePath(change).ioFile)
            changedPaths += relativePath
            val ranges = hunks[relativePath] ?: HunkRanges.EMPTY
            val before = runCatching { change.beforeRevision?.content }.getOrNull()
            val after = runCatching { change.afterRevision?.content }.getOrNull()

            blocks += relativePath to renderSnapshot("提交前", relativePath, before, ranges.before)
            blocks += relativePath to renderSnapshot("提交后", relativePath, after, ranges.after)
            if (after != null) changedAfter[relativePath] = after
        }

        val related = if (config.maxRelatedFiles > 0) {
            findRelatedFiles(root, changedAfter, changedPaths, config.maxRelatedFiles)
        } else {
            emptyList()
        }
        related.forEach { relatedFile ->
            blocks += relatedFile.path to renderRelatedFile(relatedFile)
        }

        val output = StringBuilder()
        val includedFiles = linkedSetOf<String>()
        var remaining = config.maxContextBytes
        blocks.forEach { (path, block) ->
            if (remaining <= 0) return@forEach
            val separator = if (output.isEmpty()) "" else "\n\n"
            val separatorBytes = separator.toByteArray(StandardCharsets.UTF_8).size
            if (remaining <= separatorBytes + 80) return@forEach
            val fitted = truncateUtf8(block, remaining - separatorBytes)
            output.append(separator).append(fitted)
            remaining -= separatorBytes + fitted.toByteArray(StandardCharsets.UTF_8).size
            includedFiles += path
        }

        val bytes = output.toString().toByteArray(StandardCharsets.UTF_8).size
        return ReviewContext(output.toString(), includedFiles.toList(), bytes)
    }

    internal fun extractImports(content: String): Set<String> = buildSet {
        importPatterns.forEach { pattern ->
            pattern.findAll(content).forEach { match ->
                match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    internal fun renderSnapshot(
        label: String,
        path: String,
        content: String?,
        changedRanges: List<IntRange>,
    ): String {
        val heading = "### $label：$path"
        if (content == null) return "$heading\n（文件不存在，可能是新建或删除文件）"

        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        val lines = content.lines()
        val indexes = when {
            bytes <= FULL_SNAPSHOT_BYTES -> lines.indices.toList()
            changedRanges.isNotEmpty() -> selectContextLines(lines.size, changedRanges)
            else -> (
                (0 until minOf(200, lines.size)).toList() +
                    ((lines.size - 80).coerceAtLeast(0) until lines.size).toList()
                ).distinct()
        }
        val mode = if (indexes.size == lines.size) "完整内容" else "变更点前后 ${CONTEXT_LINES} 行摘要"
        return buildString {
            appendLine(heading)
            appendLine("（$mode；行号为该版本文件行号）")
            appendLine("```${language(path)}")
            var previous = -2
            indexes.forEach { index ->
                if (index > previous + 1) appendLine("…… 省略 ${index - previous - 1} 行 ……")
                appendLine("${(index + 1).toString().padStart(6)} | ${lines[index]}")
                previous = index
            }
            append("```")
        }
    }

    private fun renderRelatedFile(file: RelatedFile): String {
        val content = truncateUtf8(file.content, RELATED_FILE_BYTES)
        val truncated = content.length < file.content.length
        return buildString {
            appendLine("### 关联文件：${file.path}")
            appendLine("（关系：${file.relationship}${if (truncated) "；文件较大，已截断" else ""}）")
            appendLine("```${language(file.path)}")
            appendLine(content)
            append("```")
        }
    }

    private fun findRelatedFiles(
        root: Path,
        changedAfter: Map<String, String>,
        changedPaths: Set<String>,
        limit: Int,
    ): List<RelatedFile> {
        val relationships = linkedMapOf<String, LinkedHashSet<String>>()

        changedAfter.forEach { (changedPath, content) ->
            extractImports(content).forEach { reference ->
                resolveImport(root, changedPath, reference)?.let { relatedPath ->
                    if (relatedPath !in changedPaths) {
                        relationships.getOrPut(relatedPath) { linkedSetOf() }
                            .add("被修改文件 $changedPath 直接依赖")
                    }
                }
            }
        }

        if (relationships.size < limit) {
            var scannedBytes = 0L
            for (candidate in sourceFiles(root)) {
                if (relationships.size >= limit) break
                val candidatePath = root.relativize(candidate).toString().replace(File.separatorChar, '/')
                if (candidatePath in changedPaths || candidatePath in relationships) continue
                val size = runCatching { Files.size(candidate) }.getOrNull() ?: continue
                if (size <= 0 || size > MAX_SCANNED_FILE_BYTES) continue
                if (scannedBytes + size > MAX_SCANNED_CONTENT_BYTES) break
                scannedBytes += size
                val content = readSource(candidate) ?: continue
                extractImports(content).forEach { reference ->
                    val resolved = resolveImport(root, candidatePath, reference)
                    if (resolved != null && resolved in changedPaths) {
                        relationships.getOrPut(candidatePath) { linkedSetOf() }
                            .add("该文件引用了修改文件 $resolved")
                    }
                }
            }
        }

        return relationships.entries.take(limit).mapNotNull { (path, relation) ->
            val file = root.resolve(path).normalize()
            val content = readSource(file) ?: return@mapNotNull null
            RelatedFile(path, relation.joinToString("；"), content)
        }
    }

    private fun sourceFiles(root: Path): Sequence<Path> {
        if (!Files.isDirectory(root)) return emptySequence()
        val result = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName.toString().lowercase() in ignoredSegments) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && !shouldIgnore(root, file)) result.add(file)
                    return if (result.size >= MAX_SCANNED_FILES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            })
        }
        return result.asSequence()
    }

    private fun resolveImport(root: Path, importerPath: String, reference: String): String? {
        val importer = root.resolve(importerPath).normalize()
        val bases = when {
            reference.startsWith(".") -> listOf(importer.parent.resolve(reference))
            reference.startsWith("@/") -> listOf(root.resolve("src").resolve(reference.removePrefix("@/")))
            reference.startsWith("~/") -> listOf(root.resolve(reference.removePrefix("~/")))
            reference.contains('.') && !reference.contains('/') -> {
                val javaPath = reference.removeSuffix(".*").replace('.', '/')
                listOf(
                    root.resolve("src/main/java").resolve(javaPath),
                    root.resolve("src/main/kotlin").resolve(javaPath),
                    root.resolve("src").resolve(javaPath),
                )
            }
            else -> emptyList()
        }

        bases.forEach { base ->
            candidatePaths(base).forEach { candidate ->
                val normalized = candidate.toAbsolutePath().normalize()
                if (normalized.startsWith(root) && Files.isRegularFile(normalized) && !shouldIgnore(root, normalized)) {
                    return root.relativize(normalized).toString().replace(File.separatorChar, '/')
                }
            }
        }
        return null
    }

    private fun candidatePaths(base: Path): Sequence<Path> = sequence {
        yield(base)
        sourceExtensions.forEach { extension -> yield(Paths.get("$base.$extension")) }
        sourceExtensions.forEach { extension -> yield(base.resolve("index.$extension")) }
    }

    private fun readSource(path: Path): String? {
        return runCatching {
            val size = Files.size(path)
            if (size <= 0 || size > 512 * 1024) return null
            Files.readString(path, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun shouldIgnore(root: Path, path: Path): Boolean {
        val relative = runCatching { root.relativize(path.toAbsolutePath().normalize()) }.getOrNull() ?: return true
        val segments = relative.map { it.toString().lowercase() }
        val name = path.name.lowercase()
        val extension = path.extension.lowercase()
        return segments.any { it in ignoredSegments } ||
            extension !in sourceExtensions ||
            name in sensitiveNames ||
            extension in sensitiveExtensions
    }

    private fun parseHunks(patch: String): Map<String, HunkRanges> {
        val result = linkedMapOf<String, MutableHunkRanges>()
        var beforePath: String? = null
        var afterPath: String? = null
        patch.lineSequence().forEach { line ->
            when {
                line.startsWith("--- ") -> beforePath = patchPath(line.removePrefix("--- "))
                line.startsWith("+++ ") -> afterPath = patchPath(line.removePrefix("+++ "))
                line.startsWith("@@") -> {
                    val match = hunkPattern.find(line) ?: return@forEach
                    val oldStart = match.groupValues[1].toInt()
                    val oldCount = match.groupValues[2].toIntOrNull() ?: 1
                    val newStart = match.groupValues[3].toInt()
                    val newCount = match.groupValues[4].toIntOrNull() ?: 1
                    val key = afterPath ?: beforePath ?: return@forEach
                    val ranges = result.getOrPut(key) { MutableHunkRanges() }
                    if (oldCount > 0) ranges.before += oldStart..(oldStart + oldCount - 1)
                    if (newCount > 0) ranges.after += newStart..(newStart + newCount - 1)
                }
            }
        }
        return result.mapValues { (_, value) -> HunkRanges(value.before, value.after) }
    }

    private fun patchPath(value: String): String? {
        val path = value.substringBefore('\t').trim()
        if (path == "/dev/null") return null
        return path.removePrefix("a/").removePrefix("b/")
    }

    private fun selectContextLines(lineCount: Int, ranges: List<IntRange>): List<Int> {
        val indexes = linkedSetOf<Int>()
        ranges.forEach { range ->
            val start = (range.first - 1 - CONTEXT_LINES).coerceAtLeast(0)
            val end = (range.last - 1 + CONTEXT_LINES).coerceAtMost(lineCount - 1)
            if (start <= end) indexes.addAll(start..end)
        }
        return indexes.sorted()
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return value
        val suffix = "\n…… 已达上下文上限，后续内容省略 ……"
        val suffixBytes = suffix.toByteArray(StandardCharsets.UTF_8).size
        var low = 0
        var high = value.length
        val target = (maxBytes - suffixBytes).coerceAtLeast(0)
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (value.substring(0, middle).toByteArray(StandardCharsets.UTF_8).size <= target) low = middle else high = middle - 1
        }
        return value.substring(0, low) + suffix
    }

    private fun relativePath(basePath: String, file: File): String =
        FileUtil.getRelativePath(File(basePath), file)?.replace(File.separatorChar, '/') ?: file.path

    private fun language(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        "py" -> "python"
        "yml", "yaml" -> "yaml"
        else -> path.substringAfterLast('.', "")
    }

    private data class RelatedFile(val path: String, val relationship: String, val content: String)
    private data class HunkRanges(val before: List<IntRange>, val after: List<IntRange>) {
        companion object {
            val EMPTY = HunkRanges(emptyList(), emptyList())
        }
    }
    private data class MutableHunkRanges(
        val before: MutableList<IntRange> = mutableListOf(),
        val after: MutableList<IntRange> = mutableListOf(),
    )
}
