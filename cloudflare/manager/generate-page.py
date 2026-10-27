#!/usr/bin/env python3
"""Derive the Cloudflare UI from the existing GPL manager; check committed assets in CI."""
from pathlib import Path
import re
import sys
import textwrap

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def rewrite(text, pattern, replacement, label, expected=1):
    result, count = re.subn(pattern, lambda match: replacement(match) if callable(replacement) else replacement,
                            text, flags=re.S)
    if count != expected:
        raise SystemExit(f"Cannot generate manager: {label} expected {expected} matches, found {count}")
    return result


def replace(text, old, new, label, expected=1):
    return rewrite(text, re.escape(old), new, label, expected)


def extract(text, pattern, label):
    matches = re.findall(pattern, text, flags=re.S)
    if len(matches) != 1:
        raise SystemExit(f"Cannot generate manager: {label} expected one match, found {len(matches)}")
    return matches[0]


source = (ROOT / "app/src/main/java/app/opentv/pairing/ManagerPage.kt").read_text()
page = textwrap.dedent(extract(source, r'= """(.*?)"""\.trimIndent\(\)', 'HTML template')).strip()
page = replace(page, "OpenTV — manage channels", "Nuvix — manage your TV", "branding", expected=2)
page = replace(page, "Your login stays on the TV.", "Details pass through Cloudflare over HTTPS and are saved on the TV.", "provider disclosure")
page = rewrite(page, r'<p class="privacy">.*?</p>', '''<p class="privacy">
Cloudflare relays channel details and changes to your TV over HTTPS. Provider and NAS details
you submit pass through the service; they are saved on the TV and are not stored by this manager.
This is not end-to-end encryption. Only connect to a manager service you trust.
Refresh or close this tab to forget the browser token; start a new session on the TV to reconnect.
</p>''', 'privacy disclosure')
pairing = '''<section id="pairPanel">
<h2>Connect to your TV</h2>
<p>On Nuvix, open Settings → Manage on phone or laptop and start a browser session.</p>
<form id="pairForm">
<label class="field"><span>Code from your TV</span>
<input id="pairCode" autocomplete="off" autocapitalize="characters" spellcheck="false"
 placeholder="ABCD-EFGH-2345" maxlength="16" required aria-describedby="pairMessage"></label>
<button class="save" id="pairSubmit" type="submit">Connect</button>
</form>
<p id="pairMessage" role="status" aria-live="polite">No QR code or account needed. Codes expire in 5 minutes.</p>
</section>
<section id="manager" hidden>
<button class="back" id="disconnect" type="button">Disconnect</button>
'''
page = replace(page, '<details class="rec" id="provCard">', pairing + '<details class="rec" id="provCard">', 'pairing sections')
# Close the manager section inside .wrap, before its closing div and the status sibling.
page = rewrite(page, r'(</div>\s*)<div id="status"></div>',
               lambda m: '</section>\n' + m[1] + '<div id="status" role="status" aria-live="polite"></div>',
               'manager section and status nesting')
page = rewrite(page, r'class="save" id="(provTest|recTest)" style="background:var\(--surface2\);border-color:var\(--line\);margin-left:8px;"',
               lambda m: f'class="save secondary" id="{m[1]}"', 'secondary test buttons', expected=2)
css = extract(page, r'<style>(.*?)</style>', 'stylesheet')
css += '\n[hidden] { display:none !important; }\nbutton { font:inherit; }\n'
css += '.save.secondary { background:var(--surface2); border-color:var(--line); margin-left:8px; }\n'
page = rewrite(page, r'<style>.*?</style>', '<link rel="stylesheet" href="/style.css">', 'stylesheet link')
js = extract(page, r'<script>(.*?)</script>', 'browser script')
js = rewrite(js, r'var EMBEDDED_TOKEN = .*?var meta =', 'var meta =', 'LAN token removal')
transport = (HERE / 'browser-transport.js').read_text()
js = rewrite(js, r'function withToken\(.*?(?=var UNREACH)', transport + '\n', 'Cloudflare transport')
js = replace(js, "Couldn't reach the TV — still on the same wifi?", "Could not reach the TV. Keep its manager open. A change may have applied; refresh the list before retrying.", 'connection error')
js = rewrite(js, r'loadMeta\(\);\s*loadRecording\(\);\s*$', 'setupPairing();\n', 'pairing initialization')
page = rewrite(page, r'<script>.*?</script>', '<script src="/app.js" defer></script>', 'script link')
page = '<!-- Derived from OpenTV ManagerPage, Copyright 2026 OpenTV Contributors; Nuvix modifications. GPL-3.0-or-later. -->\n' + page
outputs = {'index.html': page + '\n', 'app.js': js.strip() + '\n', 'style.css': css.strip() + '\n'}
for name, value in outputs.items():
    path = HERE / 'public' / name
    if '--check' in sys.argv:
        if not path.exists() or path.read_text() != value:
            raise SystemExit(f'{name} is stale; run python3 cloudflare/manager/generate-page.py')
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
