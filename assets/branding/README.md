# Nuvix branding assets

The blue/cyan/violet N and play mark is the Nuvix app identity.

- `master/`: original artwork and transparent foreground.
- `png/`: common square sizes from 16 to 1024 pixels.
- `formats/`: Windows ICO, macOS ICNS, WebP, JPEG, BMP, TIFF and SVG.
- `apple/`: iPhone and iPad Xcode AppIcon asset catalog.
- `web/`: favicon, Apple touch icon, PWA icons and a starter manifest.
- `tv/`: 1280x720 TV banner.
- `play-store-icon-512.png`: 512px store artwork.

The Android app uses legacy density icons, adaptive foreground/background
layers, Android 13+ monochrome layers, and a 320x180 banner from
`app/src/main/res`. The foreground fits inside a conservative 44dp square
on the 108dp adaptive canvas, allowing circular and rounded masks.
The existing in-app `ic_nuvix_logo` name uses the transparent artwork.

The SVG embeds a PNG; it is not true vector artwork. Larger exports do not
add detail beyond the raster master. Web manifest paths are starter values
and should be adjusted when integrating into a site. Other-platform exports
are branding assets and do not imply that Nuvix runs on those platforms.

Android guidance:
https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines

These assets follow the repository's GPL-3.0-or-later licensing.
