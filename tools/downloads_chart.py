#!/usr/bin/env python3
"""Membuat grafik unduhan per versi (docs/downloads.svg) dari data rilis GitHub."""
import json, os, urllib.request

REPO = os.environ.get("GITHUB_REPOSITORY", "efendiyhy/Aphodius")
req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases?per_page=100")
req.add_header("Accept", "application/vnd.github+json")
if os.environ.get("GITHUB_TOKEN"):
    req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
rel = json.load(urllib.request.urlopen(req))
rows = sorted(
    ((r["tag_name"], sum(a["download_count"] for a in r["assets"])) for r in rel if not r.get("draft")),
    key=lambda x: [int(p) if p.isdigit() else 0 for p in x[0].lstrip("v").split(".")],
)[-12:]
total = sum(n for _, n in rows)
W, H, L, T, B = 760, 330, 56, 78, 46
mx = max([n for _, n in rows] + [4])
step = max(1, -(-mx // 4))
top = step * 4
bw = (W - L - 30) / max(len(rows), 1)
o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Segoe UI,Helvetica,Arial,sans-serif">',
     f'<rect width="{W}" height="{H}" rx="18" fill="#07131F"/>',
     '<text x="28" y="40" fill="#FFFFFF" font-size="20" font-weight="700">Unduhan Aphodius per versi</text>',
     f'<text x="28" y="62" fill="#8FA6B8" font-size="13">Total {total} unduhan • diperbarui otomatis</text>']
for i in range(5):
    y = T + (H - T - B) * (1 - i / 4)
    o.append(f'<line x1="{L}" y1="{y:.1f}" x2="{W-20}" y2="{y:.1f}" stroke="#16314A" stroke-width="1"/>')
    o.append(f'<text x="{L-10}" y="{y+4:.1f}" fill="#8FA6B8" font-size="11" text-anchor="end">{step*i}</text>')
for i, (tag, n) in enumerate(rows):
    h = (H - T - B) * n / top
    x = L + i * bw + bw * 0.18
    w = bw * 0.64
    y = H - B - h
    o.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{max(h,2):.1f}" rx="5" fill="#22C7EC"/>')
    o.append(f'<text x="{x+w/2:.1f}" y="{y-7:.1f}" fill="#FFFFFF" font-size="12" font-weight="700" text-anchor="middle">{n}</text>')
    o.append(f'<text x="{x+w/2:.1f}" y="{H-B+20}" fill="#8FA6B8" font-size="11" text-anchor="middle">{tag}</text>')
o.append("</svg>")
open(os.path.join(os.path.dirname(__file__), "..", "docs", "downloads.svg"), "w").write("\n".join(o))
print("ok", total)
