#!/usr/bin/env python3
"""
Hasilkan semua aset logo Aphodius dari geometri di beetle.py.

Jalankan dari akar repo:
    python3 tools/branding/build.py

Keluaran:
    branding/*.svg, branding/aphodius-app-icon-512.png
    app/src/main/res/drawable/ic_beetle.xml          (logo berwarna untuk dalam aplikasi)
    app/src/main/res/drawable/ic_wordmark.xml        (tulisan APHODIUS, satu warna, bisa di-tint)
    app/src/main/res/drawable/ic_launcher_*.xml      (ikon adaptif: foreground, background, monochrome)

Teks wordmark diubah menjadi path (Inter ExtraBold) supaya tampil sama di mana pun
tanpa perlu font terpasang. Huruf "O" diganti segi enam agar senada dengan gaya polygon.
"""
import math
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import beetle  # noqa: E402

from fontTools.pens.svgPathPen import SVGPathPen  # noqa: E402
from fontTools.pens.transformPen import TransformPen  # noqa: E402
from fontTools.ttLib import TTFont  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
BRAND_DIR = os.path.join(ROOT, "branding")
RES_DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

FONT_PATH = "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf"

# Warna merek
NAVY = "#030B14"
INK = "#07121D"
WHITE = "#FFFFFF"
AQUA_1 = "#1B9DDB"
AQUA_2 = "#6FE3F7"


def fmt(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def poly_d(poly):
    return "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in poly) + "Z"


# ---------------------------------------------------------------- kumbang
def beetle_items(gold=True):
    return beetle.parts(gold)


def fit_transform(items, box_w, box_h, cx, cy, pad=0.0):
    """Skala + geser agar kumbang muat dalam box dan berpusat di (cx, cy)."""
    x0, y0, x1, y1 = beetle.bbox(items)
    w, h = x1 - x0, y1 - y0
    s = min((box_w * (1 - pad)) / w, (box_h * (1 - pad)) / h)
    tx = cx - s * (x0 + w / 2)
    ty = cy - s * (y0 + h / 2)
    return s, tx, ty


def beetle_svg_group(items, s=1.0, tx=0.0, ty=0.0, mono=None):
    out = [f'<g transform="translate({fmt(tx)} {fmt(ty)}) scale({fmt(s)})">']
    for poly, col in items:
        c = mono or col
        out.append(f'<path d="{poly_d(poly)}" fill="{c}" stroke="{c}" stroke-width="0.8" stroke-linejoin="round"/>')
    out.append("</g>")
    return "\n".join(out)


# ---------------------------------------------------------------- wordmark
def _stem_width(glyphset, cmap, upm):
    from fontTools.pens.boundsPen import BoundsPen
    bp = BoundsPen(glyphset)
    glyphset[cmap[ord("I")]].draw(bp)
    x0, _, x1, _ = bp.bounds
    return (x1 - x0)


def wordmark(text="APHODIUS", size=100.0, tracking=0.16, hex_o=True):
    """Kembalikan (daftar path d, lebar, tinggi huruf kapital) dalam satuan px (y ke bawah, baseline y=0)."""
    f = TTFont(FONT_PATH)
    gs = f.getGlyphSet()
    cmap = f.getBestCmap()
    upm = f["head"].unitsPerEm
    cap = f["OS/2"].sCapHeight
    hmtx = f["hmtx"]
    k = size / upm
    stem = _stem_width(gs, cmap, upm) * k
    track = tracking * size
    x = 0.0
    paths = []
    for ch in text:
        gname = cmap[ord(ch)]
        adv = hmtx[gname][0] * k
        if ch == "O" and hex_o:
            h = cap * k * 1.03                    # tinggi luar (pointy-top)
            r = h / 2                             # jari-jari luar
            w = r * math.sqrt(3)
            side = adv * 0.06
            cx = x + side + w / 2
            cy = -cap * k / 2
            outer = [(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))) for a in range(-90, 270, 60)]
            apothem_in = (r * math.sqrt(3) / 2) - stem * 0.92
            ri = apothem_in / (math.sqrt(3) / 2)
            inner = [(cx + ri * math.cos(math.radians(a)), cy + ri * math.sin(math.radians(a))) for a in range(-90, 270, 60)]
            inner.reverse()                       # arah berlawanan -> lubang (nonzero)
            paths.append(poly_d(outer) + poly_d(inner))
            x += w + side * 2 + track
        else:
            pen = SVGPathPen(gs, ntos=lambda v: fmt(v))
            tp = TransformPen(pen, (k, 0, 0, -k, x, 0))
            gs[gname].draw(tp)
            paths.append(pen.getCommands())
            x += adv + track
    width = x - track
    return paths, width, cap * k


