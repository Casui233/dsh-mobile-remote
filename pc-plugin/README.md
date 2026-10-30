# dsh-mobile-bridge

把 PC 上的 DeepSeek Harness **借给手机**：手机连的是同一个 DSH 进程、同一批会话，不是第二份副本。

```
手机 App（WebView，远程模式）
  │  http://<MagicDNS 名>:8765   ← Tailscale/WireGuard 加密
  ▼
dsh-mobile-bridge（跑在 Desktop 的 DSH 进程里）
  ├─ 设备认证：6 位配对码 → 设备 token（只存哈希）
  ├─ 反向代理：HTTP + WebSocket → 127.0.0.1:<Desktop 当前端口>
  ├─ 管理接口：/api/mobile/*（走 DSH 自己的信任栅栏）
  └─ 通知：任务完成 / 出错 / 待批准 / 待回答 → ntfy、Bark、Telegram
```

## 为什么是“第二个监听”而不是改启动参数

DSH 的 Web 服务只绑一个具体地址、拒绝 `0.0.0.0`；`--host` / `--trusted-host` 是
`dsh --profile web` 的启动参数，而 Desktop 的宿主进程由 Electron 托管，没有面向用户的
入口。因此插件在同一个进程里另开一个监听，自己完成设备认证，再把请求代理到 loopback
上真正的 DSH。手机永远拿不到 DSH 的启动 token。

## 安装

1. 电脑和手机都安装并登录同一个 Tailscale；记下电脑的 `100.x.y.z` 或 MagicDNS 名。
2. 在 DSH 的插件管理里安装本目录（`plugin_manager` `install_bundle`，target 为这个目录的绝对路径）。
3. **重启 Desktop**，让 Host 半端在启动阶段绑定端口。
4. 打开 设置 → 手机连接：
   - “监听设置”里确认绑定地址（留空会自动检测 Tailscale 地址）、端口（默认 8765）；
   - 点“生成配对码”。

> 插件安装需要审批。若会话的审批策略是“从不”，安装动作会被自动拒绝——先把策略调回询问。

## 配对手机

手机 App（DeepSeek-Harness-Mobile 的远程模式）：

1. 设置 → 连接方式 → 远程；
2. 填服务器地址（设置页里那一行 `http://...:8765` 或 MagicDNS 地址）；
3. 输入 6 位配对码 → 配对。

配对成功后 App 会拿到设备 token，并把它写进 WebView 的 cookie（`dshmb`，HttpOnly）。
以后打开 App 直接加载远程地址即可。

### 不装 App，直接用手机浏览器

桥接自带一个配对页：手机浏览器打开 `http://<电脑>:8765/`，会自动跳到 `/pair`，
输入 6 位配对码提交后拿到设备 cookie 并回到 DSH 界面。适合先验证链路，或临时用浏览器
“添加到主屏幕”当 PWA。

## 配置字段

配置落在 `$DSH_HOME/mobile-bridge/config.json`，设置页改的就是它：

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关闭后不再接受手机请求（管理接口仍可用） |
| `bindHost` | 空（自动） | 必须是具体地址；自动时从 `100.64.0.0/10` 里挑 Tailscale IPv4 |
| `port` | `8765` | 固定端口，保证手机端地址稳定 |
| `publicOrigin` | 空 | 对外地址；建议填 MagicDNS 名，IP 变了也不用重配 |
| `allowNonTailscaleBind` | `false` | 绑定非 Tailscale 地址必须显式打开 |
| `notifyChannel` | `off` | `off` / `ntfy` / `bark` / `telegram` |
| `notifyEndpoint` | 空（按渠道默认） | 自建 ntfy / Bark / Telegram 反代时填 |
| `notifyTopic` | 空 | ntfy topic |
| `notifyToken` | 空 | Bark key 或 Telegram bot token（只存明文配置，不写日志） |
| `notifyChatId` | 空 | Telegram chat id |
| `notifyDone` / `notifyError` / `notifyApproval` / `notifyQuestion` | `true` | 四类事件开关 |
| `notifyQuietHours` | 空 | 例 `23:00-08:00`；期间 ntfy 降为低优先级 |
| `notifyMinRunMs` | `5000` | 短于这个时长的任务不发“完成” |

改动 `enabled` / `bindHost` / `port` / `allowNonTailscaleBind` 会立即重启手机侧监听；其余字段即时生效。

## 管理接口

都挂在内部 loopback 的 `/api/mobile` 前缀上，先过 `ctx.connection.requestRejection`（DSH 自己的
Host/Origin + cookie 校验），再过本机同源检查：

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/mobile/status` | 监听状态、内部登录态、设备列表、当前配对码、配置 |
| GET | `/api/mobile/pair-qr` | 当前配对码的 `deh://pair?...` SVG 二维码（仅本机已登录页面） |
| POST | `/api/mobile/pair-code` | 生成 / 轮换配对码 |
| POST | `/api/mobile/revoke` | `{deviceId}` 或 `{all:true}` |
| POST | `/api/mobile/config` | 部分更新配置；`clearNotifyToken:true` 清密钥 |
| POST | `/api/mobile/test-notify` | 发一条测试推送 |

