"""
生成自适应图标的渐变背景 PNG。

## 为什么不用 VectorDrawable 的 aapt:attr 渐变

`aapt:attr name="android:fillColor"` + `<gradient>` 是官方标准写法，
但 aapt2 会把它**提取成独立的内部资源文件**：
    res/drawable/$ic_launcher_background__0.xml
    res/mipmap/$ic_launcher__0.xml

这类 `$` 前缀的内部资源在**部分国产 ROM 的安装器**上解析会失败，
表现为安装报错 "packageinfo is null"（APK 本身其实是完好的）。

所以背景改成 PNG 位图，彻底消除 `$` 内部资源。
前景（N 标志）保持矢量 —— 那里没有 aapt:attr，不产生内部资源。

背景是纯渐变，缩放无损，用 432x432 足够（自适应图标会按需缩放裁切）。
"""
import zlib
import struct
import os
import sys


def write_rgb_png(path, width, height, pixels):
    """pixels: bytearray, 每像素 3 字节 RGB。"""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter None
        raw.extend(pixels[y * width * 3:(y + 1) * width * 3])

    def chunk(typ, data):
        out = struct.pack('>I', len(data)) + typ + data
        return out + struct.pack('>I', zlib.crc32(typ + data) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def lerp(c1, c2, t):
    return int(c1 + (c2 - c1) * t)


def hex_rgb(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def build_gradient(size, stops):
    """
    按 stops 生成 135° 线性渐变。

    stops: [(offset, "#RRGGBB"), ...]，offset 递增。
    渐变方向与 SVG 的 x1=0,y1=0 → x2=1,y2=1 一致。
    """
    pixels = bytearray(size * size * 3)
    # 135° 方向的投影长度
    diag = size * 1.41421356

    for y in range(size):
        for x in range(size):
            # 沿 (1,1) 方向的投影，归一化到 0..1
            t = (x + y) / diag
            if t > 1.0:
                t = 1.0

            # 找到所在的区间
            color = stops[-1][1]
            for i in range(len(stops) - 1):
                o1, c1 = stops[i]
                o2, c2 = stops[i + 1]
                if o1 <= t <= o2:
                    local = 0.0 if o2 == o1 else (t - o1) / (o2 - o1)
                    color = (
                        lerp(c1[0], c2[0], local),
                        lerp(c1[1], c2[1], local),
                        lerp(c1[2], c2[2], local),
                    )
                    break
            else:
                color = stops[0][1]

            idx = (y * size + x) * 3
            pixels[idx] = color[0]
            pixels[idx + 1] = color[1]
            pixels[idx + 2] = color[2]
    return pixels


if __name__ == "__main__":
    res = sys.argv[1]
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 432

    stops = [
        (0.00, hex_rgb('#3730A3')),
        (0.55, hex_rgb('#6366F1')),
        (1.00, hex_rgb('#818CF8')),
    ]

    out_dir = os.path.join(res, "drawable")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, 'ic_launcher_bg_gradient.png')
    write_rgb_png(path, size, size, build_gradient(size, stops))
    print(f"生成 {path} ({size}x{size}, {os.path.getsize(path)} 字节)")
