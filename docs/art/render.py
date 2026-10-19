#!/usr/bin/env python3
# ruff: noqa: E501  (mostly inline HTML and SVG)
"""Renders the README graphics (docs/assets/*.png) from HTML, in the app's own colors and type.

Same approach as Handheld Forge's art: Playwright + Chromium + the Inter font, with real app
screenshots from CI's emulator run (ci/screens.sh, published to the ci-screens/api-36 branch).

    SCREENS=/path/to/screens python3 docs/art/render.py
"""
import base64
import os
import pathlib
import random

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "assets"
SCREENS = pathlib.Path(os.environ.get("SCREENS", "screens"))
INTER = pathlib.Path(os.environ.get("INTER_DIR", "/usr/share/fonts/opentype/inter"))

NIGHT, SLATE, IRON, IRON_HI, SEAM = "#14181D", "#1B2026", "#242A32", "#2C333C", "#353D47"
ASH, BONE, AURORA_A, AURORA_B = "#98A2AE", "#ECEFF2", "#6FD3FF", "#8B7CFF"
FS = {"exfat": "#4FC3A1", "fat": "#6FA8FF", "ext4": "#F5B83D", "swap": "#7D8896", "ntfs": "#8B7CFF"}


def font_face():
    faces = []
    for weight, name in [(400, "Inter-Regular"), (500, "Inter-Medium"), (600, "Inter-SemiBold"), (700, "Inter-Bold")]:
        data = base64.b64encode((INTER / f"{name}.otf").read_bytes()).decode()
        faces.append(f"@font-face{{font-family:Inter;font-weight:{weight};src:url(data:font/otf;base64,{data})}}")
    for weight, name in [(600, "InterDisplay-SemiBold"), (700, "InterDisplay-Bold"), (800, "InterDisplay-ExtraBold")]:
        data = base64.b64encode((INTER / f"{name}.otf").read_bytes()).decode()
        faces.append(f"@font-face{{font-family:InterDisplay;font-weight:{weight};src:url(data:font/otf;base64,{data})}}")
    return "\n".join(faces)


def screen(name):
    data = base64.b64encode((SCREENS / f"{name}.png").read_bytes()).decode()
    return f"data:image/png;base64,{data}"


BASE_CSS = f"""
{{FONTS}}
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{background:transparent}}
body{{font-family:Inter,sans-serif;color:{BONE};-webkit-font-smoothing:antialiased}}
.aurora{{background:linear-gradient(90deg,{AURORA_A},{AURORA_B});-webkit-background-clip:text;background-clip:text;color:transparent}}
.phone{{background:#0A0D11;border-radius:46px;padding:11px;box-shadow:0 0 0 1.5px #3A424D,0 30px 60px rgba(0,0,0,.55),0 8px 18px rgba(0,0,0,.35)}}
.phone img{{display:block;border-radius:36px;width:100%}}
"""

# The app icon: a drive split into partitions, the first one lit (same shapes as the launcher icon).
MARK = f"""
<svg viewBox="23 36 62 36" xmlns="http://www.w3.org/2000/svg">
  <defs><linearGradient id="m" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{AURORA_A}"/><stop offset="1" stop-color="{AURORA_B}"/></linearGradient></defs>
  <path fill="url(#m)" d="M30,38 h22 v32 h-22 a5,5 0 0 1 -5,-5 v-22 a5,5 0 0 1 5,-5 z"/>
  <path fill="#3A4350" d="M55,38 h11 v32 h-11 z"/>
  <path fill="#5A6574" d="M69,38 h9 a5,5 0 0 1 5,5 v22 a5,5 0 0 1 -5,5 h-9 z"/>
</svg>"""


def stars(n, w, h, seed=7):
    """Cold specks drifting up from the lower left, the aurora's answer to Forge's embers."""
    r = random.Random(seed)
    dots = []
    for _ in range(n):
        x, y = r.uniform(0, w * 0.6), r.uniform(h * 0.3, h)
        s = r.uniform(1.0, 3.0)
        o = r.uniform(0.15, 0.6) * (y / h)
        c = AURORA_A if r.random() < 0.55 else AURORA_B
        dots.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{s:.1f}" fill="{c}" opacity="{o:.2f}"/>')
    return f'<svg width="{w}" height="{h}" style="position:absolute;inset:0">{"".join(dots)}</svg>'


