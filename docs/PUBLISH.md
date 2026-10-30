# 发布到 GitHub

> 当前机器没有 git/gh 登录，也没有 GitHub token；下面是发布步骤。仓库名建议
> `dsh-mobile-remote`，发布后把所有 `Casui233/dsh-mobile-remote` 替换成实际值。

## 1. 创建仓库

在 GitHub 新建一个空仓库（不要初始化 README），例如：

```text
https://github.com/Casui233/dsh-mobile-remote
```

## 2. 上传源码

### 方式 A：GitHub 网页（不需要 git）

1. 解压 `dsh-mobile-remote-src.zip`；
2. 在仓库页面 **Add file → Upload files**，把解压后的内容拖进去；
3. 提交信息：`Initial release: DSH Mobile Remote 3.12-picker`。

### 方式 B：git 命令行

```bash
cd dsh-mobile-remote
git init
git add .
git commit -m "Initial release: DSH Mobile Remote 3.12-picker"
git branch -M main
git remote add origin https://github.com/Casui233/dsh-mobile-remote.git
git push -u origin main
```

`.gitignore` 已排除 APK、keystore、运行时资源、日志和本地配置。

## 3. 创建 Release

1. 仓库 → **Releases → Draft a new release**；
2. Tag：`v3.12-picker`；
3. 标题：`DSH Mobile Remote 3.12-picker`；
4. 描述：复制 `release/RELEASE_NOTES.md`；
5. 上传附件：
   - `release/DeepSeek-Harness_3.12-picker.apk`
   - `release/local-dsh-mobile-bridge-0.1.0.tgz`
   - `release/SHA256SUMS.txt`
6. 发布。

APK 约 88 MB，GitHub Release 单文件限制 2 GB，没问题；不要提交进 git 仓库。

## 4. 替换文档里的占位符

发布后把以下文件里的 `Casui233/dsh-mobile-remote` 替换成实际仓库：

- `README.md`
- `docs/INSTALL.md`
- `docs/INSTALL-PROMPT.md`

GitHub 网页可以直接编辑；改完提交。

## 5. 验证新会话安装链接

在浏览器打开：

```text
https://raw.githubusercontent.com/Casui233/dsh-mobile-remote/main/docs/INSTALL.md
```

能返回 Markdown 即可。然后在新的 DSH 会话里粘贴 `docs/INSTALL-PROMPT.md` 的内容，
agent 会按文档逐步检查/安装。

## 6. 可选：npm 包

`pc-plugin` 已能 `npm pack` 成 `local-dsh-mobile-bridge-0.1.0.tgz`。如果要让用户
一行安装，可以把它发布到 npm（需要 npm 账号）后再把 `plugin_manager install_bundle`
的 target 改成包名；当前文档以本地目录 + Release tarball 为准。

## 7. 不要提交的东西

- `release/*.apk`、`*.tgz`
- `dsh-release.keystore` / 任何签名密钥
- `~/.dsh/mobile-bridge/config.json`、`devices.json`
- 真实的 Tailscale IP、ntfy topic、设备 token
- `android-app/app/assets/runtime/`（84 MB 运行时资源）
