# NFCTool

Android NFC 标签读写工具箱。把手机 NFC 能做的事做成看得见、可操作的界面 ——
读取、写入、复制、整卡备份、原始指令调试、卡模拟。

- 包名 `com.nfctool.app`，Jetpack Compose 单 Activity 实现
- 58 个源文件 / 约 17,700 行 Kotlin，20 个测试类 / **255 个单元测试全绿**
- 简体中文 + English 双语（约 890 条字符串 × 2）

## 功能

| 分类 | 能力 |
|---|---|
| **读取** | 卡片型号自动识别（NTAG21x / Ultralight / Classic / DESFire / ISO 15693 / FeliCa / 银行卡 / 门禁卡）、NDEF 四态区分、块级 dump、导出 JSON |
| **NDEF 细分类型** | 在 TNF 之上做应用级二次归类：Wi-Fi 配网 / 名片 / 应用记录 / 移交 / 地理位置 / 蓝牙 OOB / AID |
| **写入** | 18 种类型分 6 组；写入任务挂起等标签靠近，写完**回读逐字节校验** |
| **复制标签** | 复制整条 NDEF 消息字节（保留 ID / MB·ME / SR 元信息），可连续复制多张 |
| **整卡备份** | Classic（按扇区，需密钥）与 NTAG / Ultralight（按页，无需密钥）存成 JSON，再写回同型号卡 |
| **MIFARE 密钥本** | 保存常用密钥，备份时依次尝试（临时输入 → 密钥本 → 默认密钥） |
| **模板收藏夹** | 常用内容存下来，点一下自动填好写入表单 |
| **控制台** | 原始指令 / APDU 透传、SELECT AID、通信日志 |
| **HCE** | 手机扮演卡片，AID 声明式注册 |
| **后台读卡** | 前台服务 + 动态 receiver |
| **接收分享** | 地图 / 浏览器 / 联系人 / 图片分享进来自动预填写入表单 |

## 下载

