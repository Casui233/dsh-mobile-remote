# 3.21-actionsfix

## 新增（自 3.19 起）

- **审批直通**：审批通知直接「批准 / 拒绝」，对应 DSH 的 `allowed-once` / `rejected`。
  - 桥接层持有 approval 请求，与 PC Web UI 竞速；谁先决定谁生效；
  - 动作 token 一次性、2 分钟过期；PC 先决定后手机旧按钮提示已过期；
  - 手机先决定时，PC 审批面板会随会话事件结束。
- **提问直通**：提问通知「回答」打开 App 内回答页；单选/多选/自由输入，提交结构化 `answers` 给 DSH。
- **通知动作自动收起**：点「批准/拒绝/回答」后取消原通知，不再残留。

## 继承 3.19 的能力

- 微信聊天记录 zip 自动解包、分享到 DSH `shared/inbox/`；
- 通知快捷动作「打开 / 稍后 10 分钟」；
- 多电脑切换、文本剪贴板同步、文件传输、混合格式多选、悬浮菜单；
- App 内扫码、PC 二维码配对；
- Tailscale 安装指导（含无 Google Play 的官方 APK）。

## 兼容

- `versionCode 36`、`versionName 3.21-actionsfix`；minSdk 26 / targetSdk 33；
- 同签名覆盖安装，配对/通知数据保留；
- 已实测 Android 16 / HyperOS（审批/提问测试动作 + 通知收起）。

## 产物

- `DeepSeek-Harness_3.21-actionsfix.apk`
  - SHA-256：`3105dcb1f61816df80a191d819ea9e33b0415faa6364de8a355b9bfd6fbf3b67`
  - 89,290,886 字节
- `local-dsh-mobile-bridge-0.1.1.tgz`（含 `/clipboard`、`/remote-action`）

## 已知限制

- 审批/提问直通只在通知渠道为 ntfy 时启用；Bark/Telegram 仍只有普通通知；
- 动作 token 2 分钟过期；超时后需重新触发；
- 审批只支持 allowed-once / rejected，不支持「总是允许」策略；
- 同一台 PC 多账号会覆盖配置；微信仅识别「聊天记录」zip；
- ntfy topic 是 bearer secret，手机丢失时换 topic。
