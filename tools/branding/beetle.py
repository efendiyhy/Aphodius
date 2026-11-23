"""
Geometri logo Aphodius: kumbang tahi (dung beetle) bergaya low-poly.

Satu sumber kebenaran untuk semua aset logo. Dari sini dihasilkan:
  - SVG (mark, app icon, lockup)         -> tools/branding/build.py
  - VectorDrawable Android (ikon launcher)-> tools/branding/build.py

Kanvas 512 x 512, kumbang menghadap ke atas, simetris terhadap x = 256.
Setiap bagian tubuh berupa jaring segitiga 3D (x, y, z). Warna tiap segitiga
dihitung dari arah normalnya terhadap cahaya dari kiri-atas, sehingga tampilan
faceted (polygon) konsisten tanpa pewarnaan manual per segitiga.
"""
import math

W = 512
AXIS = W / 2


# ---------- vektor kecil ----------
def sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def norm(v):
    length = math.sqrt(sum(x * x for x in v)) or 1.0
    return tuple(x / length for x in v)


def mir(v):
    return (W - v[0], v[1], v[2])


# ---------- warna ----------
def _h2r(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _r2h(c):
    return "#%02X%02X%02X" % tuple(max(0, min(255, round(v))) for v in c)


def ramp(stops):
    """stops: [(posisi 0..1, '#RRGGBB'), ...] -> fungsi t -> hex"""
    def fn(t):
        t = max(0.0, min(1.0, t))
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if t <= p1:
                u = (t - p0) / (p1 - p0) if p1 > p0 else 0.0
                a, b = _h2r(c0), _h2r(c1)
                return _r2h(tuple(x + (y - x) * u for x, y in zip(a, b)))
        return stops[-1][1]
    return fn


# Palet cangkang: biru laut -> aqua -> kilau
SHELL = ramp([(0.0, "#0A4A75"), (0.30, "#0A74B3"), (0.58, "#0FA3DE"), (0.82, "#3CD8F2"), (1.0, "#C6F5FF")])
# Palet pelindung dada (pronotum): sedikit lebih hangat ke arah emas scarab
GOLD = ramp([(0.0, "#8A540E"), (0.32, "#C47A18"), (0.60, "#F2A33A"), (0.84, "#FFC766"), (1.0, "#FFEBC2")])
LEG = ramp([(0.0, "#08436B"), (0.5, "#0A6AA8"), (1.0, "#2AA6D6")])

LIGHT = norm((-0.40, -0.52, 0.76))
FLAT = dot((0.0, 0.0, 1.0), LIGHT)


def shade(tri, palette, bias=0.0, gain=1.25):
    a, b, c = tri
    n = cross(sub(b, a), sub(c, a))
    if n[2] < 0:
        n = tuple(-x for x in n)
    n = norm(n)
    t = 0.5 + (dot(n, LIGHT) - FLAT) * gain + bias
    return palette(t)


# ---------- jaring bagian tubuh ----------
def _mesh(verts, tris):
    return [tuple(verts[k] for k in t) for t in tris]


def _both_sides(tris):
    """Cerminkan jaring sisi kiri ke sisi kanan."""
    out = list(tris)
    for a, b, c in tris:
        out.append((mir(a), mir(b), mir(c)))
    return out


# Kepala (clypeus bergerigi), dilihat dari atas
_HEAD_V = {
    "h0": (256, 80, 0), "h1": (234, 66, 0), "h2": (204, 74, 0), "h3": (182, 100, 0),
    "h4": (182, 130, 0), "h5": (210, 152, 0), "h6": (256, 156, 0),
    "hi": (216, 110, 16), "hc": (256, 112, 24),
}
_HEAD_T = [("h0", "h1", "hi"), ("h1", "h2", "hi"), ("h2", "h3", "hi"), ("h3", "h4", "hi"),
           ("h0", "hi", "hc"), ("hi", "h4", "hc"), ("h4", "h5", "hc"), ("h5", "h6", "hc")]

# Pelindung dada (pronotum)
_PRO_V = {
    "p0": (256, 158, 0), "p1": (212, 160, 0), "p2": (176, 180, 0), "p3": (166, 212, 0),
    "p4": (186, 242, 0), "p5": (256, 250, 0),
    "pi": (214, 198, 24), "pc": (256, 204, 32),
}
_PRO_T = [("p0", "p1", "pi"), ("p1", "p2", "pi"), ("p2", "p3", "pi"), ("p3", "p4", "pi"),
          ("p0", "pi", "pc"), ("pi", "p4", "pc"), ("p4", "p5", "pc")]

# Sayap keras (elytra) — satu sisi, ada celah 12px di tengah
_ELY_V = {
    "a0": (250, 260, 0), "a1": (198, 258, 0), "a2": (154, 290, 0), "a3": (138, 346, 0),
    "a4": (150, 404, 0), "a5": (188, 446, 0), "a6": (232, 474, 0), "a7": (250, 484, 0),
    "i1": (206, 304, 26), "i2": (192, 368, 34), "i3": (226, 412, 28), "i4": (234, 340, 26),
}
_ELY_T = [("a0", "a1", "i1"), ("a1", "a2", "i1"), ("a2", "a3", "i2"), ("a2", "i1", "i2"),
          ("a0", "i1", "i4"), ("i1", "i2", "i4"), ("a3", "a4", "i2"), ("i2", "a4", "i3"),
          ("i2", "i3", "i4"), ("a0", "i4", "a7"), ("i4", "i3", "a7"), ("i3", "a4", "a5"),
          ("i3", "a5", "a6"), ("i3", "a6", "a7")]


# ---------- kaki & antena: poligon dari polyline bertaper ----------
def _normal(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    length = math.hypot(dx, dy) or 1.0
    return (-dy / length, dx / length)


def limb_edges(points, widths):
    """Dua sisi (kiri, kanan) di sekeliling polyline, dengan sambungan miter."""
    n = len(points)
    left, right = [], []
    for i, (x, y) in enumerate(points):
        if i == 0:
            nx, ny = _normal(points[0], points[1])
            m = 1.0
        elif i == n - 1:
            nx, ny = _normal(points[i - 1], points[i])
            m = 1.0
        else:
            na, nb = _normal(points[i - 1], points[i]), _normal(points[i], points[i + 1])
            sx, sy = na[0] + nb[0], na[1] + nb[1]
            length = math.hypot(sx, sy) or 1.0
            nx, ny = sx / length, sy / length
            m = 1.0 / max(0.5, nx * na[0] + ny * na[1])
        h = widths[i] / 2 * m
        left.append((x + nx * h, y + ny * h))
        right.append((x - nx * h, y - ny * h))
    return left, right


def limb(points, widths):
    left, right = limb_edges(points, widths)
    return left + right[::-1]


def limb_facets(points, widths, tones):
    """Kaki berfaset: tiap ruas dibelah dua segitiga bernada beda."""
    left, right = limb_edges(points, widths)
    out = []
    for k in range(len(points) - 1):
        out.append(([left[k], left[k + 1], right[k + 1]], tones[(2 * k) % len(tones)]))
        out.append(([left[k], right[k + 1], right[k]], tones[(2 * k + 1) % len(tones)]))
    return out


def mirror_poly(poly):
    return [(W - x, y) for x, y in poly]


# Kaki kiri (dicerminkan ke kanan). Kaki depan lebih lebar untuk menggali.
_LEGS_LEFT = [
    ([(204, 186), (152, 168), (102, 196), (80, 240)], [18, 18, 14, 3]),   # depan
    ([(178, 310), (116, 294), (84, 330), (66, 380)], [16, 16, 12, 3]),     # tengah
    ([(180, 402), (126, 418), (100, 454), (90, 492)], [16, 16, 12, 3]),    # belakang
]
# antena: tangkai + gada 3 helai
_ANT_STALK = ([(198, 100), (172, 78)], [7, 7])
_ANT_CLUB = [
    [(172, 78), (148, 66), (158, 82)],
    [(172, 78), (146, 80), (160, 94)],
    [(172, 78), (152, 94), (168, 100)],
]


def parts(gold_pronotum=True):
    """Daftar (poligon[(x,y)...], warna) dari belakang ke depan (urutan gambar)."""
    out = []
    # kaki
    for pts, w in _LEGS_LEFT:
        for poly, col in limb_facets(pts, w, [LEG(0.72), LEG(0.42)]):
            out.append((poly, col))
            out.append((mirror_poly(poly), LEG(0.30) if col == LEG(0.72) else LEG(0.55)))
    # antena
    sp = limb(*_ANT_STALK)
    out.append((sp, LEG(0.7)))
    out.append((mirror_poly(sp), LEG(0.45)))
    for i, t in enumerate(_ANT_CLUB):
        out.append((t, SHELL(0.86 - i * 0.12)))
        out.append((mirror_poly(t), SHELL(0.62 - i * 0.12)))

    # sayap keras
    for tri in _both_sides(_mesh(_ELY_V, _ELY_T)):
        out.append(([(x, y) for x, y, _ in tri], shade(tri, SHELL)))
    # kepala
    for tri in _both_sides(_mesh(_HEAD_V, _HEAD_T)):
        out.append(([(x, y) for x, y, _ in tri], shade(tri, SHELL, bias=0.06)))
    # pronotum
    pal = GOLD if gold_pronotum else SHELL
    for tri in _both_sides(_mesh(_PRO_V, _PRO_T)):
        out.append(([(x, y) for x, y, _ in tri], shade(tri, pal)))
    return out


def bbox(items):
    xs = [x for poly, _ in items for x, _ in poly]
    ys = [y for poly, _ in items for _, y in poly]
    return min(xs), min(ys), max(xs), max(ys)
