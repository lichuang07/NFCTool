# NFC 工具箱 — 功能与调研清单

> 工程路径 `F:/Work/App/NFCTool/` ｜ APK `app/build/outputs/apk/debug/app-debug.apk`
> 技术栈：Kotlin 2.4.21 + Compose BOM 2026.09.00 + Material 3 ｜ minSdk 24 / target 37

---

## 一、手机 NFC 到底能做什么（调研结论）

手机 NFC 芯片的能力边界由 Android 的 `android.nfc.tech` 技术族决定。完整清单如下：

| 技术 | 协议 | 读 | 写 | 说明 |
|---|---|:--:|:--:|---|
| `Ndef` | NFC Forum NDEF | ✅ | ✅ | 文本 / 链接 / MIME / Smart Poster / 外部类型 |
| `NdefFormatable` | NFC Forum Type 1-4 | — | ✅ | 空白标签，写入前需先格式化 |
| `MifareClassic` | ISO 14443-4 | ✅ | ✅ | 1K/4K 分区卡，需密钥认证 |
| `MifareUltralight` | ISO 14443-3 | ✅ | ✅ | NTAG / UL 轻量卡，4 字节页 |
| `IsoDep` | ISO 7816-4 | ✅ | ✅ | 智能卡 / 银行卡 / 身份证，APDU 透传 |
| `NfcA` | ISO 14443-3 | ✅ | ✅ | 底层帧收发 |
| `NfcB` | ISO 14443-3 | ✅ | ✅ | 底层帧收发 |
| `NfcF` | JIS 6319-4 | ✅ | ✅ | FeliCa 公交卡 |
| `NfcV` | ISO 15693 | ✅ | ✅ | Vicinity 卡，块寻址 |
| `NfcBarcode` | Sony/Kovio | ✅ | — | 只读条码标签 |

**手机做不到的**（重要边界）：
- 无法伪造 UID（UID 由卡片出厂固化）
- 无法读取 DESFire 的加密文件（除非知道密钥）
- 无法读取银行卡 CVV 等受保护数据
- 无法复制"加密门禁卡"（除非卡片允许明文读）
- 部分标签出厂即锁定（永久锁定位块）

**系统级新能力**（本应用已做能力探测并在设置页展示）：

| 能力 | API | 用途 |
|---|:--:|---|
| Secure NFC | 29 | 交易后自动关闭 NFC |
| NfcAntennaInfo | 34 | 感知天线在机身上的位置 |
| Observe Mode | 35 | 被动监听读卡器发出的帧，不响应 |
| Reader Option | 35 | 系统级读卡开关 |
| Tag Intent 授权 | 36 | 查询系统是否禁止本应用接收标签 |
| setDiscoveryTechnology | 35 | 精细控制轮询哪些射频协议 |

---

## 二、已实现功能

### 读取页
- 雷达扫描动画 + 实时状态提示
- **UID 展示**：标准 UID、反向 UID、长度，一键复制
- **卡片型号自动识别**：NTAG213/215/216、Ultralight、Ultralight C、Classic 1K/4K、Plus、DESFire、ISO 15693、FeliCa、银行卡、身份证、门禁卡
- **NDEF 解析**：逐记录展示类型、载荷大小、文本内容、URI、原始十六进制
- **NDEF 细分类型识别**：不只按 TNF 归类，而是在 TNF 之上做应用级二次归类，
  读到的标签会明确显示这是"Wi-Fi 配网""电子名片""应用记录"还是普通 MIME：
  - **Wi-Fi 配网**（`application/vnd.wfa.wsc`）→ 解析出网络名、密码、认证类型、是否隐藏网络
  - **电子名片**（`text/vcard`）→ 解析出姓名、单位、职位、电话、邮箱、网址、地址
  - **应用记录**（`android.com:pkg`）→ 解析出目标包名
  - **连接移交**（`Hr` / `Hs`）→ 解析出可选链路（蓝牙 / Wi-Fi / URI 等）
  - **地理位置**（`geo:` URI）→ 解析出纬度、经度、海拔、不确定度、名称、缩放
  - **蓝牙配对**（`application/vnd.bluetooth.ep.oob` / `...le.oob`）→ 解析出蓝牙地址、设备名与携带的 AD 结构
  - **AID 标识**（Type 4 应用标识字节）→ 解析出 AID 十六进制与长度
  - **普通 MIME / 外部类型 / 未知类型** → 给出 MIME 类型、域/子类型、文本预览或二进制预览
- **协议层原始信息**：每条记录附带 TNF 编号、type 原始字节、记录 ID、完整 hex
- **NDEF 区状态区分**：明确区分「标签不支持 NDEF」「NDEF 区为空（空白标签）」
  「NDEF 读取失败」「正常有数据」四种情况，不会一律显示成空白
- **技术矩阵**：每种技术的可读/可写标记与说明，附系统原始 techList 供对照
- **容量信息**：NDEF 容量与可写状态、扇区/块数、页数、传输协议、历史字节
- **块级数据读取**：MIFARE Classic 全卡 dump、Ultralight 全部用户页，逐块十六进制展示
- 设备能力面板：Secure NFC / 观察模式 / Reader 选项 / 天线位置 / 标签事件授权

### 写入页

写入页是**纯目录**：只列出所有可写入的类型，按分组分区展示；单击某一类型进入
该类型专属的表单页。这样类型再多也不会把写入页堆成一长条表单。

路由：`write`（目录）→ `write_detail/{entryId}`（详情）。

#### 分组与条目

| 分组 | 条目 | 说明 |
|---|---|---|
| 基础内容 | 纯文本 | UTF-8 手工构造，中文不乱码 |
| 基础内容 | 链接 | URI / 深链 |
| 基础内容 | Wi-Fi 配网 | `application/vnd.wfa.wsc`，安卓碰一下弹窗确认 |
| 基础内容 | 地理位置 | `geo:` URI（RFC 5870），碰一下直接唤起地图 |
| 联系与日程 | 电子名片 vCard | `text/vcard`，可导入系统通讯录 |
| 联系与日程 | 电子名片 MECARD | `text/x-mecard`，紧凑省空间 |
| 联系与日程 | 日历事件 | `text/vcalendar`，VCALENDAR 2.0 + VEVENT |
| 联系与日程 | 智能海报 | NFC Forum RTD "S"：S 记录 + 多条 U 记录 |
| 系统与协议 | 应用唤起 | AAR（`android.com:pkg`），强制派发给指定应用 |
| 系统与协议 | 连接移交 | `Hr` / `Hs` 移交记录，载体可选 URI / 蓝牙 / Wi-Fi / NFC |
| 系统与协议 | AID 标识 | Type 4 标签的应用标识（十六进制字节串） |
| 系统与协议 | 蓝牙配对 | OOB 记录（BR/EDR 与 LE 两种 MIME），供蓝牙设备快速配对 |
| 高级自定义 | 自定义 MIME | 任意 MIME 类型与内容 |
| 高级自定义 | 外部类型 | `domain:type` 形式的 TNF 0x04 记录 |
| 高级自定义 | 图片 | 写入图片，自动压缩到标签容量以内 |
| 高级自定义 | 二维码 | 把网址或文本转成二维码写入标签 |
| 存储技术 | MIFARE Classic 块 | 按扇区认证后读写数据块，可指定 Key A |
| 存储技术 | MIFARE Ultralight 页 | 按页号读写 4 字节用户数据 |
| 存储技术 | ISO 15693 块 | NFC-V 块寻址读写 |
| 存储技术 | DESFire 高级操作 | APDU 直通与文件管理 |
| 标签管理 | 格式化标签 | 清除全部内容，带二次确认 |
| 标签管理 | 恢复出厂设置 | 逐扇区恢复默认密钥与标准 MAD |

