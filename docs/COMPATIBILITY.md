# 兼容性

## Android

| 项目 | 值 |
|---|---|
| minSdk | 26（Android 8.0） |
| targetSdk | 33（Android 13） |
| compileSdk | 34 |
| ABI | arm64-v8a |
| 已实测 | Android 16 / HyperOS / Redmi Note 13 Pro |
| 通知 | 前台服务 + `specialUse` 类型；Android 13+ 请求 `POST_NOTIFICATIONS` |
| 不支持 | 32 位 only 设备、无 WebView 的设备 |

Android 8–16 使用同一套 API guard：

- 通知渠道：API 26+；
- `startForegroundService`：API 26+；
- `POST_NOTIFICATIONS`：API 33+；
- 三参 `startForeground(type)`：API 34+ 才调用，低版本走两参。

未在 Android 8–15 真机上逐版本测试；如遇到 ROM 差异，请附 `adb logcat`。

## HarmonyOS

- **HarmonyOS 4.x 及更早**（Android 兼容层）：可以安装 Android APK，行为等同 Android；
- **HarmonyOS NEXT / 5.x**：不再兼容 Android APK，本仓库的 App 无法安装，需要单独的 HarmonyOS 原生客户端；
- 远程模式本身只依赖 HTTP/WebSocket 和 Tailscale，协议层可复用，但 UI/通知要重写。

## iOS

没有 APK。需要单独做 iOS App 或 PWA：

- 远程 Web UI 可以直接用 Safari/PWA 打开 `http://<PC>:8765` 并配对；
- 后台通知需要 APNs 或保持前台，不能复用 Android 的 ntfy 前台服务方案。

## PC

- 插件是 Cordis/Node，Windows、macOS、Linux 均可；
- 防火墙脚本目前只有 Windows 版（`pc-plugin/scripts/allow-firewall.ps1`）；
- Tailscale 官方客户端支持三平台。

## 已知限制

- 国产 ROM 的省电策略可能杀前台服务，需要把 App 设为“无限制”；
- ntfy.sh 在部分网络下延迟/不可达，可换自建无鉴权 endpoint；
- 撤销设备不会让已知 ntfy topic 停止投递，手机丢失时换 topic；
- 开机自启未实现，重启手机后需要打开一次 App。
