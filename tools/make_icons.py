"""Build the GuardApp launcher icon set from the official MinSU seal.

The seal is the university's own artwork, downloaded from minsu.edu.ph, not a redrawing.
It ships at 200px, which is the largest MinSU publishes; that is enough, because the ring
text is illegible at every size a launcher actually renders.

Adaptive icons are two layers the launcher masks and parallaxes independently, so the seal
must sit inside the 66/108 safe circle — anything outside it can be cropped to any shape
the OEM likes. The legacy PNGs are pre-shaped instead: nothing masks them, so they carry
their own rounded square / circle.
"""
from PIL import Image, ImageDraw

SRC = "minsu_logo.png"
RES = "D:/MINSU/Projects/GuardApp/app/src/main/res"
GREEN = (0, 88, 37, 255)  # Brand, #005825 — the same green as the app's primary.

seal = Image.open(SRC).convert("RGBA")

# 108dp adaptive canvas, per density.
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# 48dp legacy icon, per density.
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# Every OEM mask fits inside the middle 72dp of the 108dp canvas, and the 66dp circle is the
# part guaranteed to survive all of them. 63% (≈68dp) fills a circular mask with a thin green
# margin and still keeps the seal's ring clear of the tightest crop.
SAFE = 0.63


def fitted(size, fraction):
    """The seal, centred on a transparent square of `size`, occupying `fraction` of it."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = max(1, int(round(size * fraction)))
    art = seal.resize((d, d), Image.LANCZOS)
    canvas.paste(art, ((size - d) // 2, (size - d) // 2), art)
    return canvas


def rounded(size, radius_fraction):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_fraction), fill=255
    )
    return mask


def circle(size):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


written = []
for density, size in ADAPTIVE.items():
    fg = fitted(size, SAFE)
    path = f"{RES}/mipmap-{density}/ic_launcher_foreground.webp"
    fg.save(path, "WEBP", lossless=True)
    written.append(path)

for density, size in LEGACY.items():
    plate = Image.new("RGBA", (size, size), GREEN)
    # Legacy icons are not masked by the launcher, so the seal can run wider than the
    # adaptive safe circle — 78% keeps a green margin without looking like a sticker.
    plate.alpha_composite(fitted(size, 0.78))

    square = plate.copy()
    square.putalpha(rounded(size, 0.22))
    square.save(f"{RES}/mipmap-{density}/ic_launcher.webp", "WEBP", lossless=True)

    round_ = plate.copy()
    round_.putalpha(circle(size))
    round_.save(f"{RES}/mipmap-{density}/ic_launcher_round.webp", "WEBP", lossless=True)
    written += [
        f"{RES}/mipmap-{density}/ic_launcher.webp",
        f"{RES}/mipmap-{density}/ic_launcher_round.webp",
    ]

# Play Store listing icon: 512x512, no transparency, no rounding — Google applies its own.
store = Image.new("RGBA", (512, 512), GREEN)
store.alpha_composite(fitted(512, 0.78))
store.convert("RGB").save(
    "D:/MINSU/Projects/GuardApp/.docs/play_store_icon.png", "PNG"
)

# The seal on its own, for in-app use (login, about). nodpi: one asset, scaled by Compose.
seal.resize((512, 512), Image.LANCZOS).save(f"{RES}/drawable-nodpi/logo_minsu_seal.png", "PNG")

print(f"wrote {len(written)} icon files + play store icon + in-app seal")
