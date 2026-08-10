#!/usr/bin/env python3
"""
Generate DawaSafe's launcher icons and the status-bar notification PNGs.

Why generate instead of shipping binaries: the icons must be reproducible and
reviewable. A committed .png is opaque to code review; this script is not.

Two families are produced.

1. mipmap-*/ic_launcher.png and ic_launcher_round.png
   The home-screen icon. Full colour, drawn on the app's teal, with the safe
   zone respected so the round mask on Samsung/Xiaomi launchers does not clip
   the capsule.

2. drawable-*/ic_stat_dawasafe.png
   The status-bar icon, WHITE-ON-TRANSPARENT. Android repaints every opaque
   pixel white, so this is drawn as an outline: a filled shape would silhouette
   into a blob. This exists alongside the vector because RemoteViews on API
   21-23 cannot reliably inflate a VectorDrawable, and a notification icon that
   fails to inflate renders as nothing at all - i.e. a dose alarm the user never
   sees.
"""
import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "app", "src", "main", "res")

BRAND = (15, 118, 110, 255)        # #0F766E, the app's own header teal
BRAND_DEEP = (11, 90, 84, 255)
WHITE = (255, 255, 255, 255)

# Launcher densities. Android picks by screen density; all five must exist or a
# high-DPI phone upscales the mdpi asset and the icon looks soft.
LAUNCHER = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# Status-bar icons are 24dp; the px size is 24 * density-scale.
STATUS = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}

SS = 8  # supersampling factor - draw big, downscale with LANCZOS for clean edges


def capsule_mask(size, stroke_ratio, rotate=-45):
    """A pill capsule drawn as an OUTLINE, returned as an alpha mask.

    Drawn on a transparent canvas at `size`x`size`, rotated so it reads as a
    tilted capsule rather than a rounded rectangle (which at 24dp is easily
    mistaken for a battery or a message bubble).
    """
    big = size * SS
    img = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(img)

    w = big * 0.62          # capsule length
    h = big * 0.34          # capsule width
    x0 = (big - w) / 2.0
    y0 = (big - h) / 2.0
    stroke = max(1, int(round(big * stroke_ratio)))
    r = h / 2.0

    d.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=r, outline=255, width=stroke)
    # the divider that makes it read as a two-tone capsule, not a lozenge
    d.line([(big / 2.0, y0), (big / 2.0, y0 + h)], fill=255, width=stroke)

    img = img.rotate(rotate, resample=Image.BICUBIC, expand=False)
    return img.resize((size, size), Image.LANCZOS)


def rounded_bg(size, radius_ratio, top, bottom):
    """Launcher background: a vertical teal gradient with rounded corners."""
    big = size * SS
    grad = Image.new("RGBA", (big, big))
    px = grad.load()
    for y in range(big):
        f = y / float(big - 1)
        px_row = tuple(int(round(top[i] + (bottom[i] - top[i]) * f)) for i in range(4))
        for x in range(big):
            px[x, y] = px_row

    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, big - 1, big - 1], radius=int(big * radius_ratio), fill=255
    )
    grad.putalpha(mask)
    return grad.resize((size, size), Image.LANCZOS)


def circle_bg(size, top, bottom):
    big = size * SS
    grad = Image.new("RGBA", (big, big))
    px = grad.load()
    for y in range(big):
        f = y / float(big - 1)
        row = tuple(int(round(top[i] + (bottom[i] - top[i]) * f)) for i in range(4))
        for x in range(big):
            px[x, y] = row
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, big - 1, big - 1], fill=255)
    grad.putalpha(mask)
    return grad.resize((size, size), Image.LANCZOS)


def launcher_icon(size, round_icon=False):
    bg = circle_bg(size, BRAND, BRAND_DEEP) if round_icon \
        else rounded_bg(size, 0.22, BRAND, BRAND_DEEP)
    # 0.055 stroke keeps the outline visible once the icon is scaled to 48px
    mark = capsule_mask(size, 0.055)
    white = Image.new("RGBA", (size, size), WHITE)
    bg.paste(white, (0, 0), mark)
    return bg


def status_icon(size):
    """White-on-transparent, no background. See module docstring."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mark = capsule_mask(size, 0.075)   # heavier stroke: it must survive 24px
    white = Image.new("RGBA", (size, size), WHITE)
    img.paste(white, (0, 0), mark)
    return img


def adaptive_foreground(size):
    """Foreground layer for API 26+ adaptive icons.

    The launcher may mask this to a circle, squircle, or teardrop and it also
    parallax-shifts, so only the middle 66% is guaranteed visible. The capsule
    is therefore drawn at 66% scale inside a transparent canvas.
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.66)
    mark = capsule_mask(inner, 0.055)
    white = Image.new("RGBA", (inner, inner), WHITE)
    layer = Image.new("RGBA", (inner, inner), (0, 0, 0, 0))
    layer.paste(white, (0, 0), mark)
    off = (size - inner) // 2
    img.paste(layer, (off, off), layer)
    return img


def main():
    made = []
    for dens, px in LAUNCHER.items():
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        launcher_icon(px, False).save(os.path.join(d, "ic_launcher.png"))
        launcher_icon(px, True).save(os.path.join(d, "ic_launcher_round.png"))
        # adaptive foreground is 108dp; the 72dp centre is the safe zone
        fg = int(px * 108 / 48.0)
        adaptive_foreground(fg).save(os.path.join(d, "ic_launcher_foreground.png"))
        made += [f"mipmap-{dens}/ic_launcher.png",
                 f"mipmap-{dens}/ic_launcher_round.png",
                 f"mipmap-{dens}/ic_launcher_foreground.png"]

    for dens, px in STATUS.items():
        d = os.path.join(RES, "drawable-" + dens)
        os.makedirs(d, exist_ok=True)
        status_icon(px).save(os.path.join(d, "ic_stat_dawasafe.png"))
        made.append(f"drawable-{dens}/ic_stat_dawasafe.png")

    for f in made:
        print("  wrote", f)
    print(f"\n{len(made)} icon files generated.")


if __name__ == "__main__":
    main()