# ---------------------------------------------------------------- SVG
def svg_wrap(w, h, inner, defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(w)} {fmt(h)}" width="{fmt(w)}" height="{fmt(h)}">'
            f'{("<defs>" + defs + "</defs>") if defs else ""}\n{inner}\n</svg>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def grad_def(gid, c0, c1, x1="0", y1="0", x2="1", y2="0"):
    return (f'<linearGradient id="{gid}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">'
            f'<stop offset="0" stop-color="{c0}"/><stop offset="1" stop-color="{c1}"/></linearGradient>')


# segitiga latar low-poly untuk ikon aplikasi (koordinat 0..108, alpha putih)
BG_POLYS_108 = [
    ([(0, 0), (62, 0), (0, 58)], 0.06),
    ([(62, 0), (108, 0), (108, 40), (86, 30)], 0.035),
    ([(0, 58), (40, 108), (0, 108)], 0.05),
    ([(108, 40), (108, 108), (70, 108)], 0.045),
    ([(0, 58), (62, 0), (86, 30), (40, 108)], 0.02),
]


def app_icon_svg(items, size=512, radius=116):
    k = size / 108.0
    x0, y0, x1, y1 = beetle.bbox(items)
    h = y1 - y0
    sc = size * 0.70 / h
    tx = size / 2 - sc * ((x0 + x1) / 2)
    ty = size / 2 - sc * ((y0 + y1) / 2)
    bg = []
    for poly, a in BG_POLYS_108:
        bg.append(f'<path d="{poly_d([(x * k, y * k) for x, y in poly])}" fill="#FFFFFF" fill-opacity="{a}"/>')
    inner = (f'<clipPath id="r"><rect width="{size}" height="{size}" rx="{radius}"/></clipPath>'
             f'<g clip-path="url(#r)"><rect width="{size}" height="{size}" fill="url(#bg)"/>' + "".join(bg) + "</g>\n" +
             beetle_svg_group(items, sc, tx, ty))
    defs = grad_def("bg", "#0E5A8A", "#030F1B", "0", "0", "1", "1")
    return svg_wrap(size, size, inner, defs)


def lockup_svg(items, text_fill, tagline=None, tag_fill=None):
    """Logo horizontal: kumbang + wordmark."""
    mark_h = 160.0
    x0, y0, x1, y1 = beetle.bbox(items)
    sc = mark_h / (y1 - y0)
    mark_w = (x1 - x0) * sc
    wm_size = 96.0
    paths, wm_w, cap_h = wordmark("APHODIUS", wm_size, tracking=0.18)
    gap = 44.0
    pad = 8.0
    total_w = pad + mark_w + gap + wm_w + pad
    total_h = pad * 2 + mark_h + (0 if not tagline else 0)
    tx = pad - sc * x0
    ty = pad - sc * y0
    base = pad + mark_h / 2 + cap_h / 2 + (14 if tagline else 0) - (22 if tagline else 0)
    wx = pad + mark_w + gap
    body = [beetle_svg_group(items, sc, tx, ty)]
    fill = text_fill
    body.append(f'<g transform="translate({fmt(wx)} {fmt(base)})" fill="{fill}">' +
                "".join(f'<path d="{d}"/>' for d in paths) + "</g>")
    if tagline:
        body.append(f'<text x="{fmt(wx + 3)}" y="{fmt(base + 34)}" font-family="Inter, Roboto, Arial, sans-serif" '
                    f'font-size="19" letter-spacing="5" fill="{tag_fill}">{tagline}</text>')
    defs = grad_def("wm", AQUA_1, AQUA_2) if text_fill.startswith("url(") else ""
    return svg_wrap(total_w, total_h, "\n".join(body), defs)


