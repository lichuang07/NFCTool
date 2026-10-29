"""
把 F 方案（NMark）的 SVG 转成 Android VectorDrawable。

为什么不用现成工具：
- 没有网络依赖，且要精确控制 VectorDrawable 的渐变与描边写法。
- SVG 的 `transform` 需要展开成 VectorDrawable 的 `group` 变换。

产出：
  drawable/ic_launcher_background.xml   渐变背景（自适应图标背景层）
  drawable/ic_launcher_foreground.xml   N 标志（自适应图标前景层）
  drawable/ic_launcher_square.xml       圆角方形传统图标
  drawable/ic_launcher_round.xml        圆形传统图标
  drawable/ic_splash_logo.xml           启动图用的 N 标志
"""
import re
import os
import sys

SRC_DIR = sys.argv[1]
RES = sys.argv[2]

FG = "icon-F-nmark-android_fg.svg"
BG = "icon-F-nmark-android_bg.svg"
SQ = "icon-F-nmark-android_square.svg"
RD = "icon-F-nmark-android_round.svg"


def read(name):
    with open(os.path.join(SRC_DIR, name), encoding="utf-8") as f:
        return f.read()


def parse_transform(text):
    """把 transform="translate(x y) scale(s)" 解析成 (tx, ty, scale)。"""
    tx = ty = 0.0
    scale = 1.0
    m = re.search(r"translate\(\s*([-\d.]+)[,\s]+([-\d.]+)\s*\)", text)
    if m:
        tx, ty = float(m.group(1)), float(m.group(2))
    m = re.search(r"scale\(\s*([-\d.]+)\s*\)", text)
    if m:
        scale = float(m.group(1))
    return tx, ty, scale


def group_attrs(text):
    """提取 <g transform=...> 里的变换。"""
    m = re.search(r"<g[^>]*transform=\"([^\"]+)\"", text)
    if not m:
        return 0.0, 0.0, 1.0
    return parse_transform(m.group(1))


def rect_to_vd(rect_str, indent="        "):
    """<rect x y width height rx fill> -> <path> 元素。"""
    def g(name, default=0.0):
        m = re.search(rf"{name}=\"([-\d.]+)\"", rect_str)
        return float(m.group(1)) if m else default

    x, y = g("x"), g("y")
    w, h = g("width"), g("height")
    rx = g("rx", 0.0)
    fill = re.search(r"fill=\"([^\"]+)\"", rect_str)
    fill = fill.group(1) if fill else "#FFFFFF"

    if rx > 0:
        # 圆角矩形用 path 描述（M = 左上, A 圆弧）
        d = (
            f"M{x + rx},{y} "
            f"h{w - 2 * rx} "
            f"a{rx},{rx} 0 0 1 {rx},{rx} "
            f"v{h - 2 * rx} "
            f"a{rx},{rx} 0 0 1 -{rx},{rx} "
            f"h-{w - 2 * rx} "
            f"a{rx},{rx} 0 0 1 -{rx},-{rx} "
            f"v-{h - 2 * rx} "
            f"a{rx},{rx} 0 0 1 {rx},-{rx} Z"
        )
    else:
        d = f"M{x},{y} h{w} v{h} h-{w} Z"

    return (
        f'{indent}<path\n'
        f'{indent}    android:fillColor="{fill}"\n'
        f'{indent}    android:pathData="{d}" />'
    )


def stroke_path_to_vd(path_d, stroke, width, indent="        "):
    """带描边的 path -> <path> + stroke 属性。"""
    return (
        f'{indent}<path\n'
        f'{indent}    android:pathData="{path_d}"\n'
        f'{indent}    android:strokeColor="{stroke}"\n'
        f'{indent}    android:strokeWidth="{width}"\n'
        f'{indent}    android:strokeLineCap="round" />'
    )


