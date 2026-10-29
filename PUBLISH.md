# 发布到 GitHub

本地 git 仓库已初始化并完成首次提交（`main` 分支，123 个文件，不含 APK 与本机配置）。
剩下三步：建仓库 → 推代码 → 发 Release。

## 0. 先确认你已经准备好了什么

```bash
git log --oneline        # 应看到 "Initial commit: NFCTool Android NFC 工具箱"
git status --short       # 应为空
git ls-files | wc -l     # 123
```

已经排除、不会进仓库的内容（`.gitignore`）：

| 排除项 | 原因 |
|---|---|
| `build/` `dist/` `*.apk` `*.aab` | 构建产物；APK 走 Release 分发 |
| `local.properties` | 含本机 Android SDK 绝对路径 |
| `gradle.properties` | 含本机 JDK 绝对路径（仓库内提供 `gradle.properties.example`） |
| `.workbuddy/` | 工作记忆，含本机路径与调试记录 |
| `.icon-backup-*` | 图标迭代的临时备份 |

## 1. 创建仓库

网页：<https://github.com/new>

- Repository name：`NFCTool`
- Description：Android NFC 标签读写工具箱 —— 读取 / 写入 / 复制 / 整卡备份 / 卡模拟，Compose 实现
- 可见性：Public
- **不要勾选** Initialize with README / .gitignore / license（本地已有，勾了会产生冲突的历史）

## 2. 推送代码

### 方式 A：git push（最直接）

```bash
git remote add origin https://github.com/lichuang07/NFCTool.git
git push -u origin main
```

HTTPS 会要求认证：用户名填 `lichuang07`，密码填 **Personal Access Token**（不是登录密码）。
Token 在 GitHub → Settings → Developer settings → Personal access tokens 生成，勾 `repo` 权限。

想一劳永逸就配凭据管理器（Git for Windows 自带）：

```bash
git config --global credential.helper manager
```

### 方式 B：走 REST API（github.com 不通时的备选）

某些网络环境下只有 `api.github.com` 可达（`github.com` 的 443 / 22 被代理拦掉，
`git push` 与 SSH 都会失败，表现为 `CONNECT tunnel failed, response 502`）。
这时用仓库里的脚本，它只调 `api.github.com` 与 `uploads.github.com`：

```bash
# 建仓库 + 推代码 + 发 Release 一步到位
python tools/publish_github.py --token ghp_xxxx

# 分开做
python tools/publish_github.py --token ghp_xxxx --skip-release   # 只推代码
python tools/publish_github.py --token ghp_xxxx --skip-code      # 只发 Release
```

脚本会：逐个文件创建 blob（二进制自动 base64）→ 分批建 tree → commit → 指向 `main`
→ 创建 `v1.0.1` Release → 上传 `dist/` 下两个 APK。仓库已存在时自动复用，可重复运行。

## 3. 发布 Release

### 网页方式

1. 仓库页右侧 **Releases** → **Create a new release**
2. Tag：`v1.0.1`（与 `app/build.gradle.kts` 的 `versionName` 一致）
3. 标题：`NFCTool v1.0.1`
4. 正文：粘贴 `docs/RELEASE_NOTES.md` 的内容
5. 附件：拖入 `dist/` 下两个 APK
6. **Publish release**

### 附件清单

```
dist/NFC工具箱-5.0MB-主推.apk          5,204,164 B   日常用
dist/NFC工具箱-20.3MB-不混淆对照.apk   21,321,349 B  只在主推版装不上时对比
```

## 4. 发布后检查

- `https://github.com/lichuang07/NFCTool` 能看到 README 与源码
- 源码树里**没有** `dist/`、`app/build/`、`local.properties`、`gradle.properties`
- Release 页两个 APK 都能下载
- 手机装一次主推版（先 `adb uninstall com.nfctool.app`）确认能装能开

## 5. 以后更新版本

```bash
# 1) 改版本号：app/build.gradle.kts 的 versionName（versionCode 顺手 +1）
# 2) 重新出包
bash tools/build_release.sh
# 3) 提交并打 tag
git add -A && git commit -m "release: v1.0.2"
git tag v1.0.2
git push origin main --tags
# 4) 网页上基于新 tag 建 Release，或：
python tools/publish_github.py --token ghp_xxxx --skip-code
```

> 判断安装兼容性时只看 **clean 构建**的体积：同一份代码 clean 构建 5.10MB、
> 增量构建 9.58MB —— 增量产物不可信。