def disk_bar(width):
    """A partition map like the app's: EFI, swap, Linux, games, free space."""
    parts = [(0.08, FS["fat"]), (0.1, FS["swap"]), (0.28, FS["ext4"]), (0.4, FS["exfat"]), (0.14, None)]
    segs = []
    for frac, color in parts:
        style = f"background:{color}" if color else f"background:repeating-linear-gradient(135deg,{IRON} 0 7px,{SEAM} 7px 9px)"
        segs.append(f'<div style="flex:{frac};{style};border-radius:7px"></div>')
    return f'<div style="display:flex;gap:4px;width:{width}px;height:30px;padding:4px;border-radius:12px;background:{NIGHT};box-shadow:inset 0 0 0 1.5px {SEAM}">{"".join(segs)}</div>'


def banner(w=1280, h=440, social=False):
    chips = "".join(f'<span class="chip">{c}</span>' for c in ["GPT &amp; MBR", "exFAT", "FAT32", "Linux swap", "SMART", "No root for USB"])
    return f"""
<style>
.card{{position:relative;width:{w}px;height:{h}px;border-radius:{0 if social else 28}px;overflow:hidden;
  background:radial-gradient(900px 520px at 8% 115%,rgba(139,124,255,.34),transparent 60%),
             radial-gradient(700px 420px at 32% 120%,rgba(111,211,255,.22),transparent 60%),
             linear-gradient(160deg,#1C2229,{SLATE} 55%,#12161A)}}
.grid{{position:absolute;inset:0;background-image:linear-gradient(rgba(255,255,255,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.035) 1px,transparent 1px);background-size:40px 40px;
  -webkit-mask-image:linear-gradient(90deg,rgba(0,0,0,.9),transparent 70%)}}
.left{{position:absolute;left:72px;top:{(h - 330) // 2}px;width:640px}}
.mark{{width:118px;height:68px;filter:drop-shadow(0 0 22px rgba(111,211,255,.45))}}
h1{{font-family:InterDisplay;font-weight:800;font-size:{70 if social else 64}px;letter-spacing:-1.5px;line-height:1.02;margin-top:22px}}
.tag{{color:{ASH};font-size:{24 if social else 21}px;line-height:1.4;margin-top:14px;max-width:580px}}
.chips{{margin-top:24px;display:flex;flex-wrap:wrap;gap:10px}}
.chip{{font-size:15px;font-weight:600;color:{BONE};padding:8px 15px;border-radius:999px;background:rgba(36,42,50,.85);box-shadow:inset 0 0 0 1.5px {SEAM}}}
.phones{{position:absolute;right:{40 if social else 30}px;top:{70 if social else 52}px;width:560px;height:{h}px}}
.phones .phone{{position:absolute;width:236px}}
.p1{{left:0;top:70px;transform:rotate(-9deg);opacity:.92}}
.p2{{left:162px;top:0;z-index:2}}
.p3{{left:326px;top:70px;transform:rotate(9deg);opacity:.92}}
</style>
<div class="card" id="shot">
  <div class="grid"></div>
  {stars(80, w, h)}
  <div class="left">
    <div class="mark">{MARK}</div>
    <h1>Raven <span class="aurora">Disk Utility</span></h1>
    <p class="tag">Partition, format and check USB drives, SSDs and hard drives from your Android phone.</p>
    <div class="chips">{chips}</div>
  </div>
  <div class="phones">
    <div class="phone p1"><img src="{screen('review')}"></div>
    <div class="phone p2"><img src="{screen('device')}"></div>
    <div class="phone p3"><img src="{screen('done')}"></div>
  </div>
</div>"""


ICONS = {
    # 48x48 line icons, drawn with the aurora gradient.
    "map": '<rect x="4" y="16" width="40" height="16" rx="4"/><path d="M15 16v16M27 16v16M35 16v16"/>',
    "partition": '<rect x="5" y="9" width="38" height="30" rx="4"/><path d="M5 24h38M24 9v30"/>',
    "format": '<path d="M36 5L25 23"/><path d="M17 21l12 7-3 14H11l-5-8z"/><path d="M13 35l3 7M19 33l2 9"/>',
    "label": '<path d="M6 9h18l18 18-15 15L9 24V9z"/><circle cx="16" cy="17" r="3"/>',
    "erase": '<path d="M8 33l18-18 14 14-12 12H14z"/><path d="M19 22l14 14"/><path d="M6 43h36"/>',
    "health": '<path d="M24 41S6 30 6 17a9 9 0 0 1 18-3 9 9 0 0 1 18 3c0 13-18 24-18 24z"/><path d="M12 24h7l3-6 4 11 3-5h7"/>',
    "review": '<rect x="8" y="5" width="32" height="38" rx="4"/><path d="M15 15h18M15 23h18M15 31h10"/><path d="M28 34l3 3 6-7"/>',
    "verify": '<path d="M24 5l15 6v10c0 10-6.5 17-15 21-8.5-4-15-11-15-21V11z"/><path d="M17 24l5 5 9-10"/>',
    "usb": '<rect x="6" y="15" width="26" height="18" rx="4"/><rect x="32" y="19" width="10" height="10" rx="1.5"/><path d="M36 22v4"/>',
    "honest": '<circle cx="24" cy="24" r="18"/><path d="M24 22v11"/><circle cx="24" cy="15.5" r="1.6"/>',
    "log": '<path d="M12 5h18l8 8v28a2 2 0 0 1-2 2H12a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"/><path d="M16 22h16M16 29h16M16 36h9"/>',
    "safe": '<rect x="9" y="21" width="30" height="20" rx="4"/><path d="M15 21v-6a9 9 0 0 1 18 0v6"/><circle cx="24" cy="31" r="2.5"/>',
}