def stacked_svg(items, text_fill, tagline=None, tag_fill=None):
    mark_h = 300.0
    x0, y0, x1, y1 = beetle.bbox(items)
    sc = mark_h / (y1 - y0)
    mark_w = (x1 - x0) * sc
    wm_size = 84.0
    paths, wm_w, cap_h = wordmark("APHODIUS", wm_size, tracking=0.2)
    total_w = max(mark_w, wm_w) + 80
    total_h = 40 + mark_h + 44 + cap_h + (60 if tagline else 0) + 36
    tx = total_w / 2 - sc * ((x0 + x1) / 2)
    ty = 40 - sc * y0
    base = 40 + mark_h + 44 + cap_h
    body = [beetle_svg_group(items, sc, tx, ty)]
    body.append(f'<g transform="translate({fmt((total_w - wm_w) / 2)} {fmt(base)})" fill="{text_fill}">' +
                "".join(f'<path d="{d}"/>' for d in paths) + "</g>")
    if tagline:
        body.append(f'<text x="{fmt(total_w / 2)}" y="{fmt(base + 46)}" text-anchor="middle" '
                    f'font-family="Inter, Roboto, Arial, sans-serif" font-size="20" letter-spacing="6" fill="{tag_fill}">{tagline}</text>')
    defs = grad_def("wm", AQUA_1, AQUA_2) if text_fill.startswith("url(") else ""
    return svg_wrap(total_w, total_h, "\n".join(body), defs)


# ---------------------------------------------------------------- Android VectorDrawable
AND_NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def vd_open(w_dp, h_dp, vw, vh):
    return (f'<vector {AND_NS}\n    android:width="{fmt(w_dp)}dp" android:height="{fmt(h_dp)}dp"\n'
            f'    android:viewportWidth="{fmt(vw)}" android:viewportHeight="{fmt(vh)}">\n')


def vd_beetle_paths(items, indent="    ", mono=None):
    lines = []
    for poly, col in items:
        c = mono or col
        lines.append(f'{indent}<path android:fillColor="{c}" android:strokeColor="{c}" android:strokeWidth="0.8" '
                     f'android:strokeLineJoin="round" android:pathData="{poly_d(poly)}" />')
    return "\n".join(lines)


def android_beetle(items):
    x0, y0, x1, y1 = beetle.bbox(items)
    # viewport dipangkas ke kotak kumbang (+ sedikit margin) agar mudah dipakai di Compose
    m = 6
    vx0, vy0, vx1, vy1 = x0 - m, y0 - m, x1 + m, y1 + m
    w, h = vx1 - vx0, vy1 - vy0
    out = vd_open(w / 4, h / 4, w, h)
    out += f'    <group android:translateX="{fmt(-vx0)}" android:translateY="{fmt(-vy0)}">\n'
    out += vd_beetle_paths(items, indent="        ")
    out += "\n    </group>\n</vector>\n"
    return out, (w, h)


def android_launcher_fg(items, mono=None):
    x0, y0, x1, y1 = beetle.bbox(items)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    # radius terjauh dari pusat harus muat di lingkaran aman 72dp (jari-jari 36)
    r = max(math.hypot(x - cx, y - cy) for poly, _ in items for x, y in poly)
    s = 35.0 / r
    tx, ty = 54 - s * cx, 54 - s * cy
    out = vd_open(108, 108, 108, 108)
    out += (f'    <group android:scaleX="{s:.4f}" android:scaleY="{s:.4f}" '
            f'android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">\n')
    out += vd_beetle_paths(items, indent="        ", mono=mono)
    out += "\n    </group>\n</vector>\n"
    return out


