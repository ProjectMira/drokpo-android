#!/usr/bin/env python3
"""Regenerate Drokpo's Android brand assets from the shared handshake glyph.

Android port of drokpo-app/ci/make_brand_assets.py — same brand: YouTube red
`#FF0000` ground, handshake in Facebook blue `#1877F2` (the app accent, see
ui/theme/Color.kt) with a thin white halo so the glyph stays legible on the
red. No heart badge.

The glyph source is ci/assets/handshake_glyph.png, a grayscale mask copied
verbatim from drokpo-app/ci/assets/. Do NOT re-extract it from logo.png —
that file's alpha is a rounded red tile, not the handshake.

Every raster is drawn once at a large master size and LANCZOS-downscaled, so
the halo keeps the iOS proportion (1.5% of the glyph width) at every density
instead of being clamped to a 3px minimum on the small mipmaps.

Outputs (paths relative to the Android repo root):
  app/src/main/res/mipmap-*dpi/ic_launcher_foreground.png   adaptive fg, 108dp
  app/src/main/res/mipmap-*dpi/ic_launcher_monochrome.png   themed-icon layer
  app/src/main/res/drawable-nodpi/logo.png                  sign-in logo (= iOS Logo.png)
  app/src/main/ic_launcher-playstore.png                    512px Play listing icon
  app/src/main/res/drawable/ic_stat_notification.xml        traced vector, white
  app/src/main/res/drawable/ic_splash_handshake.xml         traced vector, splash icon

Usage (from a venv with Pillow + potracer installed, run from the repo root):
    python3 -m venv .venv-brand && .venv-brand/bin/pip install pillow potracer
    .venv-brand/bin/python ci/make_brand_assets.py
"""

import math
from pathlib import Path

import numpy as np
import potrace
from PIL import Image, ImageDraw, ImageFilter

REPO_ROOT = Path(__file__).resolve().parent.parent
GLYPH_PATH = REPO_ROOT / "ci/assets/handshake_glyph.png"
RES = REPO_ROOT / "app/src/main/res"
PLAYSTORE_PATH = REPO_ROOT / "app/src/main/ic_launcher-playstore.png"
LOGO_PATH = RES / "drawable-nodpi/logo.png"
NOTIFICATION_PATH = RES / "drawable/ic_stat_notification.xml"
SPLASH_PATH = RES / "drawable/ic_splash_handshake.xml"

RED = (255, 0, 0, 255)        # #FF0000 — YouTube red, the brand ground
BLUE = (24, 119, 242, 255)    # #1877F2 — Facebook blue, the app accent
WHITE = (255, 255, 255, 255)  # halo / monochrome

CORNER_RADIUS_RATIO = 0.224   # iOS-style rounded tile for the in-app logo
SUPERSAMPLE = 4               # draw the tile at 4x, LANCZOS down for AA corners

# Adaptive icons: 108dp layers, the launcher mask shows the centre 72dp and
# only a 66dp-diameter circle is guaranteed visible on every launcher shape.
ADAPTIVE_DP = 108
VISIBLE_DP = 72
SAFE_ZONE_DIAMETER_DP = 66
# iOS sizes the glyph at 61% of the visible tile; keep the same proportion
# against the 72dp visible area so the launcher icon reads like the iOS one.
GLYPH_WIDTH_OF_VISIBLE = 0.61
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
MASTER_PX_PER_DP = 16         # 1728px master canvas, downscaled per density


def load_glyph_mask() -> Image.Image:
    """The committed handshake mask, tightly cropped, grayscale."""
    mask = Image.open(GLYPH_PATH).convert("L")
    return mask.crop(mask.getbbox())


def scaled_mask(target_width: int) -> Image.Image:
    mask = load_glyph_mask()
    scale = target_width / mask.width
    return mask.resize((target_width, round(mask.height * scale)), Image.LANCZOS)


