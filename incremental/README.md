# 增量包（Incremental Package）使用说明

本目录用于存放「增量包」——即把若干文件改动打包成 `.zip`，由 GitHub Actions 自动解压覆盖并构建 APK。

## 增量包里包含什么

增量包内是**仓库相对路径**下的完整文件（不是 diff），解压后直接覆盖对应位置。
例如 `agora-local-model-control.zip` 包含：

```
app/src/main/java/com/newoether/agora/api/local/LocalProvider.kt
app/src/main/java/com/newoether/agora/ui/settings/SettingsLocalModelPage.kt
app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt
incremental/README.md
```

> 注意：增量包**不要**包含 `.github/workflows/` 下的文件。工作流文件由仓库单独维护一次即可，
> 否则 GITHUB_TOKEN 在推送时会因缺少 `workflows` 权限而失败。

## 工作流文件（只需提交一次）

`.github/workflows/apply-incremental.yml` 负责「解压覆盖 → 提交源码 → 构建 APK → 上传产物」。
它需要在仓库里存在（用你自己的账号通过 Web UI 或本地 `git push` 提交一次即可，你有 workflows 写权限）。

## 触发方式（二选一）

1. **自动触发**：把增量包放到 `incremental/` 目录并提交到 `master`。
   只要 `incremental/**.zip` 有变动，工作流自动运行，解压覆盖后构建 APK。
2. **手动触发**：在仓库 Actions 页面对 `Apply Incremental Package & Build APK` 点 Run workflow，
   可指定包路径（默认 `incremental/agora-local-model-control.zip`）。

## 产物

构建完成后，APK 作为 Action 产物（artifact）上传，名为 `app-debug-apk`，可在对应运行记录里下载。
如需 release 签名包，请在仓库 Secrets 配置签名信息，并将构建步骤改为 `assembleRelease`。
