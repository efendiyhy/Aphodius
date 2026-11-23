#!/usr/bin/env python3
"""
Hasilkan mockup UI Aphodius (SVG) sesuai layar yang benar-benar ada di aplikasi:
splash, onboarding, beranda, cari, baru, movie, daftarku, detail, profil, pemutar.

    python3 tools/branding/mockups.py

Keluaran: portrait/*.svg, landscape/*.svg, 00-design-system.svg, preview.html
"""
import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import beetle  # noqa: E402
import build  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

NAVY, SURF, HIGH, LINE = "#050D16", "#0D1A28", "#15283B", "#223850"
AQUA, GOLD, MUTED, SUBTLE, ONAQUA = "#22C7EC", "#F2A33A", "#8FA8BF", "#5C7790", "#031019"
FONT = "Roboto, Inter, Arial, sans-serif"
ITEMS = beetle.parts(True)

FILMS = [
    ("Senja di Pelabuhan", "2024", "Drama • Romansa", 0),
    ("Jejak Kumbang", "2023", "Dokumenter", 1),
    ("Malam Tanpa Bintang", "2022", "Thriller", 2),
    ("Samudra Terakhir", "2024", "Petualangan", 3),
    ("Kota di Balik Kabut", "2021", "Misteri", 4),
    ("Rimba Biru", "2023", "Petualangan", 5),
    ("Lintang", "2022", "Drama", 0),
    ("Gema Pulau", "2024", "Dokumenter", 1),
    ("Api dan Garam", "2020", "Aksi", 2),
]
GRADS = [("#0A84C6", "#05263F"), ("#22C7EC", "#0A3A6B"), ("#3A9BD9", "#0B2A4A"),
         ("#1B6CA8", "#061A2E"), ("#5FB8E6", "#123C5E"), ("#0E94C4", "#04121F")]

uid = [0]


def nid(p="i"):
    uid[0] += 1
    return f"{p}{uid[0]}"


class Svg:
    def __init__(self, w, h):
        self.w, self.h, self.defs, self.body = w, h, [], []

    def add(self, s):
        self.body.append(s)

    def grad(self, c0, c1, x2=1, y2=1, stops=None):
        i = nid("g")
        st = stops or [(0, c0, 1), (1, c1, 1)]
        s = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in st)
        self.defs.append(f'<linearGradient id="{i}" x1="0" y1="0" x2="{x2}" y2="{y2}">{s}</linearGradient>')
        return f"url(#{i})"

    def clip(self, x, y, w, h, r):
        i = nid("c")
        self.defs.append(f'<clipPath id="{i}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}"/></clipPath>')
        return f"url(#{i})"

    def rect(self, x, y, w, h, fill, r=0, stroke=None, op=None):
        st = f' stroke="{stroke}"' if stroke else ""
        o = f' fill-opacity="{op}"' if op is not None else ""
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}"{st}{o}/>')

    def circle(self, cx, cy, r, fill, stroke=None, sw=1):
        st = f' stroke="{stroke}" stroke-width="{sw}"' if stroke else ""
        self.add(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"{st}/>')

    def text(self, x, y, t, size=12, fill="#fff", weight=400, anchor="start", ls=0):
        t = t.replace("&", "&amp;")
        self.add(f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}" font-weight="{weight}" '
                 f'text-anchor="{anchor}" letter-spacing="{ls}">{t}</text>')

    def path(self, d, fill="none", stroke=None, sw=2, extra=""):
        s = f' stroke="{stroke}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"' if stroke else ""
        self.add(f'<path d="{d}" fill="{fill}"{s} {extra}/>')

    def icon(self, d, x, y, size=22, color="#fff", fill=False):
        k = size / 24
        if fill:
            self.add(f'<path transform="translate({x} {y}) scale({k})" d="{d}" fill="{color}"/>')
        else:
            self.add(f'<path transform="translate({x} {y}) scale({k})" d="{d}" fill="none" stroke="{color}" '
                     f'stroke-width="{2 / 1}" stroke-linecap="round" stroke-linejoin="round"/>')

    def beetle(self, cx, cy, h):
        x0, y0, x1, y1 = beetle.bbox(ITEMS)
        sc = h / (y1 - y0)
        self.add(build.beetle_svg_group(ITEMS, sc, cx - sc * (x0 + x1) / 2, cy - sc * (y0 + y1) / 2))

    def wordmark(self, x, cy, h, fill="#fff"):
        paths, w, cap = build.wordmark("APHODIUS", 100.0, tracking=0.18)
        k = h / cap
        self.add(f'<g transform="translate({x} {cy + h / 2}) scale({k:.4f})" fill="{fill}">' +
                 "".join(f'<path d="{d}"/>' for d in paths) + "</g>")
        return w * k

    def poster(self, x, y, w, h, film, r=12, play=True):
        c0, c1 = GRADS[film[3]]
        g = self.grad(c0, c1)
        cp = self.clip(x, y, w, h, r)
        seed = sum(map(ord, film[0]))
        self.add(f'<g clip-path="{cp}"><rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{g}"/>')
        cols, rows = 3, 4
        cw, ch = w / cols, h / rows
        for i in range(cols):
            for j in range(rows):
                s = (seed * (i + 3) * (j + 7)) % 100
                px, py = x + i * cw, y + j * ch
                a = 0.02 + (s % 10) / 100
                b = 0.02 + ((s * 3) % 16) / 100
                if s % 2:
                    self.add(f'<path d="M{px},{py} L{px + cw},{py} L{px},{py + ch}Z" fill="#fff" fill-opacity="{a:.2f}"/>')
                    self.add(f'<path d="M{px + cw},{py} L{px + cw},{py + ch} L{px},{py + ch}Z" fill="#000" fill-opacity="{b:.2f}"/>')
                else:
                    self.add(f'<path d="M{px},{py} L{px + cw},{py} L{px + cw},{py + ch}Z" fill="#fff" fill-opacity="{a:.2f}"/>')
                    self.add(f'<path d="M{px},{py} L{px + cw},{py + ch} L{px},{py + ch}Z" fill="#000" fill-opacity="{b:.2f}"/>')
        self.add("</g>")
        if play:
            self.icon("M6 4l14 8-14 8z", x + w / 2 - 10, y + h / 2 - 10, 20, "#ffffff99")

    def out(self):
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}" '
                f'font-family="{FONT}"><defs>{"".join(self.defs)}</defs>\n' + "\n".join(self.body) + "\n</svg>\n")