桥接自己的入口：`GET /health`、`GET /pair`（配对页）、`POST /pair`（JSON 或表单提交）、
`GET /notify`（已配对设备读取通知订阅配置）；其余全部要求设备 cookie 并代理给 DSH。
`POST /pair` 的 JSON 响应额外带 `notify { channel, endpoint, topic }`（仅 ntfy；Bark/Telegram
只回 channel，不泄露 token），供 App 自己订阅通知。浏览器带 `Accept: text/html` 访问根路径
且未配对时，会 302 到 `/pair`。

## 安全模型

- 桥接等价于 **owner 权限**（能执行工具、读写文件）。所以：只绑 Tailscale 地址、默认拒绝
  通配与非 Tailscale 地址、配对码 5 分钟 / 一次成功 / 5 次失败作废、每 IP 10 分钟限 8 次尝试。
- 设备 token 32 字节随机、只存 SHA-256；cookie `HttpOnly; SameSite=Lax`。
  明文 HTTP 下不能加 `Secure`，链路加密由 Tailscale 提供——这也是本方案只推荐 Tailscale 的原因。
- 手机不接触 DSH 启动 token；内部 cookie 由插件在 loopback 上兑换并只留在内存。
- 日志不打印 token、cookie、配对码；推送 URL 里的密钥不会进日志。
- ntfy topic 是 bearer secret：`/notify` 只回给已配对设备，但撤销设备不会让已知 topic 停止
  投递；手机丢失时换 topic。Bark/Telegram 的 key 永远不出现在 `/pair` 或 `/notify` 响应里。
- 不提供“只读访客”角色：当前 DSH 的 `PrincipalMap` 只有 `owner`，插件层无法安全裁剪。

## 故障排查

| 现象 | 处理 |
|---|---|
| 设置页显示“没找到 Tailscale 地址” | 确认 Tailscale 已登录；或在绑定地址里手填 `100.x.y.z` |
| 手机 `/health` 不通 | 检查电脑防火墙是否放行 8765（见下）、Tailscale 是否在线 |
| 页面 401 | 设备被撤销或旧 cookie；重新配对 |
| 页面 403 | DSH 信任栅栏拒绝：确认请求确实是经桥接进来的（Host 会被改写成 loopback） |
| “内部登录态建立失败” | DSH 升级改了内部认证方式；日志在 `$DSH_HOME/mobile-bridge/bridge.log` |
| 推送收不到 | 先点“发送测试”；ntfy 确认手机订阅了同一 topic；Bark 确认 key |
| 端口占用 | 改端口并重启监听；插件不会静默换端口 |

### Windows 防火墙

桥接监听在 Tailscale/局域网地址上，需要放行入站 TCP 8765。以管理员身份跑一次：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\allow-firewall.ps1
# 顺便重新打开 Windows 防火墙（可选）：
powershell -ExecutionPolicy Bypass -File .\scripts\allow-firewall.ps1 -EnableFirewall
```

也可以双击 `scripts\allow-firewall.cmd`（会弹 UAC）。规则只对**专用网络**（Tailscale 接口）放行。
没放行时的典型现象：本机 `http://100.x:8765/health` 正常，手机却超时/不可达。


## 已验证 / 未验证

**已在本机真实 DSH（Desktop 0.2.1-rc.3）上验证**：

- 单元 + 代理 + 端到端测试：`npm test`（21 项，含假 Host 跑通“配对 → 代理 → 通知 → 撤销”）；
- 内部登录态：插件启动日志出现 `内部登录态已建立（http://127.0.0.1:59551）`；
- 真实监听：绑定本机 WLAN 地址后 `GET /health` 返回 200，未配对 `GET /` 返回 401；
- 真实代理：带设备 cookie 的 `GET /` 返回 200 / 39 KB 的 DSH 页面；`/api/remote.mux`
  WebSocket 握手返回 `101 Switching Protocols`；
- 真实配对：管理接口生成配对码 → `POST /pair` 签发 token → 新 token 可加载页面；
  `revoke all` 后同一 token 立即 401；
- 状态接口不回明文 token。

**未验证**：

- 设置页在浏览器里的实际渲染（`Slots` 已确认注册为 `settings.section` occupant `mobile-bridge`，
  但本会话没有浏览器控制，无法截图确认布局）；
- 真实手机 App（当前机器没有 Android SDK，未构建 APK）；
- Tailscale 路径（本机未安装 Tailscale，验证用的是同一 Wi-Fi 局域网地址）；
- ntfy/Bark/Telegram 真实推送（集成测试用本地 stub 代替）。


## License

MIT（个人使用；`DSH`、`DeepSeek` 等相关名称与商标归各自作者）。