def haloed_glyph(target_width: int) -> Image.Image:
    """Blue handshake over a thin white halo, as an RGBA layer.

    The halo is the mask dilated with MaxFilter (kernel must be odd).
    The mask is padded first so the dilation doesn't clip at the edges
    of the bbox-tight mask.
    """
    mask = scaled_mask(target_width)

    halo_px = max(3, round(target_width * 0.015))
    pad = halo_px + 2
    padded = Image.new("L", (mask.width + 2 * pad, mask.height + 2 * pad), 0)
    padded.paste(mask, (pad, pad))
    halo_mask = padded.filter(ImageFilter.MaxFilter(2 * halo_px + 1))

    layer = Image.new("RGBA", padded.size, (0, 0, 0, 0))
    halo = Image.new("RGBA", padded.size, WHITE)
    halo.putalpha(halo_mask)
    glyph = Image.new("RGBA", padded.size, BLUE)
    glyph.putalpha(padded)
    layer.alpha_composite(halo)
    layer.alpha_composite(glyph)
    return layer


def white_glyph(target_width: int) -> Image.Image:
    """Bare white handshake (no halo) — the halo would fill the finger gaps
    in a single-colour rendering and blur the silhouette."""
    mask = scaled_mask(target_width)
    layer = Image.new("RGBA", mask.size, WHITE)
    layer.putalpha(mask)
    return layer


