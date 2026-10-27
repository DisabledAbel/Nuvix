"""Generated page structure and fail-closed template rewrites."""
from html.parser import HTMLParser
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PageStructure(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stack = []
        self.parents = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            self.parents[attrs['id']] = list(self.stack)
        if tag not in {'meta', 'link', 'input', 'img', 'br', 'hr'}:
            self.stack.append(tag)

    def handle_endtag(self, tag):
        if not self.stack or self.stack.pop() != tag:
            raise AssertionError(f'Invalid closing tag: {tag}')


class CloudflarePageTest(unittest.TestCase):
    def test_generated_page_has_valid_sections_and_csp_compatible_buttons(self):
        page = (ROOT / 'cloudflare/manager/public/index.html').read_text()
        parser = PageStructure()
        parser.feed(page)
        self.assertEqual(parser.stack, [])
        self.assertEqual(parser.parents['manager'][-1], 'div')
        self.assertNotIn('section', parser.parents['status'])
        self.assertNotIn('style=', page)
        self.assertEqual(page.count('class="save secondary"'), 2)
        js = (ROOT / 'cloudflare/manager/public/app.js').read_text()
        self.assertEqual(js.count('setupPairing();'), 1)

    def test_missing_rewrite_patterns_fail_clearly(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('cloudflare/manager/generate-page.py', 'cloudflare/manager/browser-transport.js',
                         'app/src/main/java/app/opentv/pairing/ManagerPage.kt'):
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, target)
            template = root / 'app/src/main/java/app/opentv/pairing/ManagerPage.kt'
            original = template.read_text()
            for old, new, label in (('<div id="status"></div>', '', 'manager section and status nesting'),
                                    ('loadRecording();', '', 'pairing initialization'),
                                    ('id="provTest"', 'id="changed"', 'secondary test buttons')):
                with self.subTest(label=label):
                    template.write_text(original.replace(old, new))
                    result = subprocess.run(['python3', str(root / 'cloudflare/manager/generate-page.py')],
                                            capture_output=True, text=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(label, result.stderr)
