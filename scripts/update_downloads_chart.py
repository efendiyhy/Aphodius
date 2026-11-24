import csv
import json
import os
import urllib.request
from datetime import datetime, timezone

REPO = os.environ.get("GITHUB_REPOSITORY", "efendiyhy/Aphodius")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
CSV_PATH = "docs/downloads_history.csv"
SVG_PATH = "docs/downloads.svg"


def fetch_total_downloads(repo: str, token: str) -> int:
    url = f"https://api.github.com/repos/{repo}/releases?per_page=100"
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "Aphodius-Download-Tracker")
    if token:
        req.add_header("Authorization", f"Bearer {token}")

    total = 0
    with urllib.request.urlopen(req, timeout=30) as resp:
        releases = json.loads(resp.read().decode("utf-8"))
        for rel in releases:
            for asset in rel.get("assets", []):
                if asset.get("name", "").endswith(".apk"):
                    total += int(asset.get("download_count", 0))
    return total


def load_history(csv_path: str):
    rows = []
    if os.path.exists(csv_path):
        with open(csv_path, "r", newline="", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                rows.append((row["date"], int(row["total_downloads"])))
    return rows


def save_history(csv_path: str, rows):
    os.makedirs(os.path.dirname(csv_path), exist_ok=True)
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["date", "total_downloads"])
        for d, count in rows:
            writer.writerow([d, count])