「存储技术」分组中的条目需要标签具备对应技术才能写成功（如 MIFARE Classic 块），
但目录**始终全部展示** —— 写入不依赖先读一次卡，技术不匹配时由写入环节给出明确错误。

#### 写入流程（重要，2026-10-25 重构）

旧流程：**必须先到「读取」页扫一张卡 → 再回写入页点写入**。
断点很明显：没读过卡时点写入只会弹「请先读取一张标签」，用户被赶去读取页走一圈。

新流程（与主流 NFC 工具一致）：

```
填好表单 → 点「写入」 → 弹窗「请将 NFC 标签靠近手机背面」
   → reader mode 检测到标签的瞬间就地执行写入（不经过读取流程）
   → 成功 / 失败弹窗汇报 → 关闭提示
```

实现要点：

- **写入任务挂起而非立即执行**。`MainViewModel.launchWrite()` 只把
  `PendingWrite(label, block, onDone)` 记下来并置 `awaitingTag = true`，
  真正的执行在 `onTagDiscovered()` 里：检测到有待写任务时直接
  `executePendingWrite(tag, pending)`，跳过读取流程，用这张刚到场的标签开会话写入。
- **提示弹窗是全局的**（挂在 `NfctoolAppRoot`，不是页面内）。
  写入任务可能跨页面存活 —— 用户在写入页点了写入、返回目录时才贴卡，
  页面内的弹窗会随导航消失，全局的不会。写入启动后文案切为「正在写入」且禁止取消。
- **防抖要配合重置**。`handleIntentTag` 有 1.5 秒同 UID 防抖，
  点击写入时主动清空防抖记录，否则「刚扫过同一张卡再点写入」会被防抖吞掉，
  表现为点了写入没反应。写入完成（无论成败）同样重置，保证可以立刻重试。
- **写入按钮不再被「可写性」门控**。旧代码用上次读到的 `overview` 判断按钮是否可点，
  等于把写入绑死在「先读一次」上。现在只有**明确知道不可写**（上次读到的那张卡被锁定）时
  才给提示，按钮照样可点 —— 真正的写入对象是用户贴近的那张卡，
  容量 / 只读 / 格式不支持等校验全部在写入时实测并给出错误。
- 控制台的「发送原始指令 / 发送 APDU / 选择 AID」走同一条 `launchWrite`，
  因此也自动获得「点发送 → 贴近标签 → 执行」的行为。

#### 写入结果可信性（重要）

写入不是「调了 API 没抛异常就算成功」，而是**写完立刻回读校验**：

1. **写入前**不再要求预先扫描标签（见上一节的新流程）
2. **写入后**立刻把标签上的 NDEF 消息读回来，与期望写入的内容
   逐字节比对（TNF / type / ID / payload 四项全等）
3. 不一致时明确报错并指出差异位置，例如
   「写入后回读校验失败：第 1 条记录的载荷不一致（期望 12 字节，实际 0 字节）」

这条链路是必要的：**部分机型在标签已离开感应范围时，
`writeNdefMessage()` 不会抛异常，却实际什么都没写进去**。
只依据「没抛异常」报成功，就会出现「没贴卡也显示写入成功」的假象。

写入页顶部状态条展示的是**上次读取的参考信息**（型号 / UID / 容量），
仅供参考：真正写入的是点写入后贴近的那张标签。
- `写入无需预先读卡` —— 直接点写入，贴近标签即可
- `上次读取：NTAG213 · UID 04A1… · NDEF 137 字节`
- `上次读取：NTAG213（空白标签）` —— 写入时会先格式化为 NDEF

#### 各类型要点
- **Wi-Fi 配网**：填网络名、密码、安全类型（WPA2-PSK / WPA-WPA2 / 开放）、
  可选隐藏网络。安卓手机碰一下标签即弹出连接提示；iPhone 仅通知，需手动确认。
  字节布局与 Android 系统 `NfcWifiProtectedSetup` 解析逻辑严格对齐（有单测对拍）。
- **vCard / MECARD**：MECARD 比 vCard 省空间，日系手机原生识别；安卓需第三方应用支持。
- **智能海报**：标题可选，链接每行一个；S 记录手工构造（`android.nfc.SmartPoster` 是隐藏 API）。
- **连接移交**：现代 Android 已基本不用这套机制，仅作协议层写入与调试用。
- **AID**：勿在生产标签上使用支付类 AID。
- **地理位置**：`geo:纬度,经度[,海拔][;u=不确定度][?q=名称][&z=缩放]`。
  纬度限 [-90, 90]、经度限 [-180, 180]，名称走百分号编码（中文不乱码）。
  Android **没有** `createGeoUri()`，本应用走 `createUri("geo:...")`，
  而 `geo:` 本身是系统原生支持的 scheme，碰一下会直接进地图应用。
- **蓝牙配对**：写 Bluetooth Core Spec 5.2.2.7 定义的 OOB 记录，两个 IANA 注册 MIME：
  - `application/vnd.bluetooth.ep.oob`（BR/EDR）：`OOB 长度(2,小端,含自身)` + `BD_ADDR(6,小端)` + AD 结构
  - `application/vnd.bluetooth.le.oob`（LE）：纯 AD 结构序列，无长度头

  ⚠️ **Android 系统并不自动解析蓝牙 OOB 记录**（AOSP `NfcService` 中无任何 OOB 处理），
  它只会当作普通 MIME 记录派发；真正响应的是厂商自注册该 MIME 的配套 App，iPhone 完全不认。
  本项定位为**协议层写入与调试**，不保证通用。
  AD 结构遵循蓝牙规范：`[长度(1)][类型(1)][数据]`，长度字段 = 类型 + 数据字节数（不含长度自身）。