def build_foreground(name="ic_launcher_foreground", scale_boost=1.0):
    """
    生成前景 VectorDrawable。

    scale_boost：放大系数（>1 更大更醒目）。
    自适应图标前景的安全区是中心 66dp / 108dp，
    N 标志只占 47.7% 宽时桌面偏小，可适当放大。
    """
    svg = read(FG)
    tx, ty, scale = group_attrs(svg)
    scale *= scale_boost

    body = []
    # 两个竖条
    for rect_str in re.findall(r"<rect[^>]*/>", svg):
        body.append(rect_to_vd(rect_str))
    # 波浪斜杠
    m = re.search(r'<path d="([^"]+)"[^>]*stroke="([^"]+)"[^>]*stroke-width="([^"]+)"', svg)
    if m:
        body.append(stroke_path_to_vd(m.group(1), m.group(2), m.group(3)))

    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024">
    <group
        android:translateX="{tx:.4f}"
        android:translateY="{ty:.4f}"
        android:scaleX="{scale:.6f}"
        android:scaleY="{scale:.6f}"
        android:pivotX="0"
        android:pivotY="0">
{chr(10).join(body)}
    </group>
</vector>
"""


def build_background():
    """
    背景层：引用 PNG 渐变图，**不用 aapt:attr 渐变**。

    ⚠️ 原因：`aapt:attr name="android:fillColor"` + `<gradient>` 虽是官方标准写法，
    但 aapt2 会把它提取成独立的内部资源文件：
        res/drawable/$ic_launcher_background__0.xml
    这类 `$` 前缀的内部资源在部分国产 ROM 的安装器上解析会失败，
    表现为安装报错 "packageinfo is null"（APK 本身其实是完好的）。

    背景是纯渐变，用 PNG 无损，体积也很小（432×432 约 4KB）。

    ⚠️ 用 `layer-list` 而不是 `inset`：inset 会把内容向内缩，
    不适合做铺满整个画布的背景。
    """
    return """<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_launcher_bg_gradient" />
</layer-list>
"""


def build_legacy(name, shape):
    """
    传统图标（方形/圆形）：裁剪形状 + N 标志。

    ⚠️ 背景不用 aapt:attr 渐变（会产生 `$` 前缀内部资源，
    部分国产 ROM 的安装器解析会失败并报 "packageinfo is null"）。
    改为：裁剪形状内填**纯色**（与渐变中段 #6366F1 同色），
    视觉上与自适应图标接近，且完全不产生内部资源。
    """
    svg = read(SQ if shape == "square" else RD)
    tx, ty, scale = group_attrs(svg)

    body = []
    for rect_str in re.findall(r"<rect[^>]*/>", svg):
        # 背景那块大 rect 单独处理
        if 'width="1024"' in rect_str:
            continue
        body.append(rect_to_vd(rect_str))
    m = re.search(r'<path d="([^"]+)"[^>]*stroke="([^"]+)"[^>]*stroke-width="([^"]+)"', svg)
    if m:
        body.append(stroke_path_to_vd(m.group(1), m.group(2), m.group(3)))

    if shape == "square":
        clip = "M220,0h584a220,220 0 0 1 220,220v584a220,220 0 0 1 -220,220h-584a220,220 0 0 1 -220,-220v-584a220,220 0 0 1 220,-220z"
    else:
        clip = "M512,0a512,512 0 1,0 0.1,0z"

    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024">
    <path
        android:fillColor="#6366F1"
        android:pathData="{clip}" />
    <group
        android:translateX="{tx:.4f}"
        android:translateY="{ty:.4f}"
        android:scaleX="{scale:.6f}"
        android:scaleY="{scale:.6f}"
        android:pivotX="0"
        android:pivotY="0">
{chr(10).join(body)}
    </group>
</vector>
"""


def build_splash(scale_boost=0.62):
    """
    启动图：纯白色 N 标志（放在深色底上）。

    尺寸说明：Android 12+ 的 splash 屏只显示图标画布的**居中 2/3**
    （108dp 画布里取中间 72dp），所以要让 N 标志在启动时看起来大小合适，
    这里的 scale 必须小于自适应图标前景的 scale。
    """
    return build_foreground("ic_splash_logo", scale_boost=scale_boost)


def write(path, content):
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  {path}")


if __name__ == "__main__":
    print("生成 VectorDrawable：")
    write("drawable/ic_launcher_background.xml", build_background())
    write("drawable/ic_launcher_foreground.xml", build_foreground())
    write("drawable/ic_launcher_square.xml", build_legacy("square", "square"))
    write("drawable/ic_launcher_round.xml", build_legacy("round", "round"))
    write("drawable/ic_splash_logo.xml", build_splash())
    print("完成")