# ikon (path 24x24, sama dengan Icons.kt)
I = {
    "home": "M3 11l9-8 9 8v10h-6v-6H9v6H3z", "search": "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-6-6",
    "play": "M6 4l14 8-14 8z", "pause": "M6 4h4v16H6zM14 4h4v16h-4z", "plus": "M12 4v16M4 12h16",
    "check": "M4 12l5 5 11-11", "back": "M15 4l-8 8 8 8", "share": "M12 3v12M7 8l5-5 5 5M5 14v6h14v-6",
    "eps": "M4 4h16v16H4zM9 8l7 4-7 4z", "bookmark": "M6 3h12v18l-6-4-6 4z",
    "refresh": "M20 12a8 8 0 1 1-3-6.2M20 3v5h-5", "external": "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6",
    "volume": "M3 9h4l5-4v14l-5-4H3zM16 8a5 5 0 0 1 0 8", "speed": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2",
    "quality": "M4 7h8M16 7h4M4 17h4M12 17h8M12 4v6M8 14v6", "next": "M5 4l10 8-10 8zM19 5v14",
    "aspect": "M3 6h18v12H3zM7 10h10v4H7z", "rewind": "M4 12a8 8 0 1 0 3-6.2M4 3v5h5",
    "forward": "M20 12a8 8 0 1 1-3-6.2M20 3v5h-5",
}
TABS = [("Beranda", "home"), ("Baru", "eps"), ("Movie", "play"), ("Daftarku", "bookmark")]


def bottom_nav(s, w, y, h, active):
    s.rect(0, y, w, h, SURF)
    s.rect(0, y, w, 1, LINE)
    cw = w / 4
    for i, (label, ic) in enumerate(TABS):
        cx = cw * i + cw / 2
        on = i == active
        if on:
            s.rect(cx - 28, y + 9, 56, 30, AQUA, r=15, op=0.16)
        s.icon(I[ic], cx - 11, y + 13, 22, AQUA if on else MUTED)
        s.text(cx, y + 54, label, 11, AQUA if on else MUTED, 700 if on else 500, "middle")
    s.rect(w / 2 - 54, y + h - 7, 108, 4, "#ffffff", r=2, op=0.35)


def status_bar(s, w):
    s.text(20, 24, "09.41", 12, "#fff", 700)
    s.rect(w - 52, 15, 24, 10, "none", r=3, stroke="#ffffffaa")
    s.rect(w - 50, 17, 16, 6, "#fff", r=1.5)


def chip(s, x, y, label, on):
    w = len(label) * 7.2 + 32
    s.rect(x, y, w, 34, AQUA if on else HIGH, r=17)
    s.text(x + w / 2, y + 22, label, 13, ONAQUA if on else MUTED, 700 if on else 500, "middle")
    return w


def section(s, x, y, title):
    s.add(f'<rect x="{x}" y="{y - 5}" width="7" height="7" fill="{AQUA}" transform="rotate(45 {x + 3.5} {y - 1.5})"/>')
    s.text(x + 17, y + 3, title, 18, "#fff", 700)


def poster_row(s, x, y, pw, n, start=0, progress=False, gap=12):
    ph = pw * 1.5
    for i in range(n):
        f = FILMS[(start + i) % len(FILMS)]
        px = x + i * (pw + gap)
        s.poster(px, y, pw, ph, f)
        if progress:
            s.rect(px + 10, y + ph - 13, pw - 20, 4, "#fff", r=2, op=0.3)
            s.rect(px + 10, y + ph - 13, (pw - 20) * (0.25 + 0.2 * i), 4, AQUA, r=2)
        s.text(px, y + ph + 19, f[0][:18], 12, "#ffffffd9")