### 接收分享（作为分享目标）

本应用可作为**系统分享目标**出现在其他应用的「分享」菜单里：
在地图应用分享一个地点、在浏览器分享一个链接、在备忘录分享一段文字，
选本应用后会自动跳到对应的写入页并**预填好表单**，用户只需贴标签写入。

#### 分享来源与判定逻辑

**两级判定**：

```
第一级：Intent 层面 —— 判断是文本还是图片
  ↓  ShareIntentRouter（core/share/）
第二级：内容层面 —— 文本再细分成链接/位置/名片/纯文本
  ↓  SharedContentParser（core/nfc/ndef/）
```

##### 第一级：为什么 MIME type 是首要判据

⚠️ **Chrome 分享网页时，Intent 里同时带 URL 和页面截图**：
```kotlin
intent.type = "text/plain"                    // MIME 是文本
intent.putExtra(EXTRA_SUBJECT, titleOfPage)  // 网页标题
intent.putExtra(EXTRA_TEXT, urlOfPage)       // 网页 URL
intent.putExtra(EXTRA_STREAM, screenshotUri)  // 页面截图！
```
Chrome 会顺手把当前页面截个图放进 `EXTRA_STREAM`。

**所以不能「看到 EXTRA_STREAM 就当图片」** —— 那会让 Chrome 分享网页
进「图片写入」页，而用户明明分享的是链接。

判定表：

| Intent MIME | 判定 | 说明 |
|---|---|---|
| `text/` 前缀 | **文本** | 明确说了是文本，附带的截图（Chrome）被忽略 |
| `image/` 前缀 | 图片 | 相册、图库、截图分享 |
| 通配 / 空 | 看内容 | 有文本走文本，否则走图片 |

Chrome 分享网页 → `text/plain` + URL + 截图 → **走文本** →
再由第二级识别出是链接 → 进「链接」页。

##### 第二级：文本内容再分类

| 内容特征 | 归类 | 写入页 |
|---|---|---|
| `geo:39.9,116.4?q=天安门` | 地理位置 | 地理位置页 |
| 高德 `position=` / 百度 `location=` / 腾讯 `coord:` | 地理位置 | 地理位置页 |
| `https://` 开头（或混合文本里含 URL） | 链接 | 链接页 |
| `BEGIN:VCARD...END:VCARD` | 电子名片 | 电子名片页 |
| 其余 | 纯文本 | 纯文本页 |

##### 已知外部问题：Chrome 连续分享会复用旧 URL

Chrome 自身的一个 bug：连续分享不同网页时，`EXTRA_TEXT` 会**复用上一次的 URL**
（`EXTRA_SUBJECT` 标题是正确的，`ClipData.text` 恒为 null，无 workaround）。
Firefox / Edge 无此问题。
本应用无法从 Intent 侧修复，但会在链接页如实显示标题与 URL，便于用户察觉。

#### 支持的分享来源一览

| 来源 | 分享出来的形态 | 预填到 |
|---|---|---|
| 地图应用「分享地点」 | `geo:39.9,116.4?q=天安门` | 地理位置页（坐标 + 名称） |
| Google 地图 | `https://maps.google.com/?q=39.9,116.4`、`.../place/xxx/@39.9,116.4,15z` | 地理位置页 |
| 高德地图 | `https://uri.amap.com/marker?position=116.4,39.9&name=xxx` | 地理位置页 |
| 百度地图 | `https://api.map.baidu.com/marker?location=39.9,116.4&title=xxx` | 地理位置页 |
| 腾讯地图 | `https://apis.map.qq.com/uri/v1/marker?marker=coord:39.9,116.4;title:xxx` | 地理位置页 |
| 浏览器分享网页 | `EXTRA_TEXT` = URL，`EXTRA_SUBJECT` = 页面标题 | 链接页（URL + 标题） |
| 任意应用的链接 | `https://example.com/page` | 链接页 |
| 备忘录 / 微信 | 一段纯文本 | 纯文本页 |
| **分享联系人**（系统通讯录 / 微信） | `text/vcard` MIME 或 `BEGIN:VCARD...END:VCARD` 文本 | 电子名片页（姓名/电话/邮箱/单位/职位/网址/地址全填好） |
| **分享图片**（相册 / 图库 / 截图） | `image/*` MIME 的 content URI | 图片页（自动压缩后写入） |
| **分享文本文件**（文件管理器分享 .txt / .vcf） | `text/*` MIME，内容只在 `EXTRA_STREAM` | 按实际内容分流：vCard → 名片页，其余 → 纯文本页 |

#### Intent 生命周期（重要，2026-10-25 修复）

分享 / 标签 Intent 的接收有两个**时序坑**，都已修复：

1. **冷启动 Intent 曾被静默丢弃**：`viewModel` 在 Compose 组合里才回填，
   而窗口 attach 发生在 `onResume` **之后**（ActivityThread：
   performResumeActivity → onResume → wm.addView → 首帧组合）。
   冷启动时 `onResume` 里的 `viewModel?.handleSharedText(...)` 是空调用。
   → 改为暂存 `pendingIntent`，组合就绪后 `onViewModelReady()` 补处理。
2. **Intent 不消费会被重复处理**：`onNewIntent` 处理后若不清除，
   每次 `onResume` 都会再处理一遍 —— 图片重复读流压缩，更糟的是
   用户改过表单后按 Home 再回来，**表单被覆盖成最初的分享内容**。
   → 处理完置 `intentHandled`（随 savedInstanceState 持久化防旋转重复）
   并换上空 Intent；`onNewIntent` 里先置 false 再处理。

#### 地图链接的 host 校验（2026-10-25 修复）

`position` / `location` / `marker` / `q` 都是极常见的 query 参数名。
早年只对 Google 校验域名，结果 `https://example.com/wiki?position=116.4,39.9`
这类普通网页会被判成高德地图坐标，写出的标签坐标语义完全错误。
现在按 host 门控（`*.amap.com` / `*.baidu.com` / `*.map.qq.com` /
google maps 域名 + `/maps` 路径），不匹配一律按普通链接处理。

#### 浏览器分享网页的两种形态

**已核对 Chrome 源码与官方文档**，浏览器分享网页有两种常见形态：

1. **标准形态**（Chrome / Edge / Firefox）：
   `EXTRA_TEXT` 放 URL，`EXTRA_SUBJECT` 放页面标题 —— 两个都要读。
2. **组合形态**（部分国产浏览器、微信内置浏览器、邮件客户端）：
   `EXTRA_TEXT` 是「标题 + 换行 + URL」甚至「URL + 分享语」的混合文本。