def icon(name, size=96):
    return f"""
<div id="shot" style="width:{size}px;height:{size}px;border-radius:{size * 0.26}px;background:linear-gradient(160deg,{IRON_HI},{IRON});
  box-shadow:inset 0 0 0 1.5px {SEAM};display:flex;align-items:center;justify-content:center">
  <svg width="{size * 0.56}" height="{size * 0.56}" viewBox="0 0 48 48" fill="none" stroke="url(#g)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{AURORA_A}"/><stop offset="1" stop-color="{AURORA_B}"/></linearGradient></defs>
    {ICONS[name]}
  </svg>
</div>"""


def phone(name, width=300):
    return f'<div id="shot" style="padding:24px 26px 44px"><div class="phone" style="width:{width}px"><img src="{screen(name)}"></div></div>'


def flow(w=1280):
    steps = [
        ("Plug in", "Tap Allow once. Raven reads the drive: table, partitions, filesystems, health."),
        ("Plan", "Create, format, resize, label. Changes queue up; nothing is written yet."),
        ("Review", "Before and after, what data goes away, and an explicit OK."),
        ("Write &amp; verify", "Same-drive check, then the writes, then everything read back."),
    ]
    cards = "".join(
        f'<div class="step"><div class="num">{i + 1}</div><h3>{t}</h3><p>{d}</p></div>' for i, (t, d) in enumerate(steps)
    )
    return f"""
<style>
.wrap{{width:{w}px;padding:34px 40px;border-radius:28px;background:linear-gradient(160deg,#1C2229,{SLATE});position:relative}}
.line{{position:absolute;left:74px;width:916px;top:62px;height:3px;background:linear-gradient(90deg,{AURORA_A},{AURORA_B});opacity:.55;border-radius:2px}}
.steps{{display:grid;grid-template-columns:repeat(4,1fr);gap:22px;position:relative}}
.step{{text-align:left;padding:0 6px}}
.num{{width:56px;height:56px;border-radius:50%;background:linear-gradient(135deg,{AURORA_A},{AURORA_B});color:#0E1116;font-family:InterDisplay;
  font-weight:800;font-size:26px;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 7px {SLATE},0 0 26px rgba(111,211,255,.40)}}
h3{{font-family:InterDisplay;font-weight:700;font-size:25px;margin-top:20px}}
p{{color:{ASH};font-size:17px;line-height:1.45;margin-top:8px}}
</style>
<div class="wrap" id="shot"><div class="line"></div><div class="steps">{cards}</div></div>"""


def render(page, html, out, transparent=True):
    css = BASE_CSS.replace("{FONTS}", FONTS)
    page.set_content(f"<!doctype html><html><head><meta charset='utf-8'><style>{css}</style></head><body>{html}</body></html>")
    page.wait_for_timeout(150)
    page.locator("#shot").screenshot(path=str(out), omit_background=transparent)
    print("wrote", out.relative_to(ROOT))


SCREEN_SHOTS = ["dashboard", "device", "hdd-scrolled", "create", "review", "running", "done", "capabilities"]


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    FONTS = font_face()
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(viewport={"width": 1400, "height": 900}, device_scale_factor=2)
        render(page, banner(), OUT / "banner.png")
        page.set_viewport_size({"width": 1400, "height": 800})
        render(page, banner(1280, 640, social=True), OUT / "social-preview.png", transparent=False)
        render(page, flow(), OUT / "how-it-works.png")
        render(page, f'<div id="shot" style="padding:8px">{disk_bar(1100)}</div>', OUT / "disk-bar.png")
        for name in ICONS:
            render(page, icon(name), OUT / f"icon-{name}.png")
        page.set_viewport_size({"width": 500, "height": 900})
        for name in SCREEN_SHOTS:
            render(page, phone(name), OUT / f"screen-{name}.png")
        b.close()
