# Tailscale 安装与登录

PC 和手机必须登录**同一个 tailnet（同一个账号）**，这是远程模式和配对能通的前提。
Tailscale 是独立 App，本项目**不打包、不静默安装**它；账号登录和 Android VPN 授权必须由使用者本人完成。

## 0. 官方包索引（没有 Google Play / 官网下载无反应时用这个）

`https://tailscale.com/download` 依赖 Google Play 或官网重定向；部分国产手机没有 GMS/Google Play，
或者 `Download` 按钮点了没反应。这时直接用 Tailscale 官方的静态包索引：

```text
https://pkgs.tailscale.com/stable/
```

该页面同时提供 Android、Windows、macOS、Linux 安装包；版本会更新，**以页面最新版本为准**。
下面用 `1.104.4`（2026-10 的 stable）举例，实际下载时把版本号换成页面上的最新值。

| 平台 | 文件 | 说明 |
|---|---|---|
| Android | `tailscale-android-universal-1.104.4.apk` | 通用 APK，Android 8+，不依赖 Google 服务；需手动更新 |
| Windows | `tailscale-setup-1.104.4.exe` 或 `tailscale-setup-1.104.4-amd64.msi` | 10/Server 2016+ |
| macOS | `Tailscale-1.104.4-macos.pkg` | 也提供 `.zip` 版 |
| Linux | `tailscale_1.104.4_amd64.tgz` / `_arm64.tgz` | 静态二进制；也可用发行版仓库 |

直链拼法：

```text
https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.4.apk
https://pkgs.tailscale.com/stable/tailscale-setup-1.104.4.exe
https://pkgs.tailscale.com/stable/Tailscale-1.104.4-macos.pkg
https://pkgs.tailscale.com/stable/tailscale_1.104.4_amd64.tgz
```

校验（可选）：给文件 URL 加 `.sha256` 下载校验值。

```bash
# Linux/macOS
curl -O https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.4.apk
curl -O https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.4.apk.sha256
sha256sum -c tailscale-android-universal-1.104.4.apk.sha256
```

Windows PowerShell：

```powershell
Get-FileHash .\tailscale-android-universal-1.104.4.apk -Algorithm SHA256
Get-Content .\tailscale-android-universal-1.104.4.apk.sha256
```

## 1. PC 端

### Windows

```powershell
winget install --id tailscale.tailscale -e --accept-package-agreements --accept-source-agreements
tailscale up
```

winget 不可用时，从上面的 `pkgs.tailscale.com/stable/` 下载 `tailscale-setup-*.exe` 双击安装。

- `tailscale up` 会打印登录 URL，浏览器授权后完成登录；
- 如果 `tailscale` 不在 PATH：`C:\Program Files\Tailscale\tailscale.exe`。

验证：

```powershell
tailscale status
tailscale ip -4
```

### macOS

任选一种安装：

- App Store 搜索 **Tailscale**；
- 或 Homebrew：

```bash
brew install --cask tailscale
open -a Tailscale
```

App Store/Homebrew 不可用时，从 `pkgs.tailscale.com/stable/` 下载 `Tailscale-*-macos.pkg`。

在菜单栏 Tailscale 图标里登录。CLI 可能在：

```bash
/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4
```

### Linux

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

脚本不可用时，从 `pkgs.tailscale.com/stable/` 下载对应架构的静态包：

```bash
tar -xzf tailscale_1.104.4_amd64.tgz
sudo mv tailscale_1.104.4_amd64/tailscale* /usr/local/bin/
sudo systemctl enable --now tailscaled
sudo tailscale up
```

验证：

```bash
tailscale status
tailscale ip -4
```

## 2. 手机端

### Android（有 Google Play）

Google Play 搜 **Tailscale**（包名 `com.tailscale.ipn`），或
<https://play.google.com/store/apps/details?id=com.tailscale.ipn>。这是官方推荐方式，可自动更新。

### Android（没有 Google Play / 官网 Download 无反应）

从官方静态包索引直接下载通用 APK：

```text
https://pkgs.tailscale.com/stable/
```

找到 `tailscale-android-universal-<版本>.apk`，例如：

```text
https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.4.apk
```

步骤：

1. 用手机浏览器打开上面的链接；如果手机浏览器下载不了，就在 PC 下载后通过本项目的共享目录/USB 传到手机；
2. 允许“未知来源”安装（MIUI/HyperOS 可能还要在文件管理器里允许安装）；
3. 安装后打开，登录**同一账号**；
4. 首次连接允许 **VPN 授权**；通知栏出现常驻 VPN 图标才算在线；
5. 这个 APK **不会自动更新**，以后回同一个索引页手动下新版覆盖安装。

> 该通用 APK 要求 **Android 8.0+**，包含多数 CPU 架构；不需要 Google 服务。

### iOS

App Store 搜 **Tailscale**。iOS 没有官方的侧载 APK 路径。

## 3. 验证两端互通

PC：

```bash
tailscale status
```

能看到手机名（如 `redmi-note-13-pro`）且状态 `active`。

手机：

- 浏览器打开 `http://<PC Tailscale IP>:8765/health`；
- 或在本项目 App 里配对后能打开远程 DSH。

PC 本机健康检查：

```bash
curl http://127.0.0.1:8765/health
```

## 4. 常见问题

| 现象 | 处理 |
|---|---|
| 应用商店没有 Tailscale、官网 Download 没反应 | 用 `https://pkgs.tailscale.com/stable/` 的 Android APK / 桌面安装包 |
| 手机打不开 `100.x:8765` | 两端是否登录同一账号；手机 VPN 是否在线；PC 防火墙是否放行 8765（Private） |
| `tailscale ip -4` 没有输出 | 未登录/未启动；运行 `tailscale up` 或打开桌面客户端登录 |
| 状态是 `relay` 不是 `direct` | 正常可用，延迟略高；NAT 打洞失败时走 DERP 中继 |
| 手机过一段时间掉线 | Android 省电策略限制；把 Tailscale 设为“无限制” |
| 自定义 ACL 后连不上 | ACL 需要允许该设备访问 PC 的 8765；不会配就先恢复默认 `autogroup:member` |
| 公司/校园网限制 | 换网络或使用手机热点；Tailscale 可能被防火墙阻断 |
| APK 安装被拦 | 允许未知来源；部分 ROM 需关闭“纯净模式”/安全安装限制 |

## 5. 安全

- tailnet 等价于你私人的内网；不要把 Tailscale 账号借给别人；
- 不要用 Tailscale Funnel 把 8765 暴露到公网；
- 定期在 Tailscale 后台移除不用的设备；
- 本项目自己的设备 token 只存在 App 私有目录，和 Tailscale 登录是两层独立授权。