所以解析器会**从任意文本里摘出第一个 URL**，URL 之前的部分作为标题。
拿到标题后，链接页会同时提供两个按钮：
- **写入链接** —— 只写 URL
- **写成智能海报** —— 写「标题 + URL」复合记录，碰一下显示标题，点进去打开链接

#### ⚠️ 经纬度顺序是最大的坑

各家地图的参数顺序**并不一致**，搞反会写到地球另一半：

| 来源 | 参数 | 顺序 |
|---|---|---|
| `geo:` URI（RFC 5870） | 坐标 | **纬度, 经度** |
| Google 地图 | 路径 `@` 后 | **纬度, 经度** |
| 高德地图 | `position=` | **经度, 纬度** ⚠️ |
| 百度地图 | `location=` | **纬度, 经度** |
| 腾讯地图 | `marker=coord:` | **纬度, 经度** |

所以每种来源必须**独立**解析，不能套用同一个模板。

#### ⚠️ 坐标系差异

高德/腾讯用 GCJ-02，百度用 BD-09，而 `geo:` URI 按 RFC 5870 期望 WGS-84。
本应用**不做坐标系转换**（离线且无密钥），但会在表单顶部
如实标出来源与坐标系，避免用户误判定位精度。

#### 图片写入的容量现实

标签的 NDEF 容量**非常小**：

| 标签 | NDEF 容量 |
|---|---|
| NTAG213 | 137 字节 |
| NTAG215 | 504 字节 |
| NTAG216 | 888 字节 |

而手机照片动辄 2~5 MB，**直接写必然失败**。
所以本应用会先自动压缩：

1. 按最大边长 1024 像素缩放
2. 转 JPEG 并按 85 → 70 → 55 → 40 逐级降质量，直到压进容量
3. 图片页会如实展示：原始大小、压缩后大小、尺寸、压缩比、标签容量
4. 压不进就**明确报出超出多少字节**，并建议改用二维码（URL 方式）

⚠️ 写入标签里的是**低分辨率压缩版**，不是原图。
若要保留清晰图片，请改用「二维码」方案（把图片 URL 写进标签）。

### 二维码

把任意网址或文本转成二维码写进标签。**实时预览** —— 输入即刻看到效果。

#### 写入模式

三种模式，容量需求差别很大：

| 模式 | 写入内容 | 占用 | 用途 |
|---|---|---|---|
| **内容+码** | URI/Text 记录 + `image/png` 记录 | 约 250~400 字节 | 两种用法都成立：碰一下直接打开，也能扫码 |
| **仅内容** | 只有 URI/Text 记录 | 约 30~100 字节 | 最省空间，标签再小也放得下，碰一下直接打开 |
| **仅二维码** | 只有 `image/png` 记录 | 约 200~320 字节 | 内容通过扫码获得，适合内容很长时 |

⚠️ **实测数据**：真实二维码的 1-bit PNG 约 **200~320 字节**，
所以 **NTAG213（137 字节）放不下二维码图** —— 这时必须用「仅内容」模式。
UI 上会实时提示这一点。

#### 二维码 PNG 的体积特性

**与直觉相反**：1-bit + deflate 的组合让体积对模块大小**不敏感**。
实测同一条链接：

| 模块边长 | 像素尺寸 | PNG 体积 |
|---|---|---|
| 1px | 29×29 | 203 字节 |
| 2px | 58×58 | 221 字节 |
| 4px | 116×116 | 248 字节 |
| 6px | 174×174 | 312 字节 |

模块变大 36 倍（面积），体积只涨 1.5 倍 ——
因为像素变多但每行的重复模式也变多，deflate 压得更狠。
所以**优先用大模块**（扫得更准），不必为了省字节牺牲清晰度。

#### 自己实现 1-bit PNG 编码器

不用 Android 的 `Bitmap.compress`，改用纯 Java 的 `PngWriter`：

1. **体积更小**：1 bit/像素 vs `ALPHA_8` 的 8 bit/像素
2. **可测试**：Android 的 `Bitmap` 在 JUnit 里不可用，
   而 PNG 体积恰恰是最需要验证的东西。纯 Java 实现可以直接写单测
   （用 ImageIO 解码回来逐像素比对）
3. **不分配位图**：直接按行输出字节

#### 依赖

```
implementation("com.google.zxing:core:3.5.3")
```

纯 JVM、无 Android 依赖，只做编码（不含扫码 UI）。
`core` 不含 `BufferedImageLuminanceSource`（那在 `javase` 模块，会拖进 AWT），
单测里自己实现 `LuminanceSource` 接口即可，无需额外依赖。

#### 容错设计

- 识别到地图格式但内容非法（如坐标越界、缺少参数）→ **明确报错**，
  不静默降级为纯文本。否则用户会写入一个「碰一下毫无反应」的标签却不知原因。
- 非地图的普通链接 → 当作链接写入，不误判成坐标。
- 连续分享相同内容 → 用递增序号作触发键，保证每次都能重新预填。
- URL 提取时**保留 ASCII 逗号**（地图 URL 的坐标参数是 `lat,lon`，
  排除逗号会把 `?q=39.9,116.4` 截断成 `?q=39.9`），
  改为匹配完整 token 后再从尾部剥离中文标点与句末符号。

### 控制台页
- **原始指令透传**：任意十六进制帧 → 卡片（走 NfcA/B/F/V/IsoDep 通道）
- **APDU 模式**：ISO 7816-4 指令，自动处理 `61xx` 链式续传与 `6Cxx` 长度纠错
- **SELECT AID** 快捷执行
- 常用指令速查（读 NDEF、选银行卡 AID、GET RESPONSE、GET DATA）
- 完整通信日志（TX/RX/INF/ERR 分色，带时间戳）

### 历史页
- 最近 200 条扫描记录（DataStore 持久化）
- 显示卡片类型、UID、技术摘要、NDEF 摘要、时间
- 复制 UID、删除单条、清空全部

### 设置页
- **语言：跟随系统 / 简体中文 / English**（2026-10-25 新增，切换即时生效，见下方专章）
- 主题模式：深色 / 浅色 / 跟随系统
- **5 套强调色**：极光青 / 霓虹粉 / 星云紫 / 熔岩金 / 数据绿
- 扫描行为：震动反馈、跳过 NDEF 预检查（提速 ~500ms）、轮询 NFC-B
- **后台读卡开关**（connectedDevice 类型前台服务）
- **主机卡模拟面板**：设备支持情况、已注册 AID、是否默认服务、跳转系统卡模拟设置
- 设备信息与能力清单
- 应用说明

---

## 二·五、第二阶段新增功能（20:00 - 20:40）

