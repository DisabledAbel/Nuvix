"""Check the shipped pixels and Android resource wiring, including mask safety."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
BRANDING = ROOT / "assets/branding"
ANDROID = "{http://schemas.android.com/apk/res/android}"
NAVY = (8, 26, 58)


def visible_bbox(image):
    difference = ImageChops.difference(image.convert("RGB"), Image.new("RGB", image.size, NAVY))
    channels = difference.split()
    strength = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
    return strength.point(lambda value: 255 if value > 16 else 0).getbbox()


class BrandingTest(unittest.TestCase):
    def test_all_shipped_branding_pngs_decode(self):
        paths = [*BRANDING.rglob("*.png"), *RES.rglob("*.png")]
        self.assertGreater(len(paths), 80)
        for path in paths:
            with self.subTest(path=str(path.relative_to(ROOT))), Image.open(path) as image:
                image.verify()

    def assert_inside_circle(self, image, radius):
        alpha = image.convert("RGBA").getchannel("A")
        self.assertIsNotNone(alpha.getbbox(), "Artwork must not be empty")
        cx, cy = image.width / 2, image.height / 2
        for y in range(image.height):
            for x in range(image.width):
                if alpha.getpixel((x, y)) > 16:
                    self.assertLessEqual((x + .5 - cx) ** 2 + (y + .5 - cy) ** 2,
                                         radius ** 2, f"Visible pixel clipped at {x}, {y}")

    def test_tv_banners_have_expected_density_sizes_and_purple_wordmark(self):
        for density, size in {"mdpi": (160, 90), "hdpi": (240, 135), "xhdpi": (320, 180),
                              "xxhdpi": (480, 270), "xxxhdpi": (640, 360)}.items():
            with self.subTest(density=density), Image.open(RES / f"drawable-{density}/banner.png") as image:
                self.assertEqual(image.size, size)
                self.assertEqual(image.mode, "RGB")
                self.assertEqual(image.getpixel((0, 0)), NAVY)
                left, top, right, bottom = visible_bbox(image)
                self.assertGreater(top, size[1] * .05)
                self.assertLess(bottom, size[1] * .95)
                self.assertGreater(left, size[0] * .05)
                self.assertLess(right, size[0] * .95)
                text = image.crop((left, round(bottom - (bottom - top) * .20), right, bottom))
                purple_pixels = 0
                for y in range(text.height):
                    for x in range(text.width):
                        r, g, b = text.getpixel((x, y))
                        purple_pixels += r > 120 and b > 160 and b > g
                self.assertGreater(purple_pixels, text.width, "Banner must include the purple name")

    def test_fullscreen_exports_keep_logo_proportions_and_safe_margins(self):
        expected = {(1280, 720), (1920, 1080), (3840, 2160), (1440, 1080),
                    (1920, 1200), (2560, 1080), (3840, 1080), (1080, 1920)}
        files = list((BRANDING / "tv").glob("nuvix-banner-*.png"))
        self.assertEqual(len(files), len(expected))
        with Image.open(BRANDING / "master/nuvix-transparent.png") as master:
            bounds = master.getchannel("A").point(lambda value: 255 if value > 16 else 0).getbbox()
        ratio = (bounds[2] - bounds[0]) / (bounds[3] - bounds[1])
        actual = set()
        for path in files:
            with self.subTest(file=path.name), Image.open(path) as image:
                actual.add(image.size)
                left, top, right, bottom = visible_bbox(image)
                self.assertAlmostEqual((right - left) / (bottom - top), ratio, delta=.01)
                self.assertGreater(left, image.width * .05)
                self.assertGreater(top, image.height * .05)
                self.assertLess(right, image.width * .95)
                self.assertLess(bottom, image.height * .95)
                self.assertLessEqual(abs((left + right) / 2 - image.width / 2), 2)
                self.assertLessEqual(abs((top + bottom) / 2 - image.height / 2), 2)
        self.assertEqual(actual, expected)

    def test_adaptive_foregrounds_and_splash_survive_circular_masks(self):
        for density, scale in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
            with self.subTest(density=density):
                with Image.open(RES / f"drawable-{density}/ic_launcher_foreground.png") as image:
                    self.assertEqual(image.size, (round(108 * scale),) * 2)
                    self.assert_inside_circle(image, 33 * scale)
                    alpha = image.getchannel("A")
                with Image.open(RES / f"drawable-{density}/ic_launcher_monochrome.png") as image:
                    self.assertIsNone(ImageChops.difference(alpha, image.getchannel("A")).getbbox())
                    self.assertEqual(image.convert("RGB").getextrema(), ((255, 255),) * 3)
                with Image.open(RES / f"drawable-{density}/ic_nuvix_splash.png") as image:
                    self.assertEqual(image.size, (round(288 * scale),) * 2)
                    self.assert_inside_circle(image, 96 * scale)

    def test_tv_launcher_icons_and_round_fallback(self):
        for density, edge in {"mdpi": 80, "hdpi": 120, "xhdpi": 160, "xxhdpi": 240, "xxxhdpi": 320}.items():
            with self.subTest(density=density):
                with Image.open(RES / f"mipmap-{density}/ic_launcher.png") as image:
                    self.assertEqual(image.size, (edge, edge))
                    self.assertEqual(image.getpixel((0, 0)), NAVY)
                with Image.open(RES / f"mipmap-{density}/ic_launcher_round.png") as image:
                    self.assertEqual(image.size, (edge, edge))
                    self.assertEqual(image.getchannel("A").getpixel((0, 0)), 0)
                    self.assertEqual(image.getchannel("A").getpixel((edge // 2, edge // 2)), 255)

    def test_android_manifest_and_splash_use_the_exported_resources(self):
        application = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        self.assertEqual(application.get(ANDROID + "banner"), "@drawable/banner")
        self.assertEqual(application.get(ANDROID + "icon"), "@mipmap/ic_launcher")
        theme = ET.parse(RES / "values-v31/themes.xml").getroot().find("style")
        items = {item.get("name"): item.text for item in theme.findall("item")}
        self.assertEqual(items["android:windowSplashScreenAnimatedIcon"], "@drawable/ic_nuvix_splash")
        for version in (26, 33):
            adaptive = ET.parse(RES / f"mipmap-anydpi-v{version}/ic_launcher.xml").getroot()
            self.assertEqual(adaptive.find("foreground").get(ANDROID + "drawable"), "@drawable/ic_launcher_foreground")

    def test_maskable_web_icon_keeps_artwork_in_central_safe_circle(self):
        with Image.open(BRANDING / "web/icon-maskable-512.png") as image:
            rgb = image.convert("RGB")
            difference = ImageChops.difference(rgb, Image.new("RGB", rgb.size, NAVY))
            channels = difference.split()
            alpha = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
            foreground = image.convert("RGBA")
            foreground.putalpha(alpha.point(lambda value: 255 if value > 16 else 0))
            self.assert_inside_circle(foreground, image.width * .4)


if __name__ == "__main__":
    unittest.main()
