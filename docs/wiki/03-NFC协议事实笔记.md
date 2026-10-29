# NFC 协议事实笔记

全部经 `javap` 读 android.jar 或对照规范核对，**不要凭记忆修改**。

## Android API 事实
- `NdefRecord` **无**中文 Text 支持（`createTextRecord` 只出 UTF-16）→ 手工构造 payload，
  状态字节 `bit7 = UTF16 标志`、`bit0-5 = 语言码长度`（硬编码 0 会让记录不合法）
- `NdefRecord` **无** `TNF_RESERVED`、**无** `TNF_AID`、**无** `createGeoUri`
- `MifareClassic` 无 `isBlockTrailer()`、无 `KEY_A`/`KEY_B` 常量（只有 `KEY_DEFAULT`）
- `MifareUltralight` 只有 `readPages(int)`（无 readPage），一次最多 4 页
- `IsoDep` **无** `getProtocol()`
- `android.nfc.SmartPoster` 是隐藏 API，第三方不可用
- `ApduServiceInfo` / `ApduService` 是隐藏 API
- `HostApduService.onBind` 是 final，不可 override
- `ContextCompat.registerReceiver` 返回的是最后一次注册的 Intent，不是 receiver

## NDEF 消息层布局
```
[header 1B]  bit7 MB | bit6 ME | bit5 CF | bit4 SR | bit3 IL | bit2-0 TNF
[type length 1B]
[payload length 1B(SR=1) 或 4B 大端(SR=0)]
[id length 1B]       仅 IL=1 时存在
[type] [id] [payload]
```
SR 阈值 255 字节；本项目不支持 chunked（CF=1 直接报错）。

## 各类型要点
| 类型 | 要点 |
|---|---|
| Smart Poster | type 是 **`Sp`**（不是旧版 `S`），payload 是**嵌套 NDEF 消息** |
| Wi-Fi 配网 | MIME `application/vnd.wfa.wsc`，TLV 16 位 type+len（大端），SSID=0x1045 / KEY=0x1027 / AUTH=0x1003 |
| AAR | TNF_EXTERNAL + type `android.com:pkg` + payload 包名 |
| AID | 公开 API 无 `TNF_AID`，只能用 `TNF_ABSOLUTE_URI` + payload 放 AID 字节，靠 payload 判二进制 |
| 地理位置 | RFC 5870 `geo:lat,lon` 走 `createUri`，系统原生支持 |
| 蓝牙 OOB | `vnd.bluetooth.ep.oob`（BR/EDR，2 字节长度头 + BD_ADDR）/ `.le.oob`（纯 AD 序列）。**Android 系统不解析**，只派发 MIME |
| APDU | `61xx` 续传要**累加**数据段再发 GET RESPONSE；`6Cxx` 仅当命令有 Le 才改末字节 |

## Wi-Fi 配网的 AOSP 判定
系统校验：SSID 必须存在；非 Open 认证时密码必须存在，否则整条记录判无效、不触发连接。
认证类型：Open=0x0001 / WPA-PSK=0x0002 / WPA2-PSK=0x0020 / 混合=0x0022。

## 已知平台限制
- 蓝牙 OOB 记录 Android 系统**不自动解析**，只当普通 MIME 派发，UI 必须如实提示
- iOS 对 Wi-Fi 配网不自动联网，只在通知栏提示，需手动确认
- Android Beam 已在 Android 10 废弃、14 移除
- UID 绝大多数标签出厂锁定、不可写；可改写 UID 的魔改卡需厂商私有指令且不通用