### ① 主机卡模拟 HCE —— 让手机变成一张卡

读卡器（公交机、门禁头、自助终端）靠近手机时，把 APDU 指令发给手机，本应用负责响应。

| 项 | 实现 |
|---|---|
| AID 注册 | `res/xml/apdu_service.xml`，厂商私有 AID `F0010203040506` / `F0394148148100` |
| 服务 | `NfcHceService : HostApduService` |
| 支持指令 | SELECT AID（返回 FCI 模板）、GET DATA、GET VERSION、GET UID |
| 状态查询 | 设备支持 / 已注册 AID / 是否默认服务 |
| 跳转设置 | `CardEmulation.ACTION_CHANGE_DEFAULT` |

**刻意不碰支付类 AID** —— 本应用不参与任何支付流程，避免与钱包应用冲突。

### ② DESFire 高级操作

在通用 APDU 透传之上封装了 DESFire EV1/EV2 的常用指令：

| 指令 | APDU | 说明 |
|---|---|---|
| GetVersion | `90 60 00 00 00` | 读版本，如 EV1.1 / EV2.2 |
| GetFileIDs | `90 F2 <fileId> 00 00 00` | 读文件列表 + 访问权限 |
| ReadFile | `90 F5 <fileId> <offset> <mode> <len>` | 读文件内容 |
| WriteFile | `90 F3 <fileId> <offset> <mode> <len> <data>` | 写文件 |
| CreateFile | `90 F1 <fileId> 00 00 03 01 <size> <rk> <wk>` | 创建标准数据文件 |
| DeleteFile | `90 D6 <fileId> 00 00` | 删除文件 |
| GetKeySettings | `90 F3 00 F8 00 00` | 读 AES 密钥数量与当前密钥号 |

**限制说明（界面内已注明）**：未认证（Master Key）时只能读版本与文件列表，
读取文件内容需对应密钥。DESFire EV2 的 KeyManager 密钥管理尚未实现。

### ③ 后台读卡

Android 10 起后台应用无法自动接收标签 Intent，本应用提供完整的解决方案：

1. `BackgroundScanService` 以 `connectedDevice` 类型启动前台服务（保活 + 后台 NFC 能力）
2. 服务内**动态注册**广播接收器监听 `TAG_DISCOVERED` / `NDEF_DISCOVERED` / `TECH_DISCOVERED`
3. 收到标签经 `BackgroundScanBus`（SharedFlow）通知 ViewModel，UI 自动刷新
4. 常驻通知可一键回到应用

**前提**：需在「设置 → 默认应用 → NFC」中允许本应用接收标签事件。

### ④ 标签数据导出

读取页新增「导出数据」面板，两种格式：

| 格式 | 内容 |
|---|---|
| **JSON** | UID / 反向 UID / 卡片类型 / 技术清单（含可读可写标记）/ NDEF 逐记录内容（含细分类型 `kind` 与结构化字段 `details`）/ MIFARE 全部数据块 |
| **纯文本** | 同上但人可读，含 hexdump 与 ASCII 预览 |

经 FileProvider 分享，可存到文件、发给其他应用。

> **敏感字段**：Wi-Fi 密码等字段默认在导出内容中脱敏（星号替换）。
> 如需原样导出，到「设置 → 扫描行为 → 导出敏感字段」开启。

---

## 二·六、中英文切换（2026-10-25 新增）

### 资源布局

| 目录 | 语言 | 说明 |
|---|---|---|
| `res/values/strings.xml` | 简体中文 | **默认**，约 700 条 |
| `res/values-en/strings.xml` | English | 与中文一一对应 |

### 为什么 core 层不用 `stringResource`

Compose 的 `stringResource` 只在 UI 层可用，而 `core/nfc` 的 ops / codec、
`core/export` 的导出报告也要产出用户可见文案。给它们逐个加 `Context` 参数
会污染全部签名，所以引入单例取词器：

```
core/i18n/LocalePref.kt   语言偏好（跟随系统 / zh / en）+ Context 包装
core/i18n/AppStrings.kt   持一个按当前语言包装过的 Context，core 层取词用
```

- `LocalePref` 用 **SharedPreferences 存，不用 DataStore** ——
  `Activity.attachBaseContext` 里要**同步**读语言来包装基 Context，
  DataStore 的读是挂起函数，赶不上那个时机。写入同样用 `commit = true` 同步落盘。
- 切换语言时：`LocalePref.setLanguage()` → `AppStrings.update()` 换 Context →
  `activity.recreate()` 让 Compose 树重建。

### 展示文案一律走资源 id

枚举里原本写死的中文（卡片类型、标签技术、NDEF 细分类型、写入目录标题、
写入分组名、控制台模式、导航目的地、强调色名）全部改成 `@StringRes`：

```kotlin
// 改之前
MIFARE_CLASSIC("MIFARE Classic", "通用储值卡", "分扇区、每扇区独立密钥")
// 改之后
MIFARE_CLASSIC(R.string.card_mifare_classic_name, R.string.card_mifare_classic_cat, R.string.card_mifare_classic_summary)
```

这样切语言时它们是**自动跟随**的，不需要任何额外的刷新逻辑。

### 历史记录的兼容处理

历史记录原本把卡片类型的**中文串**存进了 DataStore，切到英文后旧记录仍是中文。
现在存**稳定的枚举名**（`CardType.MIFARE_CLASSIC.name`），展示时再本地化；
读取时对旧数据做兜底（枚举名匹配不上就原样显示），老记录不会变成空白。

### 分享来源的稳定 key

`SharedContentParser.Parsed` 同时给出两个字段：

- `source` —— 本地化后的**展示名**（"百度地图" / "Baidu Maps"）
- `sourceKey` —— 与语言无关的**稳定标识**（`baidu`）

程序判断与单元测试一律用 `sourceKey`，避免断言被语言环境影响
（JUnit 无 Context 时本地化取词返回空串）。

---

## 二·七、复制标签（2026-10-26 新增）

新增 `ui/pages/ClonePage.kt`（路由 `clone`）+ `core/nfc/TagCloner.kt` +
`core/nfc/ndef/NdefMessageCodec.kt`。

### 流程

| 阶段 | 用户动作 | 应用在做什么 |
|---|---|---|
| `READING_SOURCE` | 贴源标签 | 读 NDEF 消息并序列化成字节快照 |
| `SOURCE_READY` | 看摘要，点「写入目标标签」 | 展示源卡 UID / 卡型 / 技术 / 记录数 / 字节数 |
| `WRITING_TARGET` | 贴空白标签 | 挂起写入任务，标签到场即写 + 回读校验 |

