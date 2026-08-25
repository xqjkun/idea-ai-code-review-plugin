# JetBrains Marketplace 发布配置

## 第一次发布

JetBrains 要求插件第一次发布由作者在 Marketplace 页面手工完成：

1. 登录 JetBrains Marketplace，接受开发者协议并创建 Vendor Profile。
2. 上传 GitHub Release 中的插件 ZIP。
3. 插件 XML ID 使用 `com.medcompany.ai-code-review-gate`。
4. 许可证选择 Apache License 2.0，源码地址填写 `https://github.com/xqjkun/idea-ai-code-review-plugin`。
5. 确认 Vendor 名称后提交审核。插件首次上线前不要随意修改 XML ID。

## 自动发布凭据

第一次上传完成后，在 JetBrains Marketplace 个人资料的 **My Tokens** 页面创建永久 Token。然后打开 GitHub 仓库：

```text
Settings → Secrets and variables → Actions → New repository secret
```

配置以下 Repository Secrets：

| Secret | 内容 |
| --- | --- |
| `PUBLISH_TOKEN` | JetBrains Marketplace 永久 Token |
| `CERTIFICATE_CHAIN` | PEM 格式的签名证书链 |
| `PRIVATE_KEY` | PEM 格式的 RSA 私钥 |
| `PRIVATE_KEY_PASSWORD` | 私钥密码 |

不要把上述内容发送到聊天、写入代码、放入 Release 或提交到 Git。

## 后续发布

更新 `build.gradle.kts` 的版本和 `CHANGELOG.md` 后提交到 `main`，再推送对应 Tag：

```bash
git tag v0.5.3
git push origin v0.5.3
```

工作流会依次执行测试、插件构建、8 个 IDEA 版本兼容性验证、GitHub Release、插件签名和 JetBrains Marketplace 上传。四个 Marketplace Secret 未配置完整时，仅跳过最后的 Marketplace 发布，不影响 GitHub Release。
