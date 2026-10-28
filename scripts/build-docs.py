#!/usr/bin/env python3
"""Build static, searchable Pages documentation from the repository's Markdown guides."""
# Nuvix Contributors. GPL-3.0-or-later.
import argparse
import html
import json
from pathlib import Path
import re
import shutil
from urllib.parse import quote, unquote, urlsplit, urlunsplit
from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
REPO = 'https://github.com/DisabledAbel/Nuvix'
GROUPS = [
    ('Get started', [('index', 'Overview'), ('getting-started', 'First setup'), ('installation', 'Install Nuvix'), ('browser-manager', 'Web manager')]),
    ('Use Nuvix', [('automatic-epg', 'TV guide & EPG'), ('live-playback', 'Live playback'), ('sports-hub', 'Sports Hub'), ('smart-multiview-dvr', 'Multi-View & recording'), ('dvr-storage-manager', 'Recording storage'), ('stalker-configuration', 'Stalker providers'), ('cloud-sync', 'Optional cloud sync')]),
    ('Help & reference', [('update-troubleshooting', 'Updates & installation'), ('playback-diagnostics', 'Playback diagnostics'), ('PRIVACY', 'Privacy & security'), ('building', 'Build from source'), ('TESTING', 'Testing & feedback')]),
]


def nav(active):
    groups = []
    for label, pages in GROUPS:
        links = ''.join(f'<a href="{name}.html"' + (' aria-current="page"' if name == active else '') + f'>{html.escape(title)}</a>' for name, title in pages)
        groups.append(f'<div class="nav-group"><p>{label}</p>{links}</div>')
    return ''.join(groups)


def slug(text):
    return re.sub(r'[^\w\- ]', '', text.lower(), flags=re.UNICODE).replace(' ', '-') or 'section'


def rewrite_link(href, source):
    parsed = urlsplit(href)
    if parsed.scheme or parsed.netloc or not parsed.path or parsed.path.startswith('/'):
        return href
    target = (source.parent / unquote(parsed.path)).resolve()
    if target.is_relative_to(DOCS):
        path = target.relative_to(DOCS).as_posix()
        if target.suffix == '.md' and target.is_file():
            path = str(Path(path).with_suffix('.html'))
        elif not target.exists():
            return f'{REPO}/blob/main/docs/{quote(path, safe="/")}' + (f'#{parsed.fragment}' if parsed.fragment else '')
        return urlunsplit(('', '', quote(path, safe='/'), parsed.query, parsed.fragment))
    if target.is_relative_to(ROOT):
        kind = 'tree' if target.is_dir() else 'blob'
        return f'{REPO}/{kind}/main/{quote(target.relative_to(ROOT).as_posix(), safe="/")}' + (f'#{parsed.fragment}' if parsed.fragment else '')
    return REPO


def render(source):
    md = MarkdownIt('commonmark', {'html': False}).enable('table')
    tokens = md.parse(source.read_text())
    used, toc, title, text = set(), [], source.stem, []
    for index, token in enumerate(tokens):
        if token.type == 'heading_open':
            heading = tokens[index + 1]
            label = ''.join(child.content for child in heading.children or [] if child.type in ('text', 'code_inline'))
            base, anchor, number = slug(label), slug(label), 1
            while anchor in used:
                anchor = f'{base}-{number}'; number += 1
            used.add(anchor); token.attrSet('id', anchor)
            if token.tag == 'h1' and title == source.stem:
                title = label
            if token.tag in ('h2', 'h3'):
                toc.append(f'<a class="{token.tag}" href="#{anchor}">{html.escape(label)}</a>')
        if token.type == 'inline':
            text.append(token.content)
        for child in token.children or []:
            if child.type == 'link_open':
                child.attrSet('href', rewrite_link(child.attrGet('href'), source))
            elif child.type == 'image':
                child.attrSet('src', rewrite_link(child.attrGet('src'), source))
    content = md.renderer.render(tokens, md.options, {})
    content = content.replace('<table>', '<div class="table-scroll" tabindex="0" role="region" aria-label="Scrollable table"><table>').replace('</table>', '</table></div>')
    return title, content, ''.join(toc), ' '.join(text)


def build(output):
    output = output.resolve()
    if output == ROOT or output == DOCS or DOCS.is_relative_to(output) or output.is_relative_to(DOCS):
        raise ValueError('Build output must be separate from documentation sources')
    output.mkdir(parents=True, exist_ok=True)
    # Preserve the permanent APK route, screenshots, and source downloads.
    shutil.copytree(DOCS, output, dirs_exist_ok=True, ignore=shutil.ignore_patterns('_*'))
    shutil.copyfile(ROOT / 'assets/branding/web/icon-192.png', output / 'site-icon.png')
    template = (DOCS / 'index.html').read_text()
    index = []

    def page(name, title, content, toc=''):
        values = {'TITLE': html.escape(title), 'NAV': nav(name), 'CONTENT': content,
                  'TOC': '<p>On this page</p>' + toc if toc else '',
                  'DESCRIPTION': html.escape('Set up and use Nuvix: providers, the TV guide, web management, recording and troubleshooting.')}
        result = template
        for key, value in values.items():
            result = result.replace('{{' + key + '}}', value)
        (output / f'{name}.html').write_text(result)

    page('index', 'Documentation', (DOCS / '_home.html').read_text())
    for source in sorted(DOCS.glob('*.md')):
        title, content, toc, text = render(source)
        source_link = f'{REPO}/blob/main/docs/{quote(source.name)}'
        content = '<p class="eyebrow">NUVIX / GUIDES</p>' + content + f'<div class="article-footer"><p>Documentation follows the source; controls may differ by installed version.</p><a href="{source_link}">View this guide on GitHub →</a></div>'
        page(source.stem, title, content, toc)
        index.append({'title': title, 'url': quote(source.with_suffix('.html').name, safe=''), 'text': text})
    (output / 'search-index.json').write_text(json.dumps(index, ensure_ascii=False))
    for _, pages in GROUPS:
        for name, _ in pages:
            if not (output / f'{name}.html').exists():
                raise ValueError(f'Missing navigation page: {name}')
    print(f'Built {len(index)} guides and documentation home in {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '_site')
    build(parser.parse_args().output)