写入成功后**不清空快照**，回到 `SOURCE_READY` 并累加张数，
主按钮文案变成「再复制一张」—— 拿着同一张源卡可以连续复制多张。

### 为什么是「整条消息」而不是「逐记录」

源卡记录可能带 ID、不同的 MB/ME 位置、短记录标志等细节。
按「记录 → 字段」搬运会把这些元信息丢掉或重写，所以直接把整条消息
序列化成字节再写入，目标卡拿到的字节流与源卡一致。

### NDEF 记录布局（自己实现，不用废弃 API）

`NdefMessage.toByteArray()` 已废弃，且 `NdefRecord` 在 JUnit 里不可用，
所以照项目「Codec 纯字节」的约定自己编码：

```
[header 1B]  bit7 MB | bit6 ME | bit5 CF | bit4 SR | bit3 IL | bit2-0 TNF
[type length 1B]
[payload length 1B(SR=1) 或 4B 大端(SR=0)]
[id length 1B]      仅 IL=1 时存在
[type] [id] [payload]
```

不支持 chunked 记录（CF=1），遇到直接报错而不是静默截断。
已知标准记录对照：Text(`TNF=1,type=T`) → `D1 01 03 54 02 7A 68`。

### 入口

不放在底部导航（复制是「有始有终」的任务型页面，混进常驻 tab
会破坏五宫格对称性），而是两处入口：
- **写入页**顶部高亮卡片 `CloneEntryCard`
- **读取页**读完卡片后的「复制此标签」入口

### 边界：哪些能复制，哪些不能

页面上如实列出了限制，避免用户误以为"能复制门禁卡"：

| 项目 | 能否复制 | 原因 |
|---|---|---|
| NDEF 消息内容 | ✅ | 跨卡型通用 |
| UID | ❌ | 绝大多数标签出厂锁定、不可写；可改写 UID 的魔改卡需厂商私有指令且不通用 |
| 密钥 / 密码区 | ❌ | 不属于 NDEF 内容，出于安全考虑故意跳过 |

---

## 二·八、MIFARE Classic 整卡备份 / 恢复（2026-10-27 新增）

新增 `core/nfc/MifareBackup.kt`，入口在读取页扫到 MIFARE Classic 后的
「整卡备份」卡片。

### 流程

| 动作 | 对象 | 走哪条路 |
|---|---|---|
| **备份此卡** | 当前已读取的这张卡 | 用 ViewModel 保存的 `session` 直接读 |
| **恢复到此卡** | **下一张贴上来的卡** | 走 `launchWrite` 挂起写入，弹窗提示靠近标签 |

两个方向不一样，UI 上用文案区分清楚：备份是"现在"，恢复是"下一张"。

### 备份文件格式
JSON（`kotlinx.serialization`），字段：`version / uid / cardType /
sizeBytes / sectorCount / blockCount / createdAt / sectors[]`。
每个扇区记 `authenticated`、`keyType`，每个块记 `isTrailer`、`readable`、`hex`。

### 两条安全边界（勿回退）

1. **不碰 sector trailer** —— 尾块存着 Key A / Key B，备份文件可能被分享出去，
   带出密钥风险太大。读取时脱敏（hex 置空），恢复时跳过。
   代价：恢复后目标卡沿用**自己的**密钥，不继承原卡密钥。
2. **不写块 0** —— 那是 UID，出厂写死。所以恢复出来的卡 UID 仍是目标卡自己的。

这两条合起来决定了它的定位：**数据备份 / 批量复制同型卡**，
而不是"克隆卡片身份"。1K 卡满卡共 64 块，可写回的是 **47** 块
（16 扇区 × 3 数据块 − 块 0）。

### 其他行为
- 目标卡型号必须匹配（`blockCount` / `sectorCount` 一致），否则拒绝 ——
  扇区布局对不上，写出来的数据会错位
- 有扇区认证失败时**不中断**，其余继续读，但结果如实标注「部分备份」
- 恢复时有块写失败 → 整体判失败，并把前 3 条原因带出去，不假装成功
- 密钥可输入（十六进制，留空用默认密钥 `FFFFFFFFFFFF`）
- 恢复前弹二次确认（会覆盖目标卡）

### 与"卡片克隆"的关系
这是被问到「为什么手机钱包能复制门禁卡」之后补的功能。
门禁读头认的是 UID，而 UID 改不了 → **本功能不能用来复制门禁卡**，
这一点在卡片内就写明了，避免误会。

---

## 二·九、NTAG / Ultralight 整卡备份（2026-10-28 新增）

与二·八的 Classic 备份**共用一张卡片**，内部按卡型分流：

| | MIFARE Classic | Ultralight / NTAG |
|---|---|---|
| 结构 | 扇区 × 块，每块 16 字节 | 页，每页 4 字节 |
| 认证 | 需要密钥 | **无扇区概念，不需要密钥** |
| 备份范围 | 数据块（排除 trailer 与块 0） | 用户数据页（第 4 页起） |
| 文件 `kind` | `classic` | `ultralight` |

Ultralight 侧**只备份用户数据页**，其余一律不碰：
- 页 0-1：UID，出厂写死
- 页 2：锁定位，动它等于改写整卡可写性
- 页 3：CC 能力声明，型号不同 CC 不同，写错标签就废了
- 末尾配置区：含 PWD / PACK（密码），与 Classic 的 trailer 同等处理

恢复只要求目标卡的**用户页数不少于**备份（大卡可装小备份）——
页布局是线性的，不像扇区那样存在错位问题。

### 顺带修掉的一个真 bug：NTAG 页数探测算错

原 `detectPageCountFromCc` 里写成 `mlen / 8 / 4` —— **MLEN 的单位就是
「用户区字节数 / 8」**，多除了一次 8：

| 型号 | MLEN | 正确用户页数 | 旧代码算出来 |
|---|---|---|---|
| NTAG213 | 0x12 (18) | 36（总 45） | 0 → 退化成 48 ❌ |
| NTAG215 | 0x3E (62) | 124（总 133） | 1 → 退化成 48 ❌ |
| NTAG216 | 0x6D (109) | 218（总 227） | 3 → 退化成 48 ❌ |

即**所有 NTAG 都被认成普通 Ultralight 48 页**，影响卡型识别、容量显示和块级读取范围。
现在抽成纯函数 `MifareUltralightOps.userPagesFromCc()` 并用单测钉死三种型号的值。

---

## 二·十、MIFARE 密钥本（2026-10-28 新增）

### 为什么需要

MIFARE Classic 整卡备份要用密钥认证**每个扇区**，而很多卡（小区门禁、电梯卡）
的密钥早被改过 —— 只试默认密钥 `FFFFFFFFFFFF` 的话，加了密的扇区会全部跳过，
备份出来的东西是不完整的。

