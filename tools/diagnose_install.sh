#!/bin/bash
# APK 安装诊断脚本
#
# 用法：
#   1. 手机开启「开发者选项 → USB 调试」
#   2. USB 连接电脑
#   3. 运行本脚本（需要 adb 在 PATH，或设置 ADB 环境变量）
#
# 目的：拿到**真实的**安装错误码 —— GUI 安装器只显示 "packageInfo is null"，
# 真实原因藏在 adb 的输出里。

set -u

ADB="${ADB:-~/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
if [ ! -f "$ADB" ]; then
    ADB="adb"
fi

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="com.nfctool.app"

echo "=============================================="
echo " APK 安装诊断"
echo "=============================================="
echo "APK: $APK"

if [ ! -f "$APK" ]; then
    echo "❌ 找不到 APK 文件"
    exit 1
fi

echo
echo "---- 1. 设备连接状态 ----"
"$ADB" devices -l 2>&1 | head -5

DEVICE_COUNT=$("$ADB" devices 2>/dev/null | grep -c "device$")
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo
    echo "⚠️  没有检测到设备。"
    echo "   请：设置 → 关于手机 → 连点版本号开启开发者选项 → 开启 USB 调试"
    echo "   然后用数据线连电脑，并在手机上确认「允许 USB 调试」。"
    echo
    echo "---- 无设备时的离线自检 ----"
    echo "（以下检查不需要设备，能排除 APK 自身的问题）"
    APK_SELF_CHECK=1
fi

if [ -z "${APK_SELF_CHECK:-}" ]; then
    echo
    echo "---- 2. 设备信息 ----"
    "$ADB" shell getprop ro.build.version.release 2>&1 | sed 's/^/  Android 版本: /'
    "$ADB" shell getprop ro.build.version.sdk 2>&1 | sed 's/^/  API 级别:    /'
    "$ADB" shell getprop ro.product.manufacturer 2>&1 | sed 's/^/  厂商:       /'
    "$ADB" shell getprop ro.product.model 2>&1 | sed 's/^/  型号:       /'
    "$ADB" shell getprop ro.build.version.security_patch 2>&1 | sed 's/^/  安全补丁:   /'

    echo
    echo "---- 3. 已安装状态（是否已存在旧版）----"
    "$ADB" shell pm list packages 2>&1 | grep "$PKG" || echo "  未安装（首次安装）"
    "$ADB" shell dumpsys package "$PKG" 2>&1 | grep -E "versionCode|signatures|firstInstallTime|lastUpdateTime" | head -6

    echo
    echo "---- 4. 尝试安装（真实错误码在这里）----"
    echo "  正在执行 adb install -r ...（大文件可能需要几十秒）"
    "$ADB" uninstall "$PKG" 2>&1 | sed 's/^/  卸载旧版: /'
    "$ADB" install -r "$APK" 2>&1 | sed 's/^/  /'

    echo
    echo "---- 5. 安装后状态 ----"
    "$ADB" shell pm list packages 2>&1 | grep "$PKG" || echo "  仍未安装"
    "$ADB" shell dumpsys package "$PKG" 2>&1 | grep -E "versionCode|codePath" | head -4

    echo
    echo "---- 6. 相关系统日志 ----"
    "$ADB" logcat -d -t 300 2>&1 | grep -iE "PackageManager|PackageParser|packageinstaller|PackageInstaller" | tail -25

    echo
    echo "=============================================="
    echo " 如果安装成功，请再运行一次应用看是否崩溃："
    echo "   $ADB logcat -c && $ADB shell am start -n $PKG/.MainActivity"
    echo "   然后 $ADB logcat -d | grep -iE 'AndroidRuntime|$PKG'"
    echo "=============================================="
else
    # 离线自检
    BT="${BT:-~/AppData/Local/Android/Sdk/build-tools/37.0.0}"
    echo
    echo "  文件大小: $(stat -c %s "$APK" 2>/dev/null || echo '?') 字节"
    echo
    echo "  ZIP 完整性:"
    unzip -t "$APK" 2>&1 | tail -1 | sed 's/^/    /'
    echo
    echo "  zipalign:"
    "$BT/zipalign" -c 4 "$APK" 2>&1 | tail -1 | sed 's/^/    /' && echo "    ✅ 对齐正常"
    echo
    echo "  签名:"
    JAVA_HOME="${JAVA_HOME:-C:/Users/lichuang/.jdks/jbr-17.0.14}" \
        "$BT/apksigner" verify --verbose "$APK" 2>&1 | head -6 | sed 's/^/    /'
    echo
    echo "  aapt 解析:"
    "$BT/aapt2" dump badging "$APK" 2>&1 | head -2 | sed 's/^/    /'
    echo
    echo "  含 \$ 内部资源的文件（应为空）:"
    unzip -l "$APK" 2>&1 | grep -E '\$' | sed 's/^/    /' || echo "    ✅ 无"
fi
