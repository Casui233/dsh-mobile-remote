# 3.19-clipboard3

## 新增（自 3.12 起）

- **微信聊天记录自动解包**：分享微信「聊天记录」zip 时，识别 `聊天记录.txt` + 附件目录，把 docx/jpg 等实际文件传进 `shared/inbox/`，不再只得到一个 zip。
- **分享到 DSH**：任意 App 分享文本/文件 → 「发送到 DSH」→ PC `shared/inbox/`。
- **通知快捷动作**：通知展开后「打开」/「稍后 10 分钟」；稍后通过 AlarmManager 重新提醒。
- **多电脑切换**：保存多台 PC 的 origin/token，悬浮菜单切换；旧单台配对自动迁移。
- **文本剪贴板同步**：PC 插件 `/clipboard`；手机页「从电脑取 / 发到电脑 / 复制到手机」；进入页面不自动覆盖手机剪贴板，自动发送仅页面前台。
- **Tailscale 安装指导**：新增 `docs/TAILSCALE.md`，包含无 Google Play/GMS 时的 `https://pkgs.tailscale.com/stable/` 通用 APK 路径。

## 继承能力

- 远程模式：手机作为 PC 上 DSH 的第二块屏，Tailscale + 设备 token；
- PC 显示 `deh://pair` 二维码，App 内扫码/系统相机扫码配对；
- App 内置 ntfy 订阅，不装 ntfy App；前台服务、断线重连、通知去重；
- 单个可收纳悬浮菜单：文件传输、剪贴板、切换电脑、扫码；
- PC↔手机共享目录文件传输，App 内任意格式混合多选；
- 下载按扩展名返回 MIME，APK 不再变 `.bin`。

## 兼容

- `versionCode 34`、`versionName 3.19-clipboard3`；minSdk 26 / targetSdk 33；
- 同签名覆盖安装，配对/通知数据保留；
- 已实测 Android 16 / HyperOS。

## 产物

- `DeepSeek-Harness_3.19-clipboard3.apk`
  - SHA-256：`265c0b6530e82853daa7f17ba9385a093ed4d5ff821eaeb98bd1ab34d3360906`
  - 89,278,598 字节
- `local-dsh-mobile-bridge-0.1.1.tgz`（PC 插件，含 `qrcode` 依赖和 `/clipboard`）

## 已知限制

- 剪贴板只支持文本，手机自动同步仅在剪贴板页前台；Android 不允许后台读剪贴板；
- 输入法自己的剪贴板历史是私有数据，第三方 App 只能写系统剪贴板；
- 多电脑按 origin 区分，同一台 PC 上多账号会覆盖同一条配置；
- 微信仅识别「聊天记录」zip；普通 zip 原样上传；
- ntfy topic 是 bearer secret，手机丢失时换 topic；
- 开机自启未实现；HarmonyOS NEXT / iOS 不在本仓库范围。