def fade(s, x, y, w, h, up=True):
    g = s.grad("", "", 0, 1, stops=[(0, NAVY, 0), (1, NAVY, 0.96)] if up else [(0, "#000", 0.55), (1, "#000", 0)])
    s.rect(x, y, w, h, g)


def avatar(s, cx, cy, r, letter="B"):
    g = s.grad("#0A84C6", AQUA, 1, 0)
    s.circle(cx, cy, r, g)
    s.text(cx, cy + r * 0.36, letter, r * 1.0, "#fff", 700, "middle")


def header(s, w, y=44):
    s.beetle(36, y + 16, 32)
    s.wordmark(58, y + 8, 13)
    s.text(58, y + 33, "Halo, Budi", 12, MUTED)
    for ic, ox in (("refresh", 124), ("search", 80)):
        s.circle(w - ox + 2, y + 16, 20, HIGH)
        s.icon(I[ic], w - ox - 9, y + 5, 22, "#fff")
    avatar(s, w - 36, y + 16, 20)


# ===================================================== PORTRAIT 360 x 800
def p_splash():
    s = Svg(360, 800)
    g = s.grad("#0B4A75", NAVY, 0, 1)
    s.rect(0, 0, 360, 800, g)
    for pts, a in build.BG_POLYS_108:
        s.path("M" + " L".join(f"{x * 360 / 108:.1f},{y * 800 / 108:.1f}" for x, y in pts) + "Z", fill="#fff", extra=f'fill-opacity="{a}"')
    s.beetle(180, 330, 150)
    s.wordmark(180 - 82, 468, 22)
    s.text(180, 514, "TONTON TANPA BATAS", 11, MUTED, 400, "middle", 3)
    s.add(f'<circle cx="180" cy="570" r="10" fill="none" stroke="{AQUA}" stroke-width="2" stroke-dasharray="46 20" stroke-linecap="round"/>')
    s.text(180, 738, "dari", 11, SUBTLE, 400, "middle")
    s.text(180, 758, "SengkuniDeveloper", 14, "#fff", 700, "middle")
    return s


def p_home():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, NAVY)
    status_bar(s, 360)
    header(s, 360)
    x = 16
    for i, c in enumerate(["Semua", "Petualangan", "Drama", "Dokumenter"]):
        x += chip(s, x, 100, c, i == 0) + 8
    # hero
    cp = s.clip(16, 152, 328, 440, 24)
    s.add(f'<g clip-path="{cp}">')
    s.poster(16, 152, 328, 440, FILMS[0], r=0, play=False)
    s.add("</g>")
    fade(s, 16, 280, 328, 312)
    s.rect(30, 166, 92, 26, "#000", r=13, op=0.45)
    s.add(f'<rect x="43" y="175" width="7" height="7" fill="{GOLD}" transform="rotate(45 46.5 178.5)"/>')
    s.text(58, 184, "SOROTAN", 10, GOLD, 700, ls=1.5)
    s.text(34, 506, FILMS[0][0], 27, "#fff", 900)
    s.text(34, 530, "2024 • Drama • Romansa", 13, MUTED)
    s.rect(34, 546, 150, 48, AQUA, r=14)
    s.icon(I["play"], 78, 560, 20, ONAQUA, True)
    s.text(106, 575, "PUTAR", 14, ONAQUA, 700, ls=0.5)
    s.rect(194, 546, 134, 48, "#fff", r=14, op=0.14)
    s.icon(I["plus"], 206, 560, 20, "#fff")
    s.text(264, 575, "DAFTARKU", 14, "#fff", 700, "middle", 0.5)
    section(s, 16, 622, "Lanjutkan Menonton")
    poster_row(s, 16, 640, 100, 3, 1, progress=True)
    bottom_nav(s, 360, 736, 64, 0)
    return s


def p_search():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, NAVY)
    status_bar(s, 360)
    s.rect(16, 52, 328, 52, SURF, r=16, stroke=LINE)
    s.icon(I["back"], 30, 66, 22, "#fff")
    s.icon(I["search"], 62, 66, 22, MUTED)
    s.text(96, 84, "kumbang", 15, "#fff")
    s.icon("M5 5l14 14M19 5L5 19", 312, 68, 20, MUTED)
    s.text(16, 134, "HASIL (3)", 11, MUTED, 700, ls=1.5)
    for i in range(7):
        f = FILMS[(i + 1) % len(FILMS)]
        y = 150 + i * 80
        s.poster(16, y, 56, 74, f, r=10, play=False)
        s.text(88, y + 28, f[0], 15, "#fff", 700)
        s.text(88, y + 48, f"Film • {f[1]}", 12, MUTED)
        s.circle(318, y + 37, 18, HIGH)
        s.icon(I["play"], 309, y + 28, 18, AQUA, True)
    return s


