# AI Code Review Gate for IntelliJ IDEA

一个面向团队内部使用的 IntelliJ IDEA 插件。在开发者点击 Git Commit 时，插件读取本次实际选中的 changes，调用 DeepSeek 审核代码；发现 `CRITICAL` 或 `HIGH` 问题时取消提交并显示结构化报告。

同一个安装包支持 IntelliJ IDEA 2024.1 至 2026.2（平台构建号 `241`–`262.*`），无需按 IDEA 版本分别打包。

版本变化请查看 [更新记录](CHANGELOG.md)。

## 功能

- 使用 IntelliJ 官方异步 `CommitCheck`，审核过程中不冻结 IDE。
- 支持 IDEA 部分提交，patch 来源是本次 Commit 实际选中的 changes。
- 同时审核修改文件的提交前/后逻辑；大文件保留变更点前后约 80 行。
- 自动解析 `import` / `require`，补充直接依赖和引用修改文件的调用方，检查跨文件勾稽关系。
- 每位开发者填写自己的 DeepSeek API Key。
- Key 保存到 IntelliJ Password Safe，不进入项目文件或 Git。
- 默认使用 `deepseek-v4-flash`，API 地址和模型可配置；旧的官方 `deepseek-chat` 配置会自动迁移。
- `CRITICAL/HIGH` 默认拦截；可选择让 `MEDIUM` 也拦截。
- AI 定位为辅助工具：查看报告、填写原因并确认已人工复核后，可仅对本次操作强制提交。
- 强制提交会把 `AI-Review: overridden` 和 `AI-Review-Reason: ...` 作为 Git Trailer 追加到本次 Commit Message，GitLab 可查看和检索。
- 审核发现按问题卡片展示，点击“打开并定位代码”会打开对应文件、移动光标到报告行并获得编辑器焦点；非模态报告窗口会继续保留。
- AI 阻断后可选择“查看代码并保留报告”：报告以非模态窗口留在旁边；Diff 未变化时再次 Commit 可直接继续，任何代码变化都会让旧报告失效并重新调用 DeepSeek。
- 摘要、问题说明、修复建议和测试建议支持安全的 Markdown 富文本显示，包括标题、列表、粗体、斜体、行内代码和代码块。
- API 超时、Key 无效、响应格式错误时采用 fail-closed：取消提交。
- DeepSeek 返回余额不足时会明确警告“本次代码未经过 AI 审核”，但自动放行本次提交；充值后后续提交会自动恢复审核。
- 锁文件、构建产物和常见二进制文件不会发送给模型。
- 超过文件数或 Diff 大小限制时要求拆分提交，避免模型截断漏审。

## 安装

构建产物位于：

```text
build/distributions/idea-ai-code-review-plugin-0.5.2.zip
```

在 IDEA 中打开：

```text
Settings → Plugins → ⚙ → Install Plugin from Disk
```

选择 ZIP，重启 IDEA。

## 每位开发者配置

打开：

```text
Settings → Tools → AI Code Review
```

填写自己的 DeepSeek API Key，点击“测试连接”，成功后 Apply。不要把 Key 发到聊天、提交到 Git 或保存在项目配置中。

## 使用

1. 在 IDEA Commit 窗口选择准备提交的文件或改动。
2. 正常填写 Commit Message 并点击 Commit。
3. 插件自动生成 unified patch 并调用 DeepSeek。
4. 插件采集修改前/后快照和有限的关联文件，用于分析调用链、DTO/实体契约、金额汇总和状态流转。
5. 无阻断问题时继续 Commit。
6. 出现阻断问题或普通 API 错误时取消 Commit，并提供“查看 AI 审核报告”；仅余额不足会警告后自动继续提交。

## 构建

构建需要 JDK 17 或更高版本，插件本身生成 Java 17 字节码。默认使用 IDEA 2024.1.7 SDK 编译，以保证最低版本兼容性：

```bash
export JAVA_HOME='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home'
./gradlew clean test buildPlugin
```

构建产物生成后，可针对各主要 IDEA 版本运行 JetBrains Plugin Verifier：

```bash
./gradlew verifyPlugin
```

当前验证矩阵：2024.1.7、2024.2.6、2024.3.7.1、2025.1.7.2、2025.2.6.3、2025.3.6.1、2026.1.3 和 2026.2.1。

如果开发时显式传入 `-PideaHome=...`，会改用本机 IDEA SDK；发布前请务必再不带该参数完整构建，避免无意中使用高版本 API。

## 管理边界

IDEA 插件能够改善提交前体验，但开发者可以禁用插件、使用命令行提交或卸载插件，因此它不能单独形成不可绕过的团队门禁。建议继续保留 GitLab Merge Request Pipeline 的 AI 审核 Job，并启用 `Pipelines must succeed`；IDEA 插件负责及时反馈，GitLab 负责最终约束和审计。

代码 diff 会发送到配置的 DeepSeek API。公司代码如有数据合规要求，应改用内部 AI 网关，并为 Key 配置额度和访问限制。

## 开源许可

本项目使用 [Apache License 2.0](LICENSE) 开源许可。
