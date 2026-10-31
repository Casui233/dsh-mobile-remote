# 安装 DSH Mobile Remote

> 这份文档既给人看，也给新 DSH 会话里的 agent 看。agent 请逐节执行并在每节末尾做验证，
> 不要跳步；需要用户操作（登录 Tailscale、在手机上点安装）时明确停下来等用户。

## 0. 前置检查

| 项目 | 要求 |
|---|---|
| PC | Windows 10/11、macOS 或 Linux，已安装并登录 **DSH Desktop**（或能运行 `dsh web`） |
| PC Node | 22+ |
| PC Tailscale | 已安装并登录，`tailscale ip -4` 能出一个 `100.x` 地址 |
| 手机 | Android 8.0+（minSdk 26）；HarmonyOS 4.x 及更早的 Android 兼容版可用；HarmonyOS NEXT / iOS 不适用 |
| 手机 Tailscale | 与 PC 登录**同一个 tailnet** |
| 网络 | PC 防火墙放行 TCP 8765（仅 Private/Tailscale） |

先确认：

```bash
node --version          # >= 22
tailscale ip -4         # 100.x.y.z
```

如果 PC 上没有 Tailscale：优先 <https://tailscale.com/download>；如果下载页没反应、或手机没有
Google Play/GMS，直接用官方静态包索引 <https://pkgs.tailscale.com/stable/>（Android 通用 APK、
Windows/macOS/Linux 安装包都在里面）。手机安装 Tailscale 后登录同一账号。

> Tailscale 的 Windows/macOS/Linux/Android 安装命令、登录、验证和常见问题见
> [TAILSCALE.md](TAILSCALE.md)。本项目和 Tailscale 是两层独立授权：Tailscale 负责
> 网络可达，项目自己的设备 token 负责 DSH 访问；手机安装和登录必须由使用者本人完成。

## 1. 取得本仓库

```bash
git clone https://github.com/Casui233/dsh-mobile-remote.git
cd dsh-mobile-remote
```

没有 git 时，在 GitHub 页面点 **Code → Download ZIP** 解压即可。

下文用 `<REPO_DIR>` 表示仓库根目录。

## 2. 安装 PC 插件

插件目录：`<REPO_DIR>/pc-plugin`。包名 `@local/dsh-mobile-bridge`，是一个 Cordis bundle。

### 2A. 推荐：让当前 DSH 会话的 agent 安装

在 DSH 里新建/打开一个会话，把下面这句发给 agent（把路径换成实际绝对路径）：

```text
请把 <REPO_DIR>/pc-plugin 作为本地 Cordis bundle 安装进当前 DSH profile：
用 plugin_manager install_bundle，target 用该目录的绝对路径；安装后确认
plugin_manager list_plugins 里出现 @local/dsh-mobile-bridge，并且它是 enabled。
```

如果当前 DSH 版本的 `plugin_manager` 不接受本地目录，走 2B。

### 2B. 手动加入 profile

> 修改前先退出 DSH Desktop，并备份 `package.json`。

1. 找到 profile 目录：
   - Windows：`%USERPROFILE%\.dsh\profiles\desktop`
   - macOS/Linux：`~/.dsh/profiles/desktop`
   - 如果用的是别的 profile，换成对应目录。
2. 把 `pc-plugin` 复制到 `%USERPROFILE%\.dsh\plugins\dsh-mobile-bridge`（或任意固定路径）。
3. 编辑 profile 的 `package.json`：
   - `dependencies` 增加：

     ```json
     "@local/dsh-mobile-bridge": "file:C:/Users/<YOU>/.dsh/plugins/dsh-mobile-bridge"
     ```

     macOS/Linux 用 `file:/Users/<YOU>/.dsh/plugins/dsh-mobile-bridge`。
   - `dsh.profile.bundles` 数组增加 `"@local/dsh-mobile-bridge"`。
4. 在 profile 目录安装依赖：

   ```bash
   pnpm install
   ```

   DSH Desktop 自带 pnpm；没有的话先 `npm i -g pnpm`。
5. 重新启动 DSH Desktop。

### 2C. 验证插件

```bash
# Windows
powershell -ExecutionPolicy Bypass -File .\pc-plugin\scripts\allow-firewall.ps1 -EnableFirewall
```

- 打开 DSH → 设置 → **手机连接**，能看到面板即安装成功；
- 插件默认监听 `8765`，绑定地址选 Tailscale（或留空自动检测）；
- 本机验证：`curl http://127.0.0.1:8765/health` 返回 `{"ok":true,...}`。

## 3. 配置 PC 桥接

在 DSH → 设置 → **手机连接**：

1. **监听设置**：端口 `8765`；绑定地址选 Tailscale 地址（或留空让插件自动检测）。
2. **通知渠道**：选 `ntfy`；endpoint 保持 `https://ntfy.sh`（或自建无鉴权 endpoint）；topic 用插件生成的随机值。
3. 点 **发送测试**，确认 PC 侧推送请求返回成功（手机端下一步再验）。
4. 点 **生成配对码**，得到 6 位数字和 `http://100.x.y.z:8765` 形式的地址；页面同时显示 `deh://pair?...` 二维码，配对码 5 分钟内有效。二维码含一次性码，别截图外发。

> 不要把 8765 暴露到公网；只绑 Tailscale/局域网。桥接等价于 PC 上 DSH 的 owner 权限。

## 4. 安装手机 App