def p_new():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, NAVY)
    status_bar(s, 360)
    s.text(16, 96, "Baru dan Populer", 26, "#fff", 900)
    for i in range(2):
        f = FILMS[i + 1]
        y = 124 + i * 300
        s.text(16, y + 16, ["NOV", "OKT"][i], 11, GOLD, 700, ls=1)
        s.text(16, y + 46, ["22", "30"][i], 26, "#fff", 900)
        s.poster(68, y, 276, 155, f, r=16, play=False)
        s.circle(206, y + 77, 24, "#000")
        s.circle(206, y + 77, 24, "none", "#ffffff66", 1)
        s.icon(I["play"], 195, y + 66, 22, "#fff", True)
        if i == 0:
            s.rect(78, y + 10, 52, 22, GOLD, r=11)
            s.text(104, y + 25, "BARU", 10, ONAQUA, 700, "middle", 1)
        s.text(68, y + 182, f[0], 16, "#fff", 700)
        s.text(68, y + 202, f"{f[1]} • {f[2]}", 12, MUTED)
    bottom_nav(s, 360, 736, 64, 1)
    return s


def p_mylist():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, NAVY)
    status_bar(s, 360)
    s.text(16, 96, "Daftarku", 26, "#fff", 900)
    s.rect(262, 74, 82, 30, HIGH, r=15)
    s.text(303, 94, "6 judul", 12, MUTED, 500, "middle")
    pw = (360 - 32 - 24) / 3
    for i in range(6):
        f = FILMS[i % len(FILMS)]
        x = 16 + (i % 3) * (pw + 12)
        y = 124 + (i // 3) * (pw * 1.5 + 12)
        s.poster(x, y, pw, pw * 1.5, f)
    bottom_nav(s, 360, 736, 64, 3)
    return s


def p_detail():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, NAVY)
    s.poster(0, 0, 360, 360, FILMS[2], r=0, play=False)
    fade(s, 0, 0, 360, 110, up=False)
    fade(s, 0, 220, 360, 140)
    status_bar(s, 360)
    for cx, ic in ((38, "back"), (322, "share")):
        s.circle(cx, 68, 20, "#000")
        s.icon(I[ic], cx - 10, 58, 20, "#fff")
    s.circle(180, 180, 32, "#000")
    s.circle(180, 180, 32, "none", "#fff", 2)
    s.icon(I["play"], 168, 168, 24, "#fff", True)
    s.text(16, 398, FILMS[2][0], 28, "#fff", 900)
    s.text(16, 422, "Film • 2022", 13, MUTED)
    x = 16
    for g in ("Thriller", "Misteri"):
        w = len(g) * 7 + 26
        s.rect(x, 438, w, 28, HIGH, r=14)
        s.text(x + w / 2, 457, g, 12, MUTED, 500, "middle")
        x += w + 8
    s.text(16, 498, "Sinopsis", 15, "#fff", 700)
    for k, line in enumerate(("Seorang penjaga mercusuar menemukan peta tua yang", "membawanya menyusuri pulau-pulau terpencil dan",
                              "rahasia keluarga yang lama terkubur.")):
        s.text(16, 522 + k * 21, line, 14, "#D0DCE8")
    s.rect(16, 594, 328, 48, AQUA, r=14)
    s.icon(I["play"], 90, 608, 20, ONAQUA, True)
    s.text(122, 623, "LANJUTKAN 12:40", 14, ONAQUA, 700, ls=0.5)
    s.rect(16, 654, 328, 4, HIGH, r=2)
    s.rect(16, 654, 118, 4, AQUA, r=2)
    for i, (lab, ic) in enumerate((("Di Daftarku", "check"), ("Bagikan", "share"), ("Buka di X", "external"))):
        x = 16 + i * 112
        s.rect(x, 676, 104, 70, SURF, r=16, stroke=LINE)
        s.icon(I[ic], x + 40, 688, 24, "#fff")
        s.text(x + 52, 730, lab, 12, MUTED, 400, "middle")
    return s


def p_player():
    # pemutar selalu horizontal; di mockup potret ditampilkan terputar
    s = Svg(800, 360)
    s.poster(0, 0, 800, 360, FILMS[3], r=0, play=False)
    g = s.grad("", "", 0, 1, stops=[(0, "#000", .7), (.3, "#000", .25), (.65, "#000", .25), (1, "#000", .8)])
    s.rect(0, 0, 800, 360, g)
    s.circle(44, 40, 20, "#000", )
    s.icon(I["back"], 34, 30, 20, "#fff")
    s.text(400, 46, FILMS[3][0], 15, "#fff", 700, "middle")
    s.circle(756, 40, 20, "#000")
    s.icon(I["aspect"], 746, 30, 20, "#fff")
    s.icon(I["rewind"], 288, 154, 44, "#fff")
    s.text(310, 181, "10", 11, "#fff", 700, "middle")
    s.circle(400, 180, 36, AQUA)
    s.icon(I["pause"], 384, 164, 32, ONAQUA, True)
    s.icon(I["forward"], 468, 154, 44, "#fff")
    s.text(490, 181, "10", 11, "#fff", 700, "middle")
    s.text(40, 270, "21:05", 12, "#fff", 500)
    s.text(760, 270, "-38:12", 12, MUTED, 500, "end")
    s.rect(40, 280, 720, 4, "#fff", r=2, op=0.28)
    s.rect(40, 280, 260, 4, AQUA, r=2)
    s.circle(300, 282, 8, AQUA)
    for i, (lab, ic) in enumerate((("Suara", "volume"), ("Kualitas · 720p", "quality"), ("Kecepatan · 1x", "speed"), ("Berikutnya", "next"))):
        cx = 120 + i * 187
        s.icon(I[ic], cx - 11, 304, 22, "#fff")
        s.text(cx, 346, lab, 11, "#fff", 400, "middle")
    return s


