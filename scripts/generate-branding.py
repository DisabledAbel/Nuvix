#!/usr/bin/env python3
"""Export the approved Nuvix artwork without stretching or cropping it.

Requires Pillow (scripts/requirements-branding.txt). Run from any directory.
The --size/--output option renders a centered logo for any positive canvas size.
"""

from __future__ import annotations

import argparse
import base64
import io
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BRANDING = Path("assets/branding")
RES = Path("app/src/main/res")
NAVY = "#081A3A"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
TV_SIZES = ((1280, 720), (1920, 1080), (3840, 2160), (1440, 1080),
            (1920, 1200), (2560, 1080), (3840, 1080), (1080, 1920))


def artwork() -> tuple[Image.Image, Image.Image, Image.Image]:
    """Use the approved 1254px cutout, with the mark above y=872.

    The image tool removed the background. Discard its near-invisible alpha
    noise before trimming, so that stray pixels cannot determine export bounds.
    The split is specific to this master, not an automatic logo detector.
    """
    with Image.open(ROOT / BRANDING / "master/nuvix-transparent.png") as source:
        logo = source.convert("RGBA")
    if logo.size != (1254, 1254):
        raise ValueError("Update the artwork split when replacing the 1254px master")
    logo.putalpha(logo.getchannel("A").point(lambda value: value if value > 8 else 0))
    mark = logo.crop((0, 0, 1254, 872))
    wordmark = logo.crop((0, 872, 1254, 1254))
    return tuple(image.crop(image.getbbox()) for image in (logo, mark, wordmark))


def contain(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    scale = min(size[0] / image.width, size[1] / image.height)
    return image.resize((max(1, round(image.width * scale)),
                         max(1, round(image.height * scale))), Image.Resampling.LANCZOS)


def centered(image: Image.Image, size: tuple[int, int], fraction: float,
             background: str | None = NAVY) -> Image.Image:
    if min(size) < 1:
        raise ValueError("Canvas dimensions must be positive")
    canvas = Image.new("RGBA", size, background or (0, 0, 0, 0))
    fitted = contain(image, (max(1, round(size[0] * fraction)),
                             max(1, round(size[1] * fraction))))
    canvas.alpha_composite(fitted, ((size[0] - fitted.width) // 2,
                                     (size[1] - fitted.height) // 2))
    return canvas


def save(image: Image.Image, root: Path, path: Path) -> None:
    destination = root / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    encoded = io.BytesIO()
    image.save(encoded, format="PNG")
    destination.write_bytes(encoded.getvalue())


def generate(root: Path) -> None:
    logo, mark, wordmark = artwork()
    save(mark, root, BRANDING / "master/nuvix-mark.png")
    save(wordmark, root, BRANDING / "master/nuvix-wordmark.png")
    save(mark, root, RES / "drawable-nodpi/ic_nuvix_logo.png")
    save(wordmark, root, RES / "drawable-nodpi/nuvix_wordmark.png")

    for density, scale in DENSITIES.items():
        # TV launcher legacy icons are 80dp. Text is kept in the banner instead.
        edge = round(80 * scale)
        icon = centered(mark, (edge, edge), .60)
        save(icon.convert("RGB"), root, RES / f"mipmap-{density}/ic_launcher.png")
        mask = Image.new("L", (edge * 4, edge * 4))
        ImageDraw.Draw(mask).ellipse((0, 0, edge * 4 - 1, edge * 4 - 1), fill=255)
        icon.putalpha(mask.resize((edge, edge), Image.Resampling.LANCZOS))
        save(icon, root, RES / f"mipmap-{density}/ic_launcher_round.png")

        # 44dp square stays inside the 66dp safe circle, including its corners.
        adaptive_edge = round(108 * scale)
        foreground = centered(mark, (adaptive_edge, adaptive_edge), 44 / 108, None)
        save(foreground, root, RES / f"drawable-{density}/ic_launcher_foreground.png")
        monochrome = Image.new("RGBA", foreground.size, "white")
        monochrome.putalpha(foreground.getchannel("A"))
        save(monochrome, root, RES / f"drawable-{density}/ic_launcher_monochrome.png")

        # Android 12+ masks splash icons into a 192dp circle on a 288dp canvas.
        splash_edge = round(288 * scale)
        splash = centered(mark, (splash_edge, splash_edge), 128 / 288, None)
        save(splash, root, RES / f"drawable-{density}/ic_nuvix_splash.png")

        # Banners stay 16:9 regardless of the physical TV's screen ratio.
        size = (round(160 * scale), round(90 * scale))
        save(centered(logo, size, .80).convert("RGB"), root,
             RES / f"drawable-{density}/banner.png")

    for size in TV_SIZES:
        save(centered(logo, size, .80).convert("RGB"), root,
             BRANDING / f"tv/nuvix-banner-{size[0]}x{size[1]}.png")

    square = centered(mark, (1024, 1024), .60).convert("RGB")
    for path in (ROOT / BRANDING / "png").glob("nuvix-*.png"):
        edge = int(path.stem.rsplit("-", 1)[1])
        save(centered(mark, (edge, edge), .60).convert("RGB"), root,
             path.relative_to(ROOT))
    save(square.resize((512, 512), Image.Resampling.LANCZOS), root,
         BRANDING / "play-store-icon-512.png")
    for path in (ROOT / BRANDING / "apple/AppIcon.appiconset").glob("*.png"):
        with Image.open(path) as existing:
            size = existing.size
        save(centered(mark, size, .60).convert("RGB"), root, path.relative_to(ROOT))
    for name, edge in (("icon-192.png", 192), ("icon-512.png", 512),
                       ("icon-maskable-512.png", 512), ("apple-touch-icon.png", 180)):
        fraction = .54 if "maskable" in name else .60
        save(centered(mark, (edge, edge), fraction).convert("RGB"), root, BRANDING / "web" / name)

    formats = root / BRANDING / "formats"
    formats.mkdir(parents=True, exist_ok=True)
    for extension in ("webp", "jpg", "bmp", "tiff", "ico", "icns"):
        options = {"quality": 95} if extension in ("jpg", "webp") else {}
        if extension == "tiff":
            options = {"compression": "tiff_lzw"}
        square.save(formats / f"nuvix.{extension}", **options)
    icon = square.resize((256, 256), Image.Resampling.LANCZOS)
    destination = root / BRANDING / "web/favicon.ico"
    destination.parent.mkdir(parents=True, exist_ok=True)
    icon.save(destination, sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])
    encoded = io.BytesIO()
    square.save(encoded, format="PNG")
    data = base64.b64encode(encoded.getvalue()).decode("ascii")
    (formats / "nuvix.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">'
        '<image width="1024" height="1024" preserveAspectRatio="xMidYMid meet" '
        f'href="data:image/png;base64,{data}"/></svg>\n', encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", help="Custom canvas WIDTHxHEIGHT, such as 3440x1440")
    parser.add_argument("--output", type=Path, help="Custom PNG destination (requires --size)")
    args = parser.parse_args()
    if args.size or args.output:
        if not (args.size and args.output):
            parser.error("--size and --output must be used together")
        try:
            width, height = (int(value) for value in args.size.lower().split("x"))
        except ValueError:
            parser.error("--size must be WIDTHxHEIGHT")
        if min(width, height) < 1 or max(width, height) > 8192:
            parser.error("Canvas dimensions must be between 1 and 8192 pixels")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        centered(artwork()[0], (width, height), .80).convert("RGB").save(args.output)
        print(args.output)
    else:
        generate(ROOT)
        print("Updated Android and assets/branding exports")


if __name__ == "__main__":
    main()
