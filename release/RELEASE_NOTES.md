# 3.12-picker

## 新增

- **单个可收纳悬浮菜单**：屏幕边缘半透明悬浮球，点击展开「文件传输 / 扫码」；可拖动、自动吸附左右边缘、位置记忆。
- **PC ↔ 手机文件传输**：插件新增共享目录文件页（列表/上传/下载/新建文件夹），只暴露配置的共享目录；APK/PDF/Office/图片按扩展名返回 MIME，下载不再变成 `.bin`。
- **App 内多选文件浏览器**：系统 DocumentsUI 在混合格式多选时只返回一个 URI，改用 App 内文件浏览器，支持任意格式混合多选；原 UI「+ → 文件」恢复可用。

## 继承能力

- 远程模式：手机作为 PC 上 DSH 的第二块屏，Tailscale + 设备 token；
- PC 显示 `deh://pair` 二维码，App 内扫码 / 系统相机扫码配对；
- App 内置 ntfy 订阅，不装 ntfy App；通知点击回 DSH；
- 前台服务、断线重连、通知去重。

## 兼容

- `versionCode 27`、`versionName 3.12-picker`；minSdk 26 / targetSdk 33；
- 同签名覆盖安装，配对/通知数据保留；
- 已实测 Android 16 / HyperOS。

## 产物

- `DeepSeek-Harness_3.12-picker.apk`
  - SHA-256：`a5521db6c8a18812e5fd29bf2cea70ecb2826ceb6ef9020b90f2d761a4033300`
  - 89,249,926 字节
- `local-dsh-mobile-bridge-0.1.0.tgz`（PC 插件包，含 qrcode 依赖）

## 已知限制

- 文件传输是手动共享目录，不做自动双向同步；
- 自定义文件浏览器需要「所有文件访问权限」；
- ntfy topic 是 bearer secret，手机丢失时换 topic；
- 开机自启未实现；HarmonyOS NEXT / iOS 不在本仓库范围。
