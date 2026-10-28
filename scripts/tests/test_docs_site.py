"""Static docs rendering and navigation checks; run in the Pages workflow."""
import importlib.util
from html.parser import HTMLParser
from pathlib import Path
import tempfile
import unittest
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]


class Links(HTMLParser):
    def __init__(self):
        super().__init__(); self.links = []; self.ids = set()
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs: self.ids.add(attrs['id'])
        if tag == 'a' and attrs.get('href'): self.links.append(attrs['href'])


@unittest.skipUnless(importlib.util.find_spec('markdown_it'), 'Pages tests require scripts/requirements-docs.txt')
class DocsSiteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('docs_builder', ROOT / 'scripts/build-docs.py')
        cls.builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(cls.builder)
        (ROOT / 'work').mkdir(exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(dir=ROOT / 'work')
        cls.output = Path(cls.temp.name) / 'site'
        cls.builder.build(cls.output)
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_home_has_live_manager_and_preserves_apk_route(self):
        page = (self.output / 'index.html').read_text()
        self.assertIn('https://nuvix-manager.blazersboy1.workers.dev/', page)
        self.assertNotIn('{{', page)
        self.assertIn('Search documentation', page)
        self.assertIn('Skip to content', page)
        self.assertIn('5 minutes', page)
        self.assertTrue((self.output / 'apk/index.html').is_file())
        self.assertIn('releases/latest', (self.output / 'apk/index.html').read_text())

    def test_all_new_navigation_and_guide_links_resolve(self):
        for name in ('index', 'getting-started', 'installation', 'browser-manager', 'PRIVACY'):
            parser = Links(); parser.feed((self.output / f'{name}.html').read_text())
            for href in parser.links:
                url = urlsplit(href)
                if url.scheme or url.netloc: continue
                path = self.output / unquote(url.path or f'{name}.html')
                if path.is_dir(): path /= 'index.html'
                self.assertTrue(path.is_file(), f'{name}: missing {href}')
                if url.fragment and path.suffix == '.html':
                    destination = Links(); destination.feed(path.read_text())
                    self.assertIn(unquote(url.fragment), destination.ids, f'{name}: missing anchor {href}')

    def test_markdown_is_escaped_and_links_keep_project_relative_paths(self):
        fixture = Path(self.temp.name) / 'untrusted.md'
        fixture.write_text('# Sample\n\n<script>alert(1)</script>\n\n[bad](javascript:alert(1))\n\n| A | B |\n|---|---|\n| x | y |\n')
        _, rendered, _, _ = self.builder.render(fixture)
        self.assertNotIn('<script>', rendered)
        self.assertNotIn('href="javascript:', rendered)
        self.assertIn('&lt;script&gt;', rendered)
        self.assertIn('table-scroll', rendered)
        self.assertEqual('PRIVACY.html', self.builder.rewrite_link('PRIVACY.md', ROOT / 'docs/browser-manager.md'))
        self.assertEqual('https://github.com/DisabledAbel/Nuvix/blob/main/README.md', self.builder.rewrite_link('../README.md', ROOT / 'docs/browser-manager.md'))