密钥本把用户常用的密钥存下来，备份 / 恢复时**依次尝试**，命中就用。

### 实现

| 文件 | 内容 |
|---|---|
| `data/Repositories.kt` | `MifareKeyStore`：DataStore 持久化 `List<String>`（大写 12 位 hex，上限 20 条） |
| `core/nfc/MifareBackup.kt` | `normalizeKey()`：校验 6 字节并规范化（接受空格 / 冒号 / 大小写） |
| `MainViewModel` | `mifareKeys` StateFlow、`saveMifareKey()` / `removeMifareKey()` / `buildMifareKeyCandidates()` |
| `ReadPage` | 密钥输入框 + 「保存」按钮 + 已保存密钥列表（可删） |

### 尝试顺序（即优先级）

1. 用户**这次临时输入**的 —— 最可能是当前这张卡的
2. **密钥本**里保存的
3. 默认密钥 `FFFFFFFFFFFF`（兜底）

按内容去重，避免重复认证同一个密钥。

### 与其他功能的区别

- 密钥本**只存用户自己录入的密钥**，本应用不提供任何密钥推导 / 破解能力
- 备份文件里**不带密钥**（trailer 脱敏），所以拿到别人的备份文件也读不回原卡 ——
  必须同时拥有卡片和密钥

---

## 二·十一、我的模板 / 收藏夹（2026-10-28 新增）

常用的写入内容（Wi-Fi、名片、链接、文本）存下来，在写入页点一下就自动填好表单。

### 关键设计：复用分享预填那条现成通道

```
模板点击 → vm.applyTemplate(t)
   → _pendingPrefill = Parsed(...)
   → _prefillSeq++ 
   → NfctoolAppRoot 的 LaunchedEffect(prefillSeq) 触发
   → 自动 navigate("write_detail/{entryId}") + 表单预填
```

**UI 层完全不需要处理导航** —— 分享进来的内容走的就是这条路，
模板只是换了数据来源而已。

### 数据模型

`WriteTemplate` 只存**可序列化的轻量字段**（id / name / kind / text / title /
经纬度 / label / 坐标系 / 时间戳）。

⚠️ **不直接存 `SharedContentParser.Parsed`** —— 它含 `ByteArray`（图片）与
`VCardCodec.VCard` 对象，无法序列化。vCard 存原始文本，应用时重新走
`SharedContentParser.parse()` 还原。

### 两个保存入口

| 入口 | 方法 | 说明 |
|---|---|---|
| 写入页（有分享内容时） | `savePrefillAsTemplate()` | 别的应用分享进来 → 存下来 |
| 读取页（扫到卡后） | `saveOverviewAsTemplate()` | 读到的卡 → 存下来 → 以后写到别的卡 |

读取页只取**第一条**记录：多记录标签通常是"主内容 + 辅助记录"，
全存反而让模板难以理解。

### 支持范围

TEXT / URI / GEO / VCARD。**不支持 IMAGE**（要存字节，模型里没有，
会明确报错而不是静默失败）。

名称可留空 —— 留空时自动取内容摘要（标题或文本前 24 字）。

---

## 三、界面设计

深空底色（`#080B14`）+ 霓虹青 `#00E5FF` / 品红 `#FF2E93` 双主色体系：

- **玻璃拟态卡片**：半透明渐变 + 1px 细边框 + 顶部高光反光
- **雷达扫描动画**：三层同心波纹扩散 + 中心发射点
- **悬浮玻璃导航栏**：5 个目的地，选中项带霓光圆点
- **等宽字体**展示十六进制 / UID / APDU，保证数据对齐
- **顶部环境光晕**：随强调色变化的氛围渐变
- 危险操作（格式化 / 恢复出厂）统一品红警示 + 二次确认对话框

---

## 四、构建过程中修复的真实问题

### 环境层
1. **SDK 目录全是空壳** — platform / build-tools / platform-tools 均无实体文件。
   手工从 `dl.google.com` 下载安装 platform-36、platform-37.0、build-tools 36.0.0 & 37.0.0、platform-tools r37.0.1。
2. **全局 Gradle 代理 127.0.0.1:7890 已失效**（Connection refused），导致全部依赖解析失败。
   本机实际可用代理为 `12074`，且直连 Maven / Google 仓库正常。
   已在项目级 `gradle.properties` 用 `systemProp.http.nonProxyHosts=*` 绕开，未改动用户的全局配置。
3. **platform-37.0 缺 `package.xml`** — 该文件不在官方 zip 内，导致
   `Failed to find Platform SDK with path: platforms;android-37.0`。
   依据 `repository2-3.xml` 元数据手写补齐（api-level 37.0 / extension-level 22）。

### 构建配置层
4. **AGP 9.0+ 内置 Kotlin** — 再应用 `org.jetbrains.kotlin.android` 插件会直接报错。
5. **AGP 内置 KGP 为 2.2.10**，而 Compose BOM / kotlinx-serialization 已按 Kotlin 2.4 metadata 编译，
   报「compiled with an incompatible version of Kotlin」。在根 `build.gradle.kts` 的
   `buildscript.classpath` 显式提升到 KGP 2.4.21。
6. **Compose / Serialization 插件 marker 解析异常** — 改走 `buildscript.classpath` 直接引用构件。
7. **Gradle 9 守护进程 JVM 被锁在 JDK 25**（第二阶段最难查的坑）——
   `gradle/gradle-daemon-jvm.properties` 中 `toolchainVersion=25` 会强制要求 JDK 25，
   触发 foojay 联网下载 Temurin 25（本机网络下必然失败），构建直接中断。
   该文件由 Gradle 自动生成，独立于 `gradle.properties`，极易被忽略。
   排查时曾误判为 AGP 版本、KGP 版本、`jvmToolchain`、`auto-download` 所致。
   **已改为 `toolchainVersion=17`**，并在项目级 `gradle.properties` 固定 `org.gradle.java.home`。

### 代码层（均用 `javap` 读 android.jar 逐条核实，未凭记忆写）
7. `MifareClassic` **没有** `isBlockTrailer()` / `KEY_A` / `KEY_B` — trailer 位置按扇区自行计算。
8. `MifareClassic.restore(int)` 收的是**扇区号**，不是密钥。
9. `MifareUltralight` **只有** `readPages(int)`，没有 `readPage`；单页读需自行截取。
10. `IsoDep` **没有** `getProtocol()` — 改由历史字节（ATR）推断 T=CL / ISO7816。
11. `NdefRecord` **没有** `TNF_RESERVED`；`createTextRecord` 只出 UTF-16，中文必须手工构造 payload。
12. `NdefFormatable.formatReadOnly(message)` **必须传消息**。
13. `android.nfc.SmartPoster` 是隐藏 API — Smart Poster 标题改为手工解析 Action 记录。
14. `NfcAdapter` 各能力方法的 API 级别分别是 29 / 34 / 35 / 36，
    `setDiscoveryTechnology` 需 **35** 而非 34。
