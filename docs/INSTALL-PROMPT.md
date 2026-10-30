# 新 DSH 会话一键安装提示词

把下面整段复制到一个新的 DSH 会话（把 `Casui233/dsh-mobile-remote` 换成实际 GitHub 仓库）：

```text
请按 https://raw.githubusercontent.com/Casui233/dsh-mobile-remote/main/docs/INSTALL.md
帮我安装 DSH Mobile Remote。先逐项检查环境，再一步一步执行；每步做验证，不要跳步。

我的环境：
- PC 系统：<Windows 11 / macOS / Linux>
- 手机：<Android 版本 / 机型>
- 是否已装 Tailscale：<是/否>

要求：
1. 先检查 DSH Desktop、Node 22+、Tailscale、手机系统版本；缺什么先告诉我。
   - PC 没有 Tailscale：按 docs/TAILSCALE.md 用 winget/brew/install.sh 安装；`tailscale up`
     的登录 URL 交给我，由我完成账号授权；
   - 手机 Tailscale 的安装、登录、VPN 授权由我手动完成，到这一步停下来等我。
2. 安装 PC 插件时优先用 plugin_manager install_bundle，target 用仓库 pc-plugin 的绝对路径；
   如果当前 DSH 不支持本地目录，再按 INSTALL.md 的 2B 手动步骤，改 profile 前先备份并退出 DSH。
3. PC 防火墙只放行 Private/Tailscale 的 TCP 8765，不要暴露公网。
4. 手机端安装 Tailscale 和 Release 里的 APK 需要我手动点，到这些步骤请停下来等我确认。
5. 通知渠道用 ntfy，手机 App 不需要装 ntfy App；最后发一条测试通知，确认手机能收到、
   点通知能回到 App。
6. 配对时优先用 App 内扫码：原生弹窗输入框下、网页配对页的「扫码配对（App）」、或 DSH
   侧栏头像上方；也可以让系统相机扫 PC 设置页的二维码（`deh://pair` 深链）。失败再手动
   输入地址 + 6 位配对码。
7. 结束后按 INSTALL.md 第 6 节逐条打勾，失败的项给出具体命令和日志。
```

## 使用说明

- 这份提示词不包含任何密钥；配对码在 PC 设置页临时生成。
- 如果仓库还没发布，把 `Casui233/dsh-mobile-remote` 换成本地路径，或先把 `docs/INSTALL.md` 的内容直接贴给 agent。
- 手机上安装 APK、登录 Tailscale、允许未知来源，必须由使用者本人操作。
- 安装完成后建议把手机 App 的省电策略设为“无限制”，否则部分国产 ROM 会杀通知服务。
