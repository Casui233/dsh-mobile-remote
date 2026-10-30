# Tailscale 安装与登录

PC 和手机必须登录**同一个 tailnet（同一个账号）**，这是远程模式和配对能通的前提。
Tailscale 是独立 App，本项目**不打包、不静默安装**它；账号登录和 Android VPN 授权必须由使用者本人完成。

## 1. PC 端

### Windows

```powershell
winget install --id tailscale.tailscale -e --accept-package-agreements --accept-source-agreements
tailscale up
```

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

在菜单栏 Tailscale 图标里登录。CLI 可能在：

```bash
/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4
```

### Linux

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

按提示打开登录 URL。

验证：

```bash
tailscale status
tailscale ip -4
```

## 2. 手机端

- Android：Google Play 搜 **Tailscale**（包名 `com.tailscale.ipn`），或从 <https://tailscale.com/download> 获取；
- iOS：App Store 搜 **Tailscale**；
- 登录**同一账号**；
- Android 首次连接会请求 **VPN 授权**，必须允许；通知栏出现常驻 VPN 图标才说明在线；
- 如果系统有省电策略，把 Tailscale 设为“无限制”，否则后台会掉线。

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
| 手机打不开 `100.x:8765` | 两端是否登录同一账号；手机 VPN 是否在线；PC 防火墙是否放行 8765（Private） |
| `tailscale ip -4` 没有输出 | 未登录/未启动；运行 `tailscale up` 或打开桌面客户端登录 |
| 状态是 `relay` 不是 `direct` | 正常可用，延迟略高；NAT 打洞失败时走 DERP 中继 |
| 手机过一段时间掉线 | Android 省电策略限制；把 Tailscale 设为“无限制” |
| 自定义 ACL 后连不上 | ACL 需要允许该设备访问 PC 的 8765；不会配就先恢复默认 `autogroup:member` |
| 公司/校园网限制 | 换网络或使用手机热点；Tailscale 可能被防火墙阻断 |

## 5. 安全

- tailnet 等价于你私人的内网；不要把 Tailscale 账号借给别人；
- 不要用 Tailscale Funnel 把 8765 暴露到公网；
- 定期在 Tailscale 后台移除不用的设备；
- 本项目自己的设备 token 只存在 App 私有目录，和 Tailscale 登录是两层独立授权。