15. `Icons.Rounded.Console` 不存在（改 `Terminal`）；基础图标在 material-icons-core。

### 逻辑缺陷
16. **Reader Mode 最初从未启用** — 已接入 Activity 生命周期（onStart/onResume 启用，onPause/onStop 禁用）。
    关键：**读卡成功后不能立刻关闭 reader mode**，否则系统默认标签应用会被拉起并把本应用挤到后台。
17. **重复回调** — 标签持续在场时 reader mode 会反复触发，加 1500ms 同 UID 防抖。
18. **21 处 Lint API 级别错误** — 全部补版本守卫，否则在 Android 7 / 8 设备上直接 `NoSuchMethodError` 崩溃。
19. 去掉固定竖屏（Android 16 起该属性被忽略）、补 monochrome 图标层。
20. **`enableReaderMode` 宿主必须是 Activity** — Service 无法使用，
    后台读卡改走「connectedDevice 前台服务 + 动态注册广播接收器」。
    静态注册的 receiver 在后台被唤起时 Android 不允许它启动 Activity，故不采用。
21. **`ContextCompat.registerReceiver` 返回 Intent 而非 receiver** — receiver 需自行持有才能反注册。
22. **`HostApduService.onBind` 是 final** — 不可 override。
23. **`ApduServiceInfo` / `ApduService` 是隐藏 API** — 第三方无法构造，
    HCE 的 AID 只能通过 `apdu-service.xml` 声明式注册。
24. **动态接收器必须显式声明导出行为** — Android 14+ 否则抛 SecurityException，
    用 `ContextCompat.RECEIVER_EXPORTED`。

---

## 五、验证状态

| 项 | 状态 |
|---|---|
| `assembleDebug` 干净构建 | ✅ 通过 |
| `lintDebug` | ✅ 0 error |
| APK 清单 / 权限 / intent-filter | ✅ 已核验 |
| HCE 服务与 AID 注册 | ✅ 已核验（service + meta-data + BIND_NFC_SERVICE） |
| 前台服务类型 | ✅ 已核验（`foregroundServiceType=0x10` = connectedDevice） |
| FileProvider 配置 | ✅ 已核验 |
| DEX 与资源表完整性 | ✅ 已核验 |
| 代码规模 | 57 个 Kotlin 文件 / 16296 行 |
| 真机 NFC 读写 | ⏳ 待验证（需实体标签） |

**测试**：222 个 JUnit 单测全通过（含 Text 状态字节 / APDU 61xx 与 hasLe /
Smart Poster 规范 / NDEF 消息层往返与截断）。

**说明**：代码为功能实现参考，已通过编译与静态检查，但未经真机实测。
合入生产前请用实体 NFC 标签在真机上跑一遍读写流程。

### 界面与返回手势（2026-10-26 修复）
- **问题**：返回时整个页面缩小变半透明。真因是 **Android 15+ 预测式返回** ——
  targetSdk ≥ 35 时 `enableOnBackInvokedCallback` 默认 true，
  应用没注册任何 `OnBackPressedCallback` → 系统把每次返回都当成「回桌面」，
  播放 back-to-home 缩放淡出动画。附带后果：详情页按返回键会**直接退出应用**。
- **修复**：manifest 显式 `android:enableOnBackInvokedCallback="false"`
  + `NfctoolAppRoot` 注册 `BackHandler`
  （`enabled = navController.previousBackStackEntry != null`，根页面不拦截）。
- 导航转场保持「详情页右滑进 / 右滑出」的推入手感，与 tab 切换的淡入淡出区分开。

### 尚未实现（可作为后续扩展）
- **DESFire EV2 密钥管理** — KeyManager 相关指令未封装，当前只有版本 / 文件列表 / 文件读写
- **标签导入** — 导出的 JSON 目前只能查看，无法反写回标签
- **HCE 可配置卡片** — 当前响应数据写死在 `HceProfile`，未提供 UI 编辑
- **后台标签的历史去重** — 后台扫描的事件未走防抖逻辑

---

## 六、还能加什么功能（候选清单，2026-10-28 更新）

按「价值 ÷ 成本」排序。MIFARE Classic 整卡备份已落地（见二·八），故从候选中移除。

### 先做（高价值 + 低成本）

| # | 功能 | 说明 |
|---|---|---|
| 1 | **NTAG / Ultralight 整卡备份** | 与刚做的 Classic 备份对称补齐。NTAG21x 比 Classic 更常见，而 `MifareUltralightOps`（`readPages` 分批读）和备份的 JSON 基建都已就绪，主要是复用 UI |
| 2 | **MIFARE 密钥本** | 做 Classic 备份时暴露的痛点：**需要密钥**。加一个密钥列表（保存常用密钥、自动逐个尝试），直接提升备份成功率。成本很低 |

### 规划（高价值 + 中等成本）

| # | 功能 | 说明 |
|---|---|---|
| 3 | **我的模板 / 收藏夹** | 常用写入内容（Wi-Fi、名片、链接）存下来一键再写。JSON 序列化基建现成，复制页的快照本身就是天然的模板数据源 |
| 4 | **HEX 页面编辑器** | 按页浏览 + 改单字节。控制台已有原始指令通道，缺这层 UI |
| 5 | **标签锁定 / 密码管理** | `Ndef.makeReadOnly()` 已接在复制页；可独立成页并补 NTAG 的 PWD/PACK 设置与清除 |
| 6 | **NDEF 记录编辑器** | 手工增删记录、调序、改单条内容。`NdefMessageCodec` 的纯字节编解码已就绪，缺的是把 `NdefRecordInfo` 反解成可编辑表单（成本最高的一项）|

### 顺手做（低价值 + 低成本）

| # | 功能 | 说明 |
|---|---|---|
| 7 | **标签容量体检** | 扫描全卡，报告可用容量、坏块、锁定位 |
| 8 | **历史记录搜索 / 批量导出** | 现在只能一张张导出，加按卡型 / UID / 时间筛选 |
| 9 | **批量连续写入** | 复制页已有「再复制一张」，升级为设定张数自动连写 |
| 10 | **NDEF → 二维码分享** | 项目已自带 QR 编码器，把标签内容转成码给别人扫 |

> 涉及 MIFARE Classic 密钥破解 / 绕过门禁的功能**不在清单内** ——
> 只对自己拥有的卡片做读写与调试。密钥本只用于**自己**的卡片。