def p_onboarding():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, s.grad("#0B4A75", NAVY, 0, 1))
    for pts, a in build.BG_POLYS_108:
        s.path("M" + " L".join(f"{x * 360 / 108:.1f},{y * 800 / 108:.1f}" for x, y in pts) + "Z", fill="#fff", extra=f'fill-opacity="{a}"')
    s.beetle(180, 250, 120)
    s.text(180, 400, "Selamat datang di Aphodius", 22, "#fff", 900, "middle")
    s.text(180, 428, "Siapa nama sapaanmu?", 14, MUTED, 400, "middle")
    s.rect(28, 452, 304, 52, SURF, r=16, stroke=LINE)
    s.text(46, 484, "Budi", 16, "#fff")
    s.rect(28, 520, 304, 48, AQUA, r=14)
    s.text(180, 550, "MULAI", 14, ONAQUA, 700, "middle", 0.5)
    s.text(180, 592, "Profil hanya tersimpan di perangkat ini.", 11, SUBTLE, 400, "middle")
    return s


def p_movie():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, NAVY)
    status_bar(s, 360)
    s.text(16, 96, "Movie", 26, "#fff", 900)
    for gi, (g, n) in enumerate((("Drama", 3), ("Dokumenter", 2), ("Petualangan", 3))):
        y = 126 + gi * 200
        s.add(f'<rect x="16" y="{y + 7}" width="7" height="7" fill="{AQUA}" transform="rotate(45 19.5 {y + 10.5})"/>')
        s.text(33, y + 16, g, 18, "#fff", 700)
        s.rect(306, y - 2, 38, 24, HIGH, r=12)
        s.text(325, y + 15, str(n), 12, MUTED, 500, "middle")
        for i in range(3):
            s.poster(16 + i * 112, y + 32, 100, 130, FILMS[(gi * 3 + i) % len(FILMS)], r=12)
    bottom_nav(s, 360, 736, 64, 2)
    return s


def p_profile():
    s = Svg(360, 800)
    s.rect(0, 0, 360, 800, NAVY)
    status_bar(s, 360)
    s.circle(38, 68, 20, HIGH)
    s.icon(I["back"], 28, 58, 20, "#fff")
    s.text(70, 76, "Profil", 24, "#fff", 900)
    avatar(s, 180, 160, 42, "B")
    s.text(180, 236, "Budi", 20, "#fff", 700, "middle")
    s.text(180, 258, "Ubah nama sapaan", 13, AQUA, 400, "middle")

    def head(y, t):
        s.text(16, y, t, 11, AQUA, 700, ls=1.5)

    def row(y, t, sub, on):
        s.text(16, y, t, 15, "#fff")
        s.text(16, y + 19, sub, 12, MUTED)
        s.rect(288, y - 12, 52, 32, AQUA if on else HIGH, r=16)
        s.circle(324 if on else 304, y + 4, 11 if on else 9, ONAQUA if on else MUTED)

    head(310, "KEAMANAN")
    row(344, "Kunci aplikasi", "Aktif — PIN 4 digit", True)
    row(402, "Buka dengan sidik jari", "Alternatif selain PIN", True)
    head(470, "PEMUTAR")
    row(504, "Crop video", "Memakai crop tersimpan dari pemutar", False)
    head(572, "TENTANG")
    s.text(16, 604, "Aphodius v1.2.1", 14, MUTED)
    s.rect(16, 630, 328, 48, "#fff", r=14, op=0.14)
    s.icon(I["refresh"], 108, 644, 20, "#fff")
    s.text(140, 660, "Cek pembaruan", 14, "#fff", 700, ls=0.5)
    return s


PORTRAIT = [("01-splash", p_splash), ("02-onboarding", p_onboarding), ("03-home", p_home), ("04-search", p_search),
            ("05-new", p_new), ("06-movie", p_movie), ("07-my-list", p_mylist), ("08-detail", p_detail),
            ("09-profile", p_profile), ("10-player", p_player)]


# ===================================================== LANDSCAPE 800 x 360
def land_nav_rail(s, h, active):
    s.rect(0, 0, 76, h, SURF)
    s.rect(75, 0, 1, h, LINE)
    s.beetle(38, 34, 34)
    for i, (label, ic) in enumerate(TABS):
        cy = 92 + i * 62
        on = i == active
        if on:
            s.rect(14, cy - 4, 48, 30, AQUA, r=15, op=0.16)
        s.icon(I[ic], 27, cy, 22, AQUA if on else MUTED)
        s.text(38, cy + 40, label, 10, AQUA if on else MUTED, 700 if on else 500, "middle")


