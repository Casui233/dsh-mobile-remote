# 远程模式：连电脑上的 DSH

远程模式让手机**不启动本地 proot 运行时**，而是直接连电脑上的 DSH。会话、任务、文件、审批
都只有电脑那一份——手机是同一台 DSH 的第二块屏。

配套的电脑端插件是 `dsh-mobile-bridge`（见工作区 `mobile-bridge/` 的 README）。

## 数据流

```
手机 App（WebView）
  │ http://<电脑地址>:8765
  ▼
dsh-mobile-bridge（跑在电脑的 DSH 进程里）
  │ 设备 cookie → 设备表
  │ 反向代理（HTTP + WebSocket）
  ▼
电脑上的 DSH 127.0.0.1:<桌面端口>
```

手机 App 只保存两样东西：电脑地址、桥接插件签发的设备 token。DSH 的启动 token 不会离开电脑。

## 电脑端准备

1. 安装 `dsh-mobile-bridge` 插件并重启 Desktop。
2. 打开 设置 → 手机连接：
   - 确认“监听设置”里绑定地址/端口（插件会自动检测 Tailscale，也支持手填局域网 IP）；
   - 点“生成配对码”，得到 6 位数字和形如 `http://192.168.10.101:8765` 的地址。
3. 手机与电脑在同一 Wi-Fi，或都登录同一个 Tailscale。

## 手机端配对

设置 → 连接电脑：

1. 服务器地址：填电脑设置页显示的那一行（可省略 `http://`）；
2. 配对码：6 位数字；
3. 点“配对”。

成功后 App 会把设备 token 写进 WebView cookie（`dshmb`），并自动连上。之后打开 App
会直接加载远程界面，直到你在设置里“解除配对”或电脑端撤销设备。

> 不装 App 也可以：手机浏览器打开 `http://<电脑>:8765/` 会自动跳到配对页，输入同一个
> 6 位配对码即可。适合先验证链路，或把它“添加到主屏幕”临时当 PWA。

## 通知（不需要 ntfy App）

3.2 起 App 自己订阅 PC 插件配置的 ntfy topic：

- 配对时插件在 `/pair` 响应里返回 `notify { channel, endpoint, topic }`；App 只把 ntfy
  的 endpoint/topic 存在私有 SharedPreferences，不接触 Bark/Telegram 的 token。
- 远程模式下 `NotifyService` 以前台服务运行：先 `GET /notify`（设备 Bearer token）刷新
  配置，再直连 `<endpoint>/<topic>/sse`，收到消息后弹本地通知。
- 通知走公网 ntfy.sh，**Tailscale 断开也能收**；点通知触发 `deh://open` 回到远程会话。
- 设置 → 通知 显示当前状态，点一下可强制刷新；通知权限被关时会跳到系统通知设置。
- 要停掉后台通知：设置 → 连接方式切回“本机”，或解除配对。

安全注意：ntfy topic 本身是 bearer secret，撤销设备不会让已泄漏的 topic 停止投递；
手机丢失时应在电脑端换一个 topic（所有设备需要重新配对/刷新）。

> 电脑端仍需保留 ntfy 渠道并填好 topic；App 只是替代 ntfy App 做订阅端。

## 深链（可选）

App 注册了 `deh://` scheme，可被二维码或 `adb` 调起：

```text
deh://pair?origin=http%3A%2F%2F192.168.10.101%3A8765&code=123456
deh://open
```

- `deh://pair`：切到远程模式并按参数配对（`origin` 必须 URL 编码）；
- `deh://open`：切到远程模式并连接（通知点击可用它直接进 App）。

PC 插件设置页会把 `deh://pair?...` 显示成二维码；手机**系统相机**扫一下会直接调起 App
并配对，不需要 App 内再申请相机权限。

调试示例：

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "deh://pair?origin=http%3A%2F%2F192.168.10.101%3A8765&code=123456"
```

## 本地 / 远程切换

设置 → “连接方式：本机 / 远程（点击切换）”：

- 切到**远程**：不启动本地运行时；已有远程凭证就直接连，否则弹配对框。
- 切到**本机**：重启界面并走原来的 proot 流程（本地运行时数据不受影响）。

“解除配对”只清远程凭证（地址/token/设备名），不动本地运行时的任何文件。

## 实现要点

| 位置 | 内容 |
|---|---|
| `Prefs.connMode` | `local` / `remote` |
| `Prefs.remoteOrigin` | 电脑地址（`http://host:port`） |
| `Prefs.remoteToken` | 设备 token（App 私有 SharedPreferences） |
| `MainActivity.enterRemote()` | 先 `GET /health` 探活，再设 cookie、加载 WebView |
| `showRemote()` | 跳过 local 的 `Runtime` 与 `DshService`，直接加载远程 URL |
| `isRemoteHost()` | 远程地址留在 WebView 内，其它链接交给系统浏览器 |
| `handleIntent()` | `deh://pair` / `deh://open` 深链 |

## 已知限制

- 远程模式下 Shizuku/Dhizuku/本地 ADB 这些“手机侧能力”不会自动挂到电脑的 DSH 上；
  它们是本机模式的特性。
- 电脑必须保持 DSH 运行；电脑睡眠或退出，手机就连不上。
- 明文 HTTP 只建议在 Tailscale 或可信局域网内使用；桥接插件默认拒绝非 Tailscale 地址，
  需要“允许绑定非 Tailscale 地址”才可绑局域网 IP。
- 当前环境没有 Android SDK，因此本次只做了源码级改动与静态检查，**未构建 APK、未在真机验证**。
