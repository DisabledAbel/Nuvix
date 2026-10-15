# Nuvix branding assets

The approved Nuvix identity is a cyan/blue/violet ribbon N with play-shaped
negative space, a purple wordmark, and a dark navy background (`#081A3A`).

- `master/nuvix-original.png`: approved full artwork.
- `master/nuvix-transparent.png`: full artwork with its background removed.
- `master/nuvix-mark.png` and `master/nuvix-wordmark.png`: trimmed exports for small UI elements.
- `png/`: square mark icons, 16–1024px. Small icons omit the name for legibility.
- `tv/`: full logo and name, centered on opaque navy canvases with safe margins.
- `formats/`: ICO, ICNS, WebP, JPEG, BMP, TIFF and PNG-embedded SVG.
- `apple/`: existing Xcode AppIcon catalog, updated with the new mark.
- `web/`: favicon, Apple touch icon, PWA icons and a starter manifest.
- `play-store-icon-512.png`: 512px store artwork.

## TV sizes and scaling

| Ratio | Included canvases |
| --- | --- |
| 16:9 | 1280×720, 1920×1080, 3840×2160 |
| 4:3 | 1440×1080 |
| 16:10 | 1920×1200 |
| 64:27 (commonly called 21:9) | 2560×1080 |
| 32:9 | 3840×1080 |
| 9:16 | 1080×1920 |

Every export fits the entire logo inside 80% of both canvas dimensions with
uniform scaling. Unused space is navy; the artwork is never stretched or cropped.
These are reusable branding canvases, not changes to video playback or TV zoom settings.
The exporter also accepts arbitrary dimensions up to 8192px per side.

The Android launcher banner remains 16:9 even on a differently shaped screen.
It includes the name and is packaged at mdpi through xxxhdpi (160×90 to 640×360,
including Android TV's 320×180 xhdpi banner). Legacy square TV icons are 80dp.
Adaptive icons use a transparent 108dp canvas with a 44dp mark, safely inside
the central 66dp circle, supporting round, square and rounded launcher masks.
Monochrome layers remain available for compatible non-TV launchers.

Android 12+ uses a separate splash mark on a 288dp transparent canvas. Its 128dp
mark stays inside the system's 192dp circular mask. The splash and launcher
backgrounds are navy. In-app mark and wordmark images use `ContentScale.Fit`.

## Regenerate and validate

Install the export dependency:

```sh
python3 -m pip install -r scripts/requirements-branding.txt
```

Regenerate all standard assets from the approved transparent master:

```sh
python3 scripts/generate-branding.py
```

Export a different screen size:

```sh
python3 scripts/generate-branding.py --size 3440x1440 --output nuvix-3440x1440.png
```

Validate the shipped dimensions, margins, logo proportions, name, masks and
Android resource references (also run by Build CI):

```sh
python3 -m unittest discover -s scripts/tests -v
```

The master is raster artwork. Larger exports add no detail beyond the master;
the SVG embeds a PNG and is not a vector redraw. Web manifest paths are starter
values. Other-platform exports do not imply that Nuvix runs on those platforms.
Real device checks remain useful for launcher caching, overscan and vendor zoom.

References:

- https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines
- https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
- https://developer.android.com/develop/ui/views/launch/splash-screen

These assets follow the repository's GPL-3.0-or-later licensing.
