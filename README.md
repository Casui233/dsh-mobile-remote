# DSH Mobile Remote

把 Android 手机变成电脑上 **DeepSeek Harness** 的第二块屏：手机不跑本地运行时，直接连电脑上的同一个 DSH 进程；任务完成、出错、待批准/待回答时，手机 App 自己订阅 ntfy 并弹通知——**不需要安装 ntfy App**。支持二维码 / App 内扫码配对、PC↔手机共享目录文件传输、可收纳悬浮菜单。

链路：

```text
手机 App（WebView + 本地通知服务）
  │  http://<PC Tailscale IP>:8765
  ▼
dsh-mobile-bridge（跑在 PC 的 DSH 进程里）
  │  设备 token / 反向代理 / 任务事件
  ▼
PC 上的 DSH（127.0.0.1:<桌面端口>）
```

通知链路：

```text
PC 事件 → 插件 sendNote() → ntfy.sh/<topic>
手机 App NotifyService → <endpoint>/<topic>/sse → 本地通知 → 点击 deh://open
```

通知走公网 ntfy，Tailscale 断开也能收；远程界面和配对需要 Tailscale（或同一局域网直连）。

## 功能

- 手机作为 PC 上 DSH 的第二块屏，会话/任务/审批继续；
- PC 显示二维码，App 内扫码或系统相机配对；
- App 自己订阅 ntfy 并弹本地通知，不装 ntfy App；通知可「打开」/「稍后 10 分钟」；
- 悬浮菜单：文件传输、剪贴板、切换电脑、扫码；
- PC↔手机共享目录文件传输，手机原 UI 支持任意格式混合多选；
- 任意 App 分享文本/文件到 `shared/inbox/`，微信聊天记录 zip 自动解包；
- 多台电脑配对保存与切换；
- PC↔手机文本剪贴板同步（自动发送只在页面前台）。

## 当前版本

- App：`3.19-clipboard3`（versionCode 34，minSdk 26 / targetSdk 33）
- 插件：`@local/dsh-mobile-bridge` 0.1.1
- 已验证：Android 16 / HyperOS，通知/后台、二维码与 App 内扫码、PC↔手机文件传输、混合格式多选、微信聊天记录解包、通知快捷动作、多电脑切换、文本剪贴板同步
- 未覆盖：HarmonyOS NEXT（不再兼容 APK）、iOS——需要单独客户端

## 仓库结构

```text
pc-plugin/                 PC 端 DSH Cordis 插件（桥接、配对、反向代理、通知）
android-app/               Android App 源码（远程模式 + 内置通知）
docs/
  INSTALL.md               完整安装步骤（给人或 agent 看）
  TAILSCALE.md             Tailscale PC/手机安装、登录、验证、故障排查
  INSTALL-PROMPT.md        贴到新 DSH 会话里的“一键指导”提示词
  COMPATIBILITY.md         Android / HarmonyOS / iOS 兼容性
  REMOTE.md                App 远程模式细节
  PUBLISH.md               发布到 GitHub 的步骤
release/                   本地构建产物（APK 不提交 git，放 GitHub Releases）
```

## 快速开始

完整步骤见 [docs/INSTALL.md](docs/INSTALL.md)。

如果是在一个**新的 DSH 会话**里，直接把 [docs/INSTALL-PROMPT.md](docs/INSTALL-PROMPT.md) 的内容发给 agent，并把其中的 `Casui233/dsh-mobile-remote` 换成实际仓库地址；agent 会按步骤检查环境、安装 PC 插件、Tailscale 和手机 APK，并逐项验证。

最少需要：

1. PC 和手机登录**同一个 Tailscale 账号**；
2. PC 安装 `pc-plugin`，在 DSH 设置 → 手机连接里生成配对码；
3. 手机安装 `release/DeepSeek-Harness_3.19-clipboard3.apk`，远程模式填 PC 的 Tailscale 地址 + 配对码，或用 App 内扫码；
4. PC 通知渠道选 `ntfy` 并填 topic；App 设置 → 通知 显示「接收中」后，发测试通知；
5. 手机悬浮菜单里打开「文件传输」，在 PC 插件设置里指定共享目录即可双向传文件；原 UI 添加文件走 App 内多选浏览器，支持混合格式。

## 安全模型

- 桥接等价于 PC 上 DSH 的 **owner 权限**，只应绑定 Tailscale 地址，不要暴露到公网。
- 设备 token 只存 SHA-256；配对码 5 分钟有效、一次成功、5 次失败作废。
- ntfy topic 本身是 bearer secret：撤销设备不会让已知 topic 停止投递；手机丢失时换 topic。
- Bark/Telegram 的 token 永远不会下发给手机 App；App 只拿 ntfy 的 endpoint/topic。

## 构建

Android App 不依赖 Gradle，使用 `aapt2 + javac + d8 + zipalign + apksigner`；详见 [android-app/docs/BUILD.md](android-app/docs/BUILD.md)。本仓库不包含 84 MB 运行时资源 `android-app/app/assets/runtime/`，源码构建本地模式需要自行准备；只做远程模式时直接使用 Releases 里的 APK 即可。

## 许可证与致谢

MIT。Android App 基于 DeepSeek Harness Mobile（MIT）修改；App 内扫码使用 ZXing core（Apache 2.0），PC 端二维码使用 qrcode（MIT）；DSH、Tailscale、ntfy、Shizuku、Dhizuku 等名称与商标归各自作者所有。本项目不是 DeepSeek 官方项目。
