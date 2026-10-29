#!/bin/bash
# 构建并收集可交付的 APK 到 dist/
#
# 用法：bash tools/build_release.sh
#
# 产物：
#   dist/NFC工具箱-<大小>-主推.apk        R8 瘦身版（约 4-5 MB）
#   dist/NFC工具箱-<大小>-不混淆对照.apk   仅裁 ABI 的对照版（约 20 MB）
#
# 为什么要有 dist/ 这个目录：
#   交付 APK 时直接用中文名标注版本与用途，避免拿错包。
#   体积大的包（>10MB）经传输容易被截断损坏，
#   瘦身到 5MB 以内可显著降低安装失败率。

set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-C:/Users/lichuang/.jdks/jbr-17.0.14}"
BT="${BT:-$HOME/AppData/Local/Android/Sdk/build-tools/37.0.0}"

# Windows 上 apksigner 是 .bat，Git Bash 下无法直接执行，
# 改为直接用 java -cp 调 apksigner.jar（更稳，不受引号转义影响）。
run_apksigner() {
    local jar="$BT/lib/apksigner.jar"
    if [ -f "$jar" ]; then
        "$JAVA_HOME/bin/java.exe" -cp "$jar" com.android.apksigner.ApkSignerTool "$@"
    elif [ -f "$BT/apksigner" ]; then
        "$BT/apksigner" "$@"
    else
        echo "    ⚠️ 找不到 apksigner，跳过签名校验"
        return 1
    fi
}

echo "==> 构建"
./gradlew clean assembleDebug assembleNoShrink lintDebug testDebugUnitTest --no-configuration-cache

echo
echo "==> 校验"
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/noShrink/app-noShrink.apk; do
    echo "--- $apk"
    if run_apksigner verify "$apk"; then
        echo "    签名 ✅"
    fi
    "$BT/zipalign" -c 4 "$apk" && echo "    对齐 ✅"
    unzip -t "$apk" > /dev/null 2>&1 && echo "    完整性 ✅"
    if unzip -l "$apk" | grep -qE '\$'; then
        echo "    ❌ 存在 \$ 内部资源（部分国产 ROM 安装器会失败）"
        exit 1
    else
        echo "    无 \$ 内部资源 ✅"
    fi
done

echo
echo "==> 收集到 dist/"
mkdir -p dist
rm -f dist/*.apk

size_of() {
    local bytes
    bytes=$(stat -c %s "$1")
    awk -v b="$bytes" 'BEGIN {printf "%.1fMB", b/1048576}'
}

main_apk="app/build/outputs/apk/debug/app-debug.apk"
alt_apk="app/build/outputs/apk/noShrink/app-noShrink.apk"

cp "$main_apk" "dist/NFC工具箱-$(size_of "$main_apk")-主推.apk"
cp "$alt_apk"  "dist/NFC工具箱-$(size_of "$alt_apk")-不混淆对照.apk"

ls -la dist/

echo
echo "✅ 完成。安装前建议先卸载旧版：adb uninstall com.nfctool.app"