def l_splash():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, s.grad("#0B4A75", NAVY, 0, 1))
    k = 800 / 108
    for pts, a in build.BG_POLYS_108:
        s.path("M" + " L".join(f"{x * k:.1f},{y * 360 / 108:.1f}" for x, y in pts) + "Z", fill="#fff", extra=f'fill-opacity="{a}"')
    s.beetle(400, 128, 130)
    s.wordmark(400 - 70, 236, 19)
    s.text(400, 272, "TONTON TANPA BATAS", 10, MUTED, 400, "middle", 3)
    s.text(400, 330, "SengkuniDeveloper", 12, "#fff", 700, "middle")
    return s


def l_home():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, NAVY)
    land_nav_rail(s, 360, 0)
    x0 = 96
    s.wordmark(x0, 28, 12)
    avatar(s, 764, 28, 16)
    x = x0 + 150
    for i, c in enumerate(["Semua", "Petualangan", "Drama"]):
        x += chip(s, x, 11, c, i == 0) + 8
    cp = s.clip(x0, 52, 684, 170, 22)
    s.add(f'<g clip-path="{cp}">')
    s.poster(x0, 52, 684, 170, FILMS[0], r=0, play=False)
    s.add("</g>")
    fade(s, x0, 100, 684, 122)
    s.rect(x0 + 16, 64, 90, 24, "#000", r=12, op=0.45)
    s.text(x0 + 61, 80, "SOROTAN", 10, GOLD, 700, "middle", 1.5)
    s.text(x0 + 20, 172, FILMS[0][0], 24, "#fff", 900)
    s.text(x0 + 20, 192, "2024 • Drama • Romansa", 12, MUTED)
    s.rect(x0 + 470, 164, 96, 40, AQUA, r=12)
    s.text(x0 + 518, 189, "PUTAR", 13, ONAQUA, 700, "middle", 0.5)
    s.rect(x0 + 576, 164, 96, 40, "#fff", r=12, op=0.14)
    s.text(x0 + 624, 189, "DAFTARKU", 12, "#fff", 700, "middle")
    section(s, x0, 244, "Baru Ditambah")
    for i in range(6):
        f = FILMS[i % len(FILMS)]
        s.poster(x0 + i * 112, 256, 100, 104, f, r=10, play=False)
    return s


def l_search():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, NAVY)
    s.rect(40, 16, 720, 46, SURF, r=16, stroke=LINE)
    s.icon(I["back"], 54, 27, 22, "#fff")
    s.icon(I["search"], 88, 27, 22, MUTED)
    s.text(122, 45, "kumbang", 15, "#fff")
    for i in range(6):
        f = FILMS[(i + 1) % len(FILMS)]
        col, row = i % 2, i // 2
        x = 40 + col * 372
        y = 82 + row * 90
        s.poster(x, y, 50, 74, f, r=10, play=False)
        s.text(x + 64, y + 30, f[0], 14, "#fff", 700)
        s.text(x + 64, y + 50, f"Film • {f[1]}", 12, MUTED)
        s.circle(x + 336, y + 37, 16, HIGH)
        s.icon(I["play"], x + 328, y + 29, 16, AQUA, True)
    return s


def l_new():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, NAVY)
    land_nav_rail(s, 360, 1)
    s.text(96, 44, "Baru dan Populer", 24, "#fff", 900)
    for i in range(2):
        f = FILMS[i + 1]
        x = 96 + i * 350
        s.poster(x, 68, 334, 188, f, r=16, play=False)
        s.circle(x + 167, 162, 24, "#000")
        s.icon(I["play"], x + 156, 151, 22, "#fff", True)
        if i == 0:
            s.rect(x + 12, 80, 52, 22, GOLD, r=11)
            s.text(x + 38, 95, "BARU", 10, ONAQUA, 700, "middle", 1)
        s.text(x, 282, f[0], 16, "#fff", 700)
        s.text(x, 302, f"{f[1]} • {f[2]}", 12, MUTED)
    return s


def l_mylist():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, NAVY)
    land_nav_rail(s, 360, 3)
    s.text(96, 44, "Daftarku", 24, "#fff", 900)
    s.rect(690, 24, 84, 28, HIGH, r=14)
    s.text(732, 43, "6 judul", 12, MUTED, 500, "middle")
    pw = (684 - 5 * 12) / 6
    for i in range(6):
        s.poster(96 + i * (pw + 12), 72, pw, pw * 1.5, FILMS[i % len(FILMS)])
    return s