def render_svg(rows, svg_path: str):
    width, height = 760, 340
    pad_l, pad_r, pad_t, pad_b = 70, 36, 56, 56
    plot_w = width - pad_l - pad_r
    plot_h = height - pad_t - pad_b

    if not rows:
        rows = [(datetime.now(timezone.utc).strftime("%Y-%m-%d"), 0)]

    # Keep last 30 points if history grows very large
    display_rows = rows[-30:]
    dates = [r[0] for r in display_rows]
    values = [r[1] for r in display_rows]

    min_v = 0
    max_v = max(values) if max(values) > 0 else 10
    # Add 15% headroom
    top_v = int(max_v * 1.15) + 1

    n = len(display_rows)
    coords = []
    for i, val in enumerate(values):
        x = pad_l + (plot_w / 2 if n == 1 else (i / (n - 1)) * plot_w)
        y = pad_t + plot_h - ((val - min_v) / (top_v - min_v)) * plot_h
        coords.append((x, y, dates[i], val))

    # Build polyline and area path
    line_points = " ".join(f"{x:.1f},{y:.1f}" for x, y, _, _ in coords)
    if n == 1:
        x0, y0, _, _ = coords[0]
        area_path = f"M {pad_l},{pad_t + plot_h} L {x0:.1f},{y0:.1f} L {pad_l + plot_w},{y0:.1f} L {pad_l + plot_w},{pad_t + plot_h} Z"
        line_points = f"{pad_l},{y0:.1f} {pad_l + plot_w},{y0:.1f}"
    else:
        first_x = coords[0][0]
        last_x = coords[-1][0]
        poly_coords = " ".join(f"L {x:.1f},{y:.1f}" for x, y, _, _ in coords)
        area_path = f"M {first_x:.1f},{pad_t + plot_h} {poly_coords} L {last_x:.1f},{pad_t + plot_h} Z"

    # Y-axis ticks (5 steps)
    y_ticks_svg = []
    steps = 4
    for s in range(steps + 1):
        val = int(round((top_v / steps) * s))
        y = pad_t + plot_h - (s / steps) * plot_h
        y_ticks_svg.append(
            f'<line x1="{pad_l}" y1="{y:.1f}" x2="{pad_l + plot_w}" y2="{y:.1f}" stroke="#2A3242" stroke-width="1" stroke-dasharray="4 4"/>'
            f'<text x="{pad_l - 10}" y="{y + 4:.1f}" text-anchor="end" fill="#94A3B8" font-size="11" font-family="system-ui, -apple-system, sans-serif">{val:,}</text>'
        )

    # X-axis labels (show up to 6 evenly spaced dates)
    x_labels_svg = []
    label_indices = sorted(set([0, n - 1] + [int(round(i * (n - 1) / min(5, max(1, n - 1)))) for i in range(min(6, n))]))
    for idx in label_indices:
        x, _, d_str, _ = coords[idx]
        short_d = d_str[5:] if len(d_str) == 10 else d_str  # MM-DD
        x_labels_svg.append(
            f'<text x="{x:.1f}" y="{pad_t + plot_h + 22}" text-anchor="middle" fill="#94A3B8" font-size="11" font-family="system-ui, -apple-system, sans-serif">{short_d}</text>'
        )

    # Data points & latest callout
    dots_svg = []
    for i, (x, y, d_str, val) in enumerate(coords):
        is_last = (i == n - 1)
        r = "5" if is_last else "3.5"
        fill = "#F2A33A" if is_last else "#22C7EC"
        dots_svg.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r}" fill="{fill}" stroke="#0F172A" stroke-width="2"/>')
        if is_last:
            label_x = min(x, pad_l + plot_w - 24)
            dots_svg.append(
                f'<text x="{label_x:.1f}" y="{max(pad_t + 12, y - 12):.1f}" text-anchor="middle" fill="#F8FAFC" font-size="12" font-weight="700" font-family="system-ui, -apple-system, sans-serif">{val:,}</text>'
            )

    latest_total = values[-1]
    latest_date = dates[-1]

    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <defs>
    <linearGradient id="areaGrad" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#22C7EC" stop-opacity="0.32"/>
      <stop offset="100%" stop-color="#22C7EC" stop-opacity="0.0"/>
    </linearGradient>
  </defs>

  <!-- Background Card -->
  <rect width="{width}" height="{height}" rx="14" fill="#0F172A" stroke="#1E293B" stroke-width="1.5"/>

  <!-- Header -->
  <text x="{pad_l}" y="30" fill="#F8FAFC" font-size="15" font-weight="700" font-family="system-ui, -apple-system, sans-serif">Total Unduhan Aphodius dari Waktu ke Waktu</text>
  <text x="{width - pad_r}" y="30" text-anchor="end" fill="#22C7EC" font-size="13" font-weight="600" font-family="system-ui, -apple-system, sans-serif">Total: {latest_total:,} ({latest_date})</text>

  <!-- Grid & Y-Axis -->
  {"".join(y_ticks_svg)}

  <!-- Axes Lines -->
  <line x1="{pad_l}" y1="{pad_t}" x2="{pad_l}" y2="{pad_t + plot_h}" stroke="#334155" stroke-width="1.5"/>
  <line x1="{pad_l}" y1="{pad_t + plot_h}" x2="{pad_l + plot_w}" y2="{pad_t + plot_h}" stroke="#334155" stroke-width="1.5"/>

  <!-- Area & Line -->
  <path d="{area_path}" fill="url(#areaGrad)"/>
  <polyline fill="none" stroke="#22C7EC" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" points="{line_points}"/>

  <!-- Data Points -->
  {"".join(dots_svg)}

  <!-- X-Axis Labels -->
  {"".join(x_labels_svg)}

  <!-- Axis Titles -->
  <text x="{pad_l + plot_w / 2:.1f}" y="{height - 12}" text-anchor="middle" fill="#64748B" font-size="11" font-family="system-ui, -apple-system, sans-serif">Tanggal (UTC)</text>
  <text x="18" y="{pad_t + plot_h / 2:.1f}" text-anchor="middle" fill="#64748B" font-size="11" font-family="system-ui, -apple-system, sans-serif" transform="rotate(-90 18 {pad_t + plot_h / 2:.1f})">Total Unduhan</text>
</svg>
"""
    os.makedirs(os.path.dirname(svg_path), exist_ok=True)
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(svg)


def main():
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    total = fetch_total_downloads(REPO, TOKEN)
    history = load_history(CSV_PATH)

    if history and history[-1][0] == today:
        history[-1] = (today, total)
    else:
        history.append((today, total))

    save_history(CSV_PATH, history)
    render_svg(history, SVG_PATH)
    print(f"Updated {SVG_PATH} with {total} total downloads on {today}.")


if __name__ == "__main__":
    main()