1. 手机安装 Tailscale，登录同一账号，确认 VPN 图标在。
2. 下载 APK：
   - GitHub Releases 里的 `DeepSeek-Harness_3.12-picker.apk`；
   - 或本仓库 `release/DeepSeek-Harness_3.12-picker.apk`。
3. 允许“未知来源”安装。若系统提示与旧版签名冲突，先卸载旧版（会丢 App 私有数据，共享存储 `/storage/emulated/0/DEH` 保留）。
4. 打开 App → 设置 → 连接方式 → **远程（连电脑）**。
5. 地址填 PC 设置页显示的 `http://100.x.y.z:8765`；配对码填 6 位数字；点配对。
   也可以不手输：用 **App 内扫码**——原生配对弹窗输入框下的图标、网页配对页的「扫码配对（App）」、或进入 DSH 后左侧栏头像上方的圆形按钮；首次会请求相机权限。用手机系统相机扫 PC 设置页的二维码也可以，系统会通过 `deh://pair` 深链打开 App。
6. 配对成功后 App 直接加载远程 DSH。之后打开 App 都会进远程模式，直到解除配对。

### 手机端验证

- App 能打开远程会话、发消息、看到 PC 上的任务；
- 设置 → 连接电脑 显示已配对设备名和地址；
- 如果失败：先确认手机 Tailscale 在线、PC `/health` 本机可访问、防火墙放行 8765。

## 5. 通知（不需要 ntfy App）

App 3.2 起自己订阅 PC 插件配置的 ntfy topic。

1. 手机 App → 设置 → **通知**：
   - 首次配对后会自动拉取配置，显示 `ntfy · …XXXX · 接收中`；
   - 如果显示“未配置/未连接”，点这一行强制刷新；仍不行看第 7 节。
2. 通知权限：Android 13+ 首次启动会请求；如果拒绝，设置 → 通知 会跳到系统通知设置。
3. PC → 手机连接 → **发送测试**；手机应弹出「DSH 测试通知」。
4. 点通知，应回到 App 的远程 DSH 界面。

不需要安装 `io.heckel.ntfy`。如果手机上还装着 ntfy App，建议卸载或在 ntfy 里退订同一个 topic，否则会收到两份。

> 通知走公网 ntfy.sh，Tailscale 关掉也能收；远程界面仍然需要 Tailscale。
> ntfy topic 是 bearer secret：手机丢失时在 PC 换 topic，所有设备重新配对/刷新。

## 5.1 文件传输与 App 内多选

- PC 设置 → 手机连接 → **文件共享目录**填一个绝对路径（建议 DSH 工作区下的 `shared/`）；
- 手机 DSH 页面边缘的悬浮菜单 → **文件传输**：浏览/上传/下载；手机下载到 `Download/DSH/`；
- 原 UI「+ → 文件」不再走系统选择器，而是 App 内文件浏览器，可任意混合格式多选；首次需要「所有文件访问权限」；
- 悬浮菜单可拖动并吸附到左右边缘，文件传输和扫码都在里面。

## 6. 验收清单

- [ ] PC `curl http://127.0.0.1:8765/health` 返回 200
- [ ] 手机 Tailscale 在线，`http://100.x.y.z:8765/health` 在手机浏览器能打开
- [ ] App 配对成功，远程会话可用
- [ ] App 设置 → 通知 显示「接收中」
- [ ] PC 发送测试，手机收到通知；点通知回 App
- [ ] 手机锁屏/切后台 10 分钟后再发一条，仍能收到
- [ ] 不需要 ntfy App

## 7. 故障排查

| 现象 | 处理 |
|---|---|
| 手机打不开 `100.x:8765` | PC 防火墙放行 8765（Private）；Tailscale 是否在线；插件监听地址是否选对 |
| 配对码无效 | 5 分钟过期/已用过；重新生成；每 IP 10 分钟限 8 次 |
| 远程页 401 | 设备被撤销或 token 失效；App 设置 → 连接电脑重新配对 |
| 通知不弹 | App 设置 → 通知 是否「接收中」；系统通知权限；HyperOS 省电策略设为“无限制”；前台通知别划掉 |
| 通知重复 | 还装着 ntfy App 且订阅了同一 topic；卸载或退订 |
| 通知延迟/收不到 | ntfy.sh 网络波动；换自建 endpoint（无鉴权）；确认 PC 发送测试成功 |
| App 升级签名冲突 | 必须用同一 keystore 签名；本项目 Release APK 固定签名，不要混装别人构建的包 |

## 8. 更新与卸载

- 更新 App：下载新 APK 直接覆盖安装（同签名），配对数据保留；
- 更新插件：替换 `pc-plugin` 目录后重启 DSH；
- 卸载：手机卸载 App；PC 从 profile 的 `bundles`/`dependencies` 移除 `@local/dsh-mobile-bridge`，重启 DSH；删除 `~/.dsh/mobile-bridge` 状态目录。

## 9. 平台说明

- **Android 8–16**：App `minSdk 26`、`targetSdk 33`；通知用前台服务 + `specialUse` 类型。
- **HarmonyOS**：Android 兼容版（4.x 及更早）可安装 APK；HarmonyOS NEXT 不再运行 APK，需要单独客户端。
- **iOS**：没有 APK；需要单独 App 或 PWA，不在本仓库范围。
- **PC**：插件是 Node/Cordis，Windows/macOS/Linux 均可；防火墙脚本目前只提供 Windows 版。