def centered(canvas_size: int, layer: Image.Image) -> tuple:
    return ((canvas_size - layer.width) // 2, (canvas_size - layer.height) // 2)


def max_radius_dp(layer_canvas: Image.Image, px_per_dp: float) -> float:
    """Distance (dp) from the canvas centre to the farthest visible pixel."""
    alpha = np.array(layer_canvas.getchannel("A"))
    ys, xs = np.nonzero(alpha > 8)
    cx = cy = (layer_canvas.width - 1) / 2
    return float(np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()) / px_per_dp


def adaptive_layer(make_glyph) -> Image.Image:
    size = ADAPTIVE_DP * MASTER_PX_PER_DP
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer = make_glyph(round(VISIBLE_DP * GLYPH_WIDTH_OF_VISIBLE * MASTER_PX_PER_DP))
    canvas.alpha_composite(layer, centered(size, layer))
    radius = max_radius_dp(canvas, MASTER_PX_PER_DP)
    assert radius <= SAFE_ZONE_DIAMETER_DP / 2, f"glyph leaves the safe zone ({radius:.1f}dp)"
    print(f"  glyph reaches {radius:.1f}dp from centre (safe zone {SAFE_ZONE_DIAMETER_DP / 2}dp)")
    return canvas


def write_density_set(master: Image.Image, name: str) -> None:
    for density, scale in DENSITIES.items():
        px = round(ADAPTIVE_DP * scale)
        out = RES / f"mipmap-{density}" / f"{name}.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        master.resize((px, px), Image.LANCZOS).save(out, optimize=True)
        print(f"wrote {out.relative_to(REPO_ROOT)} ({px}x{px})")


def make_adaptive_icon() -> None:
    print("adaptive foreground:")
    write_density_set(adaptive_layer(haloed_glyph), "ic_launcher_foreground")
    print("adaptive monochrome:")
    write_density_set(adaptive_layer(white_glyph), "ic_launcher_monochrome")


def make_playstore_icon() -> None:
    """512x512 full-bleed red square (Play applies its own mask), glyph at
    the iOS proportion. Drawn at 1024 like the iOS icon, then downscaled."""
    size = 1024
    canvas = Image.new("RGBA", (size, size), RED)
    layer = haloed_glyph(target_width=round(size * 0.61))
    canvas.alpha_composite(layer, centered(size, layer))
    canvas = canvas.resize((512, 512), Image.LANCZOS)
    canvas.save(PLAYSTORE_PATH, optimize=True)
    print(f"wrote {PLAYSTORE_PATH.relative_to(REPO_ROOT)} (512x512)")


def make_logo() -> None:
    """560x560 RGBA: red rounded tile (mini app icon), blue handshake.
    Identical to the iOS Logo.png generator."""
    size = 560
    big = size * SUPERSAMPLE
    tile = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle(
        [0, 0, big - 1, big - 1], radius=round(big * CORNER_RADIUS_RATIO), fill=RED
    )
    canvas = tile.resize((size, size), Image.LANCZOS)

    layer = haloed_glyph(target_width=round(size * 0.63))
    canvas.alpha_composite(layer, centered(size, layer))
    LOGO_PATH.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(LOGO_PATH, optimize=True)
    print(f"wrote {LOGO_PATH.relative_to(REPO_ROOT)} ({canvas.width}x{canvas.height}, RGBA)")


# --- Notification small icon (vector) -------------------------------------

NOTIFICATION_DP = 24
NOTIFICATION_LIVE_DP = 20     # Material system-icon live area (2dp padding)


def fmt(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def xy(pt) -> tuple:
    """potracer points are `_Point(x, y)` objects; normalise to tuples."""
    return (pt.x, pt.y) if hasattr(pt, "x") else (pt[0], pt[1])


def trace_mask(mask: Image.Image):
    """potrace a grayscale mask → closed curves (white = inside) in its pixel space."""
    # potracer traces the *dark* pixels of a bool array after inverting it,
    # so hand it "True where background" to trace the white shape itself.
    data = np.array(mask) < 128
    bitmap = potrace.Bitmap(data)
    return bitmap.trace(turdsize=8, alphamax=1.0, opticurve=True, opttolerance=0.2)


def trace_glyph(work_width: int = 1024):
    """potrace the glyph mask → list of closed curves in mask pixel space."""
    mask = scaled_mask(work_width)
    return mask.size, trace_mask(mask)


def glyph_path_data(curves, size, canvas: float, glyph_width: float, source_width: float = None) -> str:
    """Traced curves → VectorDrawable path data: the traced image (`size`)
    centred on a `canvas`-unit square, scaled so `source_width` pixels (the
    glyph's own width; defaults to the image width) span `glyph_width` units."""
    w, h = size
    scale = glyph_width / (source_width or w)
    ox = (canvas - w * scale) / 2
    oy = (canvas - h * scale) / 2

    def p(pt) -> str:
        x, y = xy(pt)
        return f"{fmt(ox + x * scale)},{fmt(oy + y * scale)}"

    parts = []
    for curve in curves:
        parts.append(f"M{p(curve.start_point)}")
        for seg in curve.segments:
            if seg.is_corner:
                parts.append(f"L{p(seg.c)}L{p(seg.end_point)}")
            else:
                parts.append(f"C{p(seg.c1)} {p(seg.c2)} {p(seg.end_point)}")
        parts.append("Z")
    return "".join(parts)


def make_notification_icon() -> None:
    (w, h), curves = trace_glyph()
    glyph_width = NOTIFICATION_LIVE_DP * w / max(w, h)
    path_data = glyph_path_data(curves, (w, h), NOTIFICATION_DP, glyph_width)

    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by ci/make_brand_assets.py from ci/assets/handshake_glyph.png.
     Status-bar icon: white on transparent (the system tints it); FCM uses it
     as default_notification_icon. Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{NOTIFICATION_DP}dp"
    android:height="{NOTIFICATION_DP}dp"
    android:viewportWidth="{NOTIFICATION_DP}"
    android:viewportHeight="{NOTIFICATION_DP}">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{path_data}" />
</vector>
"""
    NOTIFICATION_PATH.parent.mkdir(parents=True, exist_ok=True)
    NOTIFICATION_PATH.write_text(xml)
    print(f"wrote {NOTIFICATION_PATH.relative_to(REPO_ROOT)} ({len(curves)} contours, {len(path_data)} chars)")
    verify_trace(curves, (w, h))


def make_splash_icon() -> None:
    """Crisp vector of the launcher foreground for the splash screen.

    The splash (icon-with-background variant) draws its icon at 240dp — far
    above the 108dp mipmaps, which would upscale blurrily — so it gets a
    vector: the halo (the glyph mask dilated exactly like haloed_glyph, which
    also fills the narrow finger gaps white) traced and filled white, with the
    traced glyph filled blue on top. Same 108-unit canvas and glyph size as
    the adaptive foreground, matching the splash's 2:3 icon / disc proportion.
    """
    work_width = 1024
    mask = scaled_mask(work_width)
    halo_px = max(3, round(work_width * 0.015))
    pad = halo_px + 2
    padded = Image.new("L", (mask.width + 2 * pad, mask.height + 2 * pad), 0)
    padded.paste(mask, (pad, pad))
    # MaxFilter(2k+1) == k rounds of MaxFilter(3) (square kernel), much faster.
    halo_mask = padded
    for _ in range(halo_px):
        halo_mask = halo_mask.filter(ImageFilter.MaxFilter(3))

    glyph_width = VISIBLE_DP * GLYPH_WIDTH_OF_VISIBLE
    halo_path = glyph_path_data(trace_mask(halo_mask), padded.size, ADAPTIVE_DP, glyph_width, mask.width)
    glyph_path = glyph_path_data(trace_mask(padded), padded.size, ADAPTIVE_DP, glyph_width, mask.width)
    blue = "#FF%02X%02X%02X" % BLUE[:3]

    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by ci/make_brand_assets.py from ci/assets/handshake_glyph.png.
     Splash-screen icon (Theme.Drokpo.Starting): blue handshake with a white
     halo on a transparent 108-unit canvas; the splash draws the red disc
     behind it (windowSplashScreenIconBackgroundColor). Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{ADAPTIVE_DP}dp"
    android:height="{ADAPTIVE_DP}dp"
    android:viewportWidth="{ADAPTIVE_DP}"
    android:viewportHeight="{ADAPTIVE_DP}">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{halo_path}" />
    <path
        android:fillColor="{blue}"
        android:fillType="evenOdd"
        android:pathData="{glyph_path}" />
</vector>
"""
    SPLASH_PATH.parent.mkdir(parents=True, exist_ok=True)
    SPLASH_PATH.write_text(xml)
    print(f"wrote {SPLASH_PATH.relative_to(REPO_ROOT)} ({len(halo_path) + len(glyph_path)} chars of path data)")


def verify_trace(curves, size) -> None:
    """Rasterise the traced curves (even-odd) and compare to the source mask,
    so a bad trace fails loudly instead of shipping a broken status-bar icon."""
    w, h = size
    k = 2  # rasterise at 2x for a fair comparison of anti-aliased edges

    def flatten(curve):
        pts = []
        cur = xy(curve.start_point)
        pts.append(cur)
        for seg in curve.segments:
            if seg.is_corner:
                pts += [xy(seg.c), xy(seg.end_point)]
            else:
                p0, p1, p2, p3 = cur, xy(seg.c1), xy(seg.c2), xy(seg.end_point)
                for i in range(1, 17):
                    t = i / 16
                    mt = 1 - t
                    pts.append((
                        mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
                        mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1],
                    ))
            cur = xy(seg.end_point)
        return [(x * k, y * k) for x, y in pts]

    acc = np.zeros((h * k, w * k), dtype=bool)
    for curve in curves:
        img = Image.new("1", (w * k, h * k), 0)
        ImageDraw.Draw(img).polygon(flatten(curve), fill=1)
        acc ^= np.array(img)
    ref = np.array(scaled_mask(w).resize((w * k, h * k), Image.LANCZOS)) >= 128
    iou = (acc & ref).sum() / max(1, (acc | ref).sum())
    print(f"  trace IoU vs mask: {iou:.4f}")
    assert iou > 0.97, "traced notification icon diverges from the glyph mask"


if __name__ == "__main__":
    make_adaptive_icon()
    make_playstore_icon()
    make_logo()
    make_notification_icon()
    make_splash_icon()