def l_detail():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, NAVY)
    s.poster(0, 0, 800, 280, FILMS[2], r=0, play=False)
    fade(s, 0, 0, 800, 90, up=False)
    fade(s, 0, 150, 800, 130)
    s.circle(40, 40, 20, "#000")
    s.icon(I["back"], 30, 30, 20, "#fff")
    s.circle(760, 40, 20, "#000")
    s.icon(I["share"], 750, 30, 20, "#fff")
    s.circle(400, 130, 32, "#000")
    s.circle(400, 130, 32, "none", "#fff", 2)
    s.icon(I["play"], 388, 118, 24, "#fff", True)
    s.text(40, 270, FILMS[2][0], 26, "#fff", 900)
    s.text(40, 294, "Film • 2022 • Thriller • Misteri", 13, MUTED)
    s.rect(40, 308, 300, 44, AQUA, r=14)
    s.text(190, 335, "LANJUTKAN 12:40", 14, ONAQUA, 700, "middle", 0.5)
    for i, ic in enumerate(("check", "share", "external")):
        x = 356 + i * 62
        s.rect(x, 308, 52, 44, SURF, r=14, stroke=LINE)
        s.icon(I[ic], x + 15, 319, 22, "#fff")
    return s


def l_onboarding():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, s.grad("#0B4A75", NAVY, 0, 1))
    for pts, a in build.BG_POLYS_108:
        s.path("M" + " L".join(f"{x * 800 / 108:.1f},{y * 360 / 108:.1f}" for x, y in pts) + "Z", fill="#fff", extra=f'fill-opacity="{a}"')
    s.beetle(230, 180, 170)
    s.text(560, 110, "Selamat datang di Aphodius", 22, "#fff", 900, "middle")
    s.text(560, 138, "Siapa nama sapaanmu?", 14, MUTED, 400, "middle")
    s.rect(420, 160, 280, 50, SURF, r=16, stroke=LINE)
    s.text(438, 191, "Budi", 16, "#fff")
    s.rect(420, 226, 280, 46, AQUA, r=14)
    s.text(560, 255, "MULAI", 14, ONAQUA, 700, "middle", 0.5)
    return s


def l_movie():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, NAVY)
    land_nav_rail(s, 360, 2)
    s.text(96, 44, "Movie", 24, "#fff", 900)
    for gi, g in enumerate(("Drama", "Dokumenter")):
        y = 70 + gi * 146
        s.add(f'<rect x="96" y="{y + 4}" width="7" height="7" fill="{AQUA}" transform="rotate(45 99.5 {y + 7.5})"/>')
        s.text(113, y + 13, g, 16, "#fff", 700)
        for i in range(6):
            s.poster(96 + i * 112, y + 24, 100, 100, FILMS[(gi * 3 + i) % len(FILMS)], r=10, play=False)
    return s


def l_profile():
    s = Svg(800, 360)
    s.rect(0, 0, 800, 360, NAVY)
    s.circle(38, 38, 20, HIGH)
    s.icon(I["back"], 28, 28, 20, "#fff")
    s.text(70, 46, "Profil", 24, "#fff", 900)
    avatar(s, 120, 150, 44, "B")
    s.text(120, 226, "Budi", 20, "#fff", 700, "middle")
    s.text(120, 248, "Ubah nama sapaan", 13, AQUA, 400, "middle")
    for i, (t, sub, on) in enumerate((("Kunci aplikasi", "Aktif — PIN 4 digit", True), ("Buka dengan sidik jari", "Alternatif selain PIN", True),
                                      ("Crop video", "Memakai crop tersimpan dari pemutar", False))):
        y = 96 + i * 64
        s.text(280, y, t, 15, "#fff")
        s.text(280, y + 19, sub, 12, MUTED)
        s.rect(700, y - 12, 52, 32, AQUA if on else HIGH, r=16)
        s.circle(736 if on else 716, y + 4, 11 if on else 9, ONAQUA if on else MUTED)
    s.rect(280, 296, 472, 44, "#fff", r=14, op=0.14)
    s.text(516, 324, "Cek pembaruan", 14, "#fff", 700, "middle", 0.5)
    return s


LANDSCAPE = [("01-splash", l_splash), ("02-onboarding", l_onboarding), ("03-home", l_home), ("04-search", l_search),
             ("05-new", l_new), ("06-movie", l_movie), ("07-my-list", l_mylist), ("08-detail", l_detail),
             ("09-profile", l_profile), ("10-player", p_player)]


