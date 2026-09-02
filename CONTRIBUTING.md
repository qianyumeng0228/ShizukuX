# 参与 ShizukuX 开发

感谢你考虑为本项目做贡献——无论是 bug 修复、翻译，还是新的 Plus API 相关工作，我们都非常欢迎。

## 开始之前

- **先查看已有的 Issue 和 PR。** 快速搜索能为所有人节省时间——见[打开的 Issues](https://github.com/qianyumeng0228/ShizukuX/issues)和[打开的 PRs](https://github.com/qianyumeng0228/ShizukuX/pulls)。
- **任何非琐碎改动，先开一个 Issue** 讨论方案，再动手写代码。小改动（错别字、明显 bug、翻译）则不需要。
- **阅读 [wiki](https://github.com/qianyumeng0228/ShizukuX/wiki)** 了解架构背景，如果你是新手，尤其建议先看 [Shizuku 与 ShizukuX 对比](https://github.com/qianyumeng0228/ShizukuX/wiki/Shizuku-vs-Shizuku%2B)。

## 开发环境

- 参与开发**无需本地 Android SDK**——本项目只通过 GitHub Actions（`.github/workflows/app.yml`）构建。如果你有本地 SDK，`./gradlew :manager:assembleRelease` 是验证构建命令。
- 大部分代码由两个构建 flavor 共享：`Shizukuplus`（`af.shizuku.plus.api`，可与原版 Shizuku 共存）和 `Dropin`（`moe.shizuku.privileged.api`，直接替换原版）。请确认你的改动需要应用到哪个 flavor。
- 切勿提交或修改 `key.jks`、`signing.properties` 或任何匹配 `secrets*` 的文件。

## 提交 PR

- 保持 PR 聚焦——一个 PR 只做一个修复或一个功能，比打包一堆改动更容易审查。
- 提交信息格式：`<type>: <what> - <why>`（例如 `fix: null-check in FooBar.init - crashes on API 29`）。
- 如果 PR 打开已久，请在请求审查前先 rebase 到当前 `master`——由你来解决冲突比让审查者处理容易得多。
- 请描述你如何测试了这次改动（设备/模拟器 + Android 版本）——大多数 UI 流程没有 CI 运行的仪器化测试覆盖，所以这确实是很有价值的信号，而不是形式主义。

## 翻译

非常欢迎翻译贡献。如果你要新增或更新某个语言，请尽量本地化完整的字符串集，而不要只做一部分——参考 `manager/src/main/res/values-*/` 中已有的语言作为对照。

## 安全问题

发现安全漏洞时请**不要**公开开 Issue——请参阅 [SECURITY.md](SECURITY.md) 了解负责任披露流程。