def android_launcher_bg():
    out = vd_open(108, 108, 108, 108)
    out += ('    <path android:pathData="M0,0h108v108h-108z">\n'
            '        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">\n'
            '            <gradient android:startX="0" android:startY="0" android:endX="108" android:endY="108"\n'
            '                android:startColor="#0E5A8A" android:endColor="#030F1B" />\n'
            '        </aapt:attr>\n    </path>\n')
    for poly, a in BG_POLYS_108:
        out += f'    <path android:fillColor="#FFFFFF" android:fillAlpha="{a}" android:pathData="{poly_d(poly)}" />\n'
    out += "</vector>\n"
    return out


def android_wordmark(paths, w, cap_h):
    # tinggi huruf kapital -> viewport; baseline di bawah
    pad = 1.0
    vw, vh = w + pad * 2, cap_h * 1.06 + pad * 2
    out = vd_open(vw / 4, vh / 4, vw, vh)
    out += f'    <group android:translateX="{fmt(pad)}" android:translateY="{fmt(pad + cap_h * 1.03)}">\n'
    for d in paths:
        out += f'        <path android:fillColor="#FFFFFF" android:pathData="{d}" />\n'
    out += "    </group>\n</vector>\n"
    return out, (vw, vh)


# ---------------------------------------------------------------- main
def main():
    items = beetle_items(gold=True)
    mono_items = items

    # --- SVG
    write(os.path.join(BRAND_DIR, "aphodius-mark.svg"), _mark_svg(items))
    write(os.path.join(BRAND_DIR, "aphodius-mark-mono.svg"), _mark_svg(items, mono="#000000"))
    write(os.path.join(BRAND_DIR, "aphodius-app-icon.svg"), app_icon_svg(items))
    write(os.path.join(BRAND_DIR, "aphodius-logo.svg"),
          lockup_svg(items, "url(#wm)", tagline="TONTON TANPA BATAS", tag_fill="#8FA8BF"))
    write(os.path.join(BRAND_DIR, "aphodius-logo-light.svg"),
          lockup_svg(items, "#06223B", tagline="TONTON TANPA BATAS", tag_fill="#4E6A84"))
    write(os.path.join(BRAND_DIR, "aphodius-logo-stacked.svg"),
          stacked_svg(items, "url(#wm)", tagline="TONTON TANPA BATAS", tag_fill="#8FA8BF"))

    # --- Android
    xml, _ = android_beetle(items)
    write(os.path.join(RES_DRAWABLE, "ic_beetle.xml"), xml)
    paths, wm_w, cap_h = wordmark("APHODIUS", 100.0, tracking=0.18)
    wm_xml, wm_vp = android_wordmark(paths, wm_w, cap_h)
    write(os.path.join(RES_DRAWABLE, "ic_wordmark.xml"), wm_xml)
    write(os.path.join(RES_DRAWABLE, "ic_launcher_foreground.xml"), android_launcher_fg(items))
    write(os.path.join(RES_DRAWABLE, "ic_launcher_monochrome.xml"), android_launcher_fg(items, mono="#FFFFFF"))
    write(os.path.join(RES_DRAWABLE, "ic_launcher_background.xml"), android_launcher_bg())
    # rasio wordmark dipakai Compose: lebar/tinggi viewport
    print("wordmark viewport: %.1f x %.1f (rasio %.3f)" % (wm_vp[0], wm_vp[1], wm_vp[0] / wm_vp[1]))

    # --- PNG 512 untuk toko aplikasi / pratinjau
    rsvg = shutil.which("rsvg-convert")
    if rsvg:
        subprocess.run([rsvg, "-w", "512", os.path.join(BRAND_DIR, "aphodius-app-icon.svg"),
                        "-o", os.path.join(BRAND_DIR, "aphodius-app-icon-512.png")], check=True)
    else:
        print("rsvg-convert tidak ada: PNG dilewati")
    print("selesai")


def _mark_svg(items, mono=None):
    x0, y0, x1, y1 = beetle.bbox(items)
    m = 8
    w, h = (x1 - x0) + m * 2, (y1 - y0) + m * 2
    return svg_wrap(w, h, beetle_svg_group(items, 1.0, m - x0, m - y0, mono=mono))


if __name__ == "__main__":
    main()
