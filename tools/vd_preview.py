"""
把 Android VectorDrawable 转成 SVG，用 headless Chrome 渲染成 PNG 以验证视觉效果。

为什么要这层验证：
- 手写 pathData 容易出错（圆角矩形的弧线方向、描边路径的坐标）。
- 光看 XML 没法确认渲染结果，必须实际画出来比对。
"""
import re
import sys
import os
import subprocess
import json

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"


def vd_to_svg(vd_path, out_svg, size=512, bg="#080B14"):
    with open(vd_path, encoding="utf-8") as f:
        text = f.read()

    # viewport
    m = re.search(r'android:viewportWidth="([\d.]+)"', text)
    vw = float(m.group(1)) if m else 1024
    m = re.search(r'android:viewportHeight="([\d.]+)"', text)
    vh = float(m.group(1)) if m else 1024

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
           f'viewBox="0 0 {vw} {vh}">']
    out.append(f'<rect x="0" y="0" width="{vw}" height="{vh}" fill="{bg}"/>')

    # aapt:attr 渐变 -> SVG linearGradient
    grad_id = 0
    for am in re.finditer(r"<aapt:attr name=\"android:fillColor\">(.*?)</aapt:attr>",
                          text, re.S):
        inner = am.group(1)
        gm = re.search(r"<gradient([^>]*)>(.*?)</gradient>", inner, re.S)
        if not gm:
            continue
        gattrs, ginner = gm.group(1), gm.group(2)
        stops = []
        for sm in re.finditer(r"<item\s+android:offset=\"([\d.]+)\"\s+android:color=\"([^\"]+)\"", ginner):
            stops.append((sm.group(1), sm.group(2)))
        if not stops:
            continue
        gid = f"grad{grad_id}"
        grad_id += 1
        sx = re.search(r'android:startX="([-\d.]+)"', gattrs)
        sy = re.search(r'android:startY="([-\d.]+)"', gattrs)
        ex = re.search(r'android:endX="([-\d.]+)"', gattrs)
        ey = re.search(r'android:endY="([-\d.]+)"', gattrs)
        x1 = sx.group(1) if sx else 0
        y1 = sy.group(1) if sy else 0
        x2 = ex.group(1) if ex else vw
        y2 = ey.group(1) if ey else 0
        st = "".join(
            f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops
        )
        out.append(
            f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
            f'x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>'
        )
        # 记录填充引用
        text = text.replace(am.group(0), f'__GRADREF__{gid}__')

    def emit_path(attrs):
        """把一个 <path> 的 android:* 属性转成 SVG 属性。"""
        d = re.search(r'android:pathData="([^"]*)"', attrs)
        if not d:
            return None
        fill = re.search(r'android:fillColor="([^"]+)"', attrs)
        stroke = re.search(r'android:strokeColor="([^"]+)"', attrs)
        sw = re.search(r'android:strokeWidth="([-\d.]+)"', attrs)

        parts = [f'd="{d.group(1)}"']
        # 有 fill 用 fill，没 fill 但有 stroke 则是纯描边
        if fill:
            parts.append(f'fill="{fill.group(1)}"')
        else:
            parts.append('fill="none"')
        if stroke:
            parts.append(f'stroke="{stroke.group(1)}"')
            parts.append(f'stroke-width="{sw.group(1) if sw else 1}"')
            parts.append('stroke-linecap="round"')
            parts.append('stroke-linejoin="round"')
        return f'<path {" ".join(parts)}/>'

    def emit_path_with_grad(attrs):
        """处理带 aapt:attr 渐变的 path。"""
        d = re.search(r'android:pathData="([^"]*)"', attrs)
        if not d:
            return None
        gm = re.search(r'__GRADREF__(\w+)__', attrs)
        if gm:
            return f'<path d="{d.group(1)}" fill="url(#{gm.group(1)})"/>'
        return emit_path(attrs)

    # 找出所有 group 与其内部 path，以及 group 外的 path
    consumed = set()
    # 按文档顺序输出，保证绘制层级（背景在前景之下）
    # 逐个匹配 <group>...</group> 与顶层的 <path>，用位置排序
    items = []

    for gm in re.finditer(r"<group\b([^>]*)>(.*?)</group>", text, re.S):
        attrs, inner = gm.group(1), gm.group(2)

        tx = re.search(r'android:translateX="([-\d.]+)"', attrs)
        ty = re.search(r'android:translateY="([-\d.]+)"', attrs)
        sx = re.search(r'android:scaleX="([-\d.]+)"', attrs)
        px = re.search(r'android:pivotX="([-\d.]+)"', attrs)
        py = re.search(r'android:pivotY="([-\d.]+)"', attrs)

        txv = float(tx.group(1)) if tx else 0
        tyv = float(ty.group(1)) if ty else 0
        sxv = float(sx.group(1)) if sx else 1
        pxv = float(px.group(1)) if px else 0
        pyv = float(py.group(1)) if py else 0

        # VectorDrawable: translate -> scale(绕 pivot) -> translate(-pivot)
        # SVG transform 列表等价于从右往左应用，矩阵乘积一致
        buf = [f'<g transform="translate({txv},{tyv}) translate({pxv},{pyv}) '
               f'scale({sxv}) translate({-pxv},{-pyv})">']
        for pm in re.finditer(r"<path\b([^>]*?)/>", inner, re.S):
            svg_path = emit_path(pm.group(1))
            if svg_path:
                buf.append(svg_path)
        buf.append('</g>')
        items.append((gm.start(), "\n".join(buf)))

    # 顶层 path（背景渐变底等）—— 含 <aapt:attr> 渐变的写法是
    # <path ...><aapt:attr>...</aapt:attr></path>，不是自闭合
    for pm in re.finditer(r"<path\b(.*?)(?:/>|>(.*?)</path>)", text, re.S):
        attrs = pm.group(1)
        body = pm.group(2) or ""
        if any(gm.start() < pm.start() < gm.end() for gm in
               re.finditer(r"<group\b([^>]*)>(.*?)</group>", text, re.S)):
            continue
        # 渐变标记替换后落在 path 的 body 部分
        gm = re.search(r'__GRADREF__(\w+)__', body)
        d = re.search(r'android:pathData="([^"]*)"', attrs)
        if gm and d:
            items.append((pm.start(), f'<path d="{d.group(1)}" fill="url(#{gm.group(1)})"/>'))
        else:
            svg_path = emit_path(attrs)
            if svg_path:
                items.append((pm.start(), svg_path))

    for _, s in sorted(items, key=lambda x: x[0]):
        out.append(s)

    out.append('</svg>')
    with open(out_svg, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


def render(svg_path, png_path, size=512):
    with open(svg_path, encoding="utf-8") as f:
        svg = f.read()
    html = f"""<!DOCTYPE html><html><head><meta charset="utf-8">
<style>html,body{{margin:0;padding:0;background:#080B14;width:{size}px;height:{size}px}}
svg{{display:block}}</style></head><body>{svg}</body></html>"""
    tmp_html = svg_path.replace(".svg", ".html")
    with open(tmp_html, "w", encoding="utf-8") as f:
        f.write(html)

    # Chrome 需要绝对路径的 file:// URL，相对路径会被当成网络地址
    abs_html = os.path.abspath(tmp_html).replace("\\", "/")
    abs_png = os.path.abspath(png_path)

    subprocess.run([
        CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
        "--no-sandbox",
        f"--screenshot={abs_png}",
        f"--window-size={size},{size}",
        f"file:///{abs_html}",
    ], check=True, capture_output=True)
    return png_path


if __name__ == "__main__":
    vd = sys.argv[1]
    svg = sys.argv[2]
    png = sys.argv[3]
    vd_to_svg(vd, svg)
    render(svg, png)
    print(f"渲染完成: {png}")