最新 APK 见 [Releases](https://github.com/lichuang07/NFCTool/releases/latest)。

| 文件 | 体积 | 说明 |
|---|---|---|
| `NFC工具箱-5.0MB-主推.apk` | 5.0 MB | 日常用这个。R8 混淆 + 资源裁剪 + 仅 arm64-v8a |
| `NFC工具箱-20.3MB-不混淆对照.apk` | 20.3 MB | 仅裁 ABI、不混淆。**只在主推版装不上时**用来对比定位 |

安装前若已装过旧版，先卸载：`adb uninstall com.nfctool.app`。

> 体积刻意压在 10MB 以内：实测超过 10MB 的包在部分国产 ROM 上传输会被截断，
> 安装器报出的错误与真实原因无关（详见 `docs/wiki/04-构建签名与安装兼容.md`）。

## 从源码构建

环境要求：JDK 17、Android SDK（compileSdk 37、build-tools 37）。

```bash
git clone https://github.com/lichuang07/NFCTool.git
cd NFCTool
cp gradle.properties.example gradle.properties   # 按需填写 JDK 17 路径

# 需要 NFC 硬件才能跑完整功能，但编译与单测在任何机器上都能跑
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug --no-configuration-cache
./gradlew testDebugUnitTest --no-configuration-cache
```

常用的两个脚本：

```bash
bash tools/build_release.sh      # clean 构建 + APK 四项校验 + 收集到 dist/
bash tools/diagnose_install.sh   # 安装失败时拿真实错误码
```

`build_release.sh` 内置的**四项校验**（国产 ROM 安装失败时按这个顺序排查）：

```
apksigner verify --print-certs -v app.apk   # v2 + v3 Verifies
zipalign -c 4 app.apk                       # Verification successful
unzip -t app.apk                            # No errors
unzip -l app.apk | grep '$'                 # 应无输出（禁止 <aapt:attr> 生成的内部资源）
```

> 判断安装兼容性时**必须用 clean 构建的体积**：同一份代码 clean 构建 5.10MB，
> 增量构建 9.58MB —— 增量产物不可信。

## 项目结构

```
app/src/main/java/com/nfctool/app/
├── core/
│   ├── nfc/            TagSession、NdefManager、MifareOps、MifareBackup、TagCloner
│   │   └── ndef/       各类型 Codec（纯字节）、NdefMessageCodec、NdefTypeRegistry
│   ├── hce/            卡模拟
│   ├── service/        后台读卡
│   └── export/         JSON 导出与分享
├── data/               DataStore 持久化（历史、设置、密钥本、模板）
└── ui/                 MainViewModel、5 个 tab 页、写入详情、复制页、主题
```

页面路由：底部 5 个常驻 tab（`read` / `write` / `console` / `history` / `settings`）
+ 2 个全屏任务页（`write_detail/{id}`、`clone`）。

## 几条改代码前该知道的约定

完整版见 `docs/wiki/02-架构约定与开发规范.md`，这里是最容易踩的三条：

1. **Codec 必须拆成纯字节** —— `NdefRecord` 在 JUnit 里不可用，payload 组装一律拆成
   `encodePayload` / `decodePayload` 两个纯函数，`NdefRecord` 只做薄壳。不这么做就写不了单测。
2. **NDEF 写完必须回读校验** —— 部分机型在标签离开感应区时 `writeNdefMessage` 不抛异常却没写进去。
3. **Compose 渐变至少要 2 个颜色** —— 颜色数校验发生在**绘制阶段**
   （`validateColorStops`），只给 1 个颜色时编译 / lint / 单测全过，真机渲染到才抛异常，
   表现为「进页面直接闪退」。

其他：同一 Tag 同时只能连一个 `TagTechnology`（探测页数后必须 close）；
`enableReaderMode` 的宿主必须是 Activity（后台读卡因此走前台服务）；
HCE 的 AID 只能声明式注册。

## 已知限制

- **不能用来复制门禁卡**。门禁读头大多只认 UID，而 UID 出厂锁定、不可写；
  手机钱包能模拟是因为走厂商私有通道 + 系统签名权限，第三方 App 拿不到。
  整卡备份（Classic 与 NTAG 都一样）**不碰密钥尾块、不写 UID**，恢复出来的卡仍是目标卡自己的 UID。
- **蓝牙 OOB 记录 Android 系统不自动解析**，只当普通 MIME 派发，界面如实提示。
- **MIFARE Classic 卡模拟做不到** —— HCE 只处理 ISO-DEP 的 APDU，而 Classic 是 ISO 14443-3 上的私有协议。
- DESFire EV2 密钥管理、标签导入（JSON 反写）、HCE 可配置卡片尚未实现，见 `docs/wiki/06-需求Backlog与路线图.md`。

**边界声明**：本工具只对自己拥有的卡片做读写与调试，不提供任何密钥破解 / 绕过门禁的能力。
密钥本里的每一条密钥都由用户自己录入。

## 文档

`docs/wiki/` 下有 6 篇技术文档：

| 篇目 | 内容 |
|---|---|
| 01 项目概览 | 定位、技术栈、页面结构、核心能力 |
| 02 架构约定与开发规范 | 不可回退的约定、写入可信性、返回手势、Compose 踩坑 |
| 03 NFC 协议事实笔记 | `javap` 核对过的 API 事实、NDEF 消息层布局、各类型要点 |
| 04 构建签名与安装兼容 | 构建约束、四项校验、adb 调试要点 |
| 05 代码评审与质量现状 | 7 个协议层 P0 的修复清单 |
| 06 需求 Backlog 与路线图 | 待办与功能候选 |

另有 `NFC功能清单.md`（完整功能说明）与 `代码评审报告.md`。

## 质量状态

| 项 | 结果 |
|---|---|
| clean 构建 | 通过 |
| lint | 0 error |
| 单元测试 | 255 通过 / 0 失败 |
| APK 四项校验 | 全部通过 |
| 真机 NFC 读写 | **待完整验证**（需实体标签） |

代码已通过编译、静态检查与全部单测，但 NFC 读写尚未在实体标签上逐项跑过 ——
这是目前最该补上的一环。

## 许可

[MIT License](LICENSE) © 2026 Li Chuang

代码可自由使用与修改。APK 以现状提供，使用前请确认你有权读写目标卡片。