# ===================================================== design system 1000 x 760
def design_system():
    s = Svg(1000, 760)
    s.rect(0, 0, 1000, 760, NAVY)
    for pts, a in build.BG_POLYS_108:
        s.path("M" + " L".join(f"{x * 1000 / 108:.1f},{y * 760 / 108:.1f}" for x, y in pts) + "Z", fill="#fff", extra=f'fill-opacity="{a / 2}"')
    s.beetle(100, 110, 120)
    s.wordmark(180, 90, 28, "#fff")
    s.text(182, 138, "Design System • Android Streaming • oleh SengkuniDeveloper", 13, MUTED)
    cols = [("Aqua", AQUA, "Aksi utama, aktif"), ("Ocean", "#0A84C6", "Gradien merek"), ("Navy", NAVY, "Latar"),
            ("Surface", SURF, "Kartu, navigasi"), ("Surface High", HIGH, "Input, chip"), ("Gold", GOLD, "Aksen scarab"),
            ("Muted", MUTED, "Teks sekunder"), ("Outline", LINE, "Garis tipis")]
    for i, (n, c, d) in enumerate(cols):
        x = 40 + (i % 4) * 238
        y = 190 + (i // 4) * 130
        s.rect(x, y, 216, 70, c, r=14, stroke="#ffffff33")
        s.text(x, y + 92, n, 13, "#fff", 700)
        s.text(x, y + 110, f"{c}  ·  {d}", 11, MUTED)
    s.text(40, 470, "TIPOGRAFI", 11, MUTED, 700, ls=2)
    s.text(40, 510, "Judul 28 / Black", 28, "#fff", 900)
    s.text(40, 540, "Bagian 18 / Bold      Isi 15 / Regular      Meta 12 / Medium", 15, "#fff")
    s.text(40, 572, "LABEL 11 / BOLD / +1.5", 11, MUTED, 700, ls=1.5)
    s.text(500, 470, "KOMPONEN", 11, MUTED, 700, ls=2)
    s.rect(500, 490, 150, 48, AQUA, r=14)
    s.text(575, 519, "PUTAR", 14, ONAQUA, 700, "middle", 0.5)
    s.rect(662, 490, 150, 48, "#fff", r=14, op=0.14)
    s.text(737, 519, "DAFTARKU", 14, "#fff", 700, "middle", 0.5)
    chip(s, 500, 556, "Aktif", True)
    chip(s, 580, 556, "Chip", False)
    s.rect(662, 556, 150, 34, HIGH, r=17)
    s.circle(830, 573, 20, HIGH)
    s.icon(I["search"], 820, 563, 20, "#fff")
    s.text(40, 640, "BENTUK", 11, MUTED, 700, ls=2)
    s.text(40, 668, "Sudut: poster 12 · kartu 16 · hero 24 · tombol 14 · chip & lencana penuh", 13, "#fff")
    s.text(40, 692, "Ikon: 24dp, garis 2, ujung bulat · Penanda bagian: belah ketupat Aqua", 13, "#fff")
    s.text(40, 716, "Ikon aplikasi & logo dibangkitkan dari tools/branding (kumbang tahi polygon)", 13, MUTED)
    return s


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(text)


def main():
    for d in ("portrait", "landscape"):
        for f in glob.glob(os.path.join(ROOT, d, "*.svg")):
            os.remove(f)
    for name, fn in PORTRAIT:
        write(os.path.join(ROOT, "portrait", f"{name}.svg"), fn().out())
    for name, fn in LANDSCAPE:
        write(os.path.join(ROOT, "landscape", f"{name}.svg"), fn().out())
    write(os.path.join(ROOT, "00-design-system.svg"), design_system().out())

    names = [n for n, _ in PORTRAIT]
    titles = {"01-splash": "Splash", "02-onboarding": "Selamat datang", "03-home": "Beranda", "04-search": "Cari",
              "05-new": "Baru dan Populer", "06-movie": "Movie", "07-my-list": "Daftarku", "08-detail": "Detail film",
              "09-profile": "Profil", "10-player": "Pemutar"}
    html = ['<!doctype html><html lang="id"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>Aphodius — Mockup UI</title><style>'
            'body{margin:0;background:#050D16;color:#fff;font-family:Roboto,Inter,Arial,sans-serif;padding:32px 20px 64px}'
            'h1{font-size:28px;margin:0 0 4px}p.s{color:#8FA8BF;margin:0 0 28px}h2{font-size:12px;letter-spacing:.15em;'
            'text-transform:uppercase;color:#8FA8BF;margin:36px 0 12px}.row{display:flex;gap:20px;flex-wrap:wrap;align-items:flex-start}'
            'img{display:block;border-radius:18px;border:1px solid #223850;background:#050D16}.p img{height:560px}.l img{height:230px}'
            '.ds{max-width:900px;width:100%}.logos img{border:0;background:none;height:90px}</style>'
            '<h1>Aphodius — Mockup UI</h1><p class="s">Kumbang tahi polygon · SengkuniDeveloper</p>'
            '<div class="row logos"><img src="branding/aphodius-app-icon.svg" style="height:120px">'
            '<img src="branding/aphodius-logo.svg"><img src="branding/aphodius-logo-stacked.svg" style="height:140px"></div>'
            '<h2>Design system</h2><img class="ds" src="00-design-system.svg">'
            '<h2>Potret</h2><div class="row p">']
    for n in names[:-1]:
        html.append(f'<figure style="margin:0"><img src="portrait/{n}.svg" alt="{titles[n]}"><figcaption style="color:#8FA8BF;font-size:13px;margin-top:8px">{titles[n]}</figcaption></figure>')
    html.append('</div><h2>Lanskap</h2><div class="row l">')
    for n in names:
        html.append(f'<figure style="margin:0"><img src="landscape/{n}.svg" alt="{titles[n]}"><figcaption style="color:#8FA8BF;font-size:13px;margin-top:8px">{titles[n]}</figcaption></figure>')
    html.append("</div></html>\n")
    write(os.path.join(ROOT, "preview.html"), "".join(html))
    print("mockup selesai")


if __name__ == "__main__":
    main()
