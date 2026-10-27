#!/usr/bin/env python3
"""Derive the Cloudflare UI from the existing GPL manager; check committed assets in CI."""
from pathlib import Path
import re
import sys
import textwrap

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
source = (ROOT / "app/src/main/java/app/opentv/pairing/ManagerPage.kt").read_text()
page = textwrap.dedent(source.split('= """', 1)[1].rsplit('""".trimIndent()', 1)[0]).strip()
page = page.replace("OpenTV — manage channels", "Nuvix — manage your TV")
page = page.replace("Your login stays on the TV.", "Details pass through Cloudflare over HTTPS and are saved on the TV.")
page = re.sub(r'<p class="privacy">.*?</p>', '''<p class="privacy">
Cloudflare relays channel details and changes to your TV over HTTPS. Provider and NAS details
you submit pass through the service; they are saved on the TV and are not stored by this manager.
This is not end-to-end encryption. Only connect to a manager service you trust.
Refresh or close this tab to forget the browser token; start a new session on the TV to reconnect.
</p>''', page, flags=re.S)
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
page = page.replace('<details class="rec" id="provCard">', pairing + '<details class="rec" id="provCard">', 1)
page = page.replace('<div id="status"></div>', '</section>\n<div id="status" role="status" aria-live="polite"></div>')
css = re.search(r'<style>(.*?)</style>', page, re.S).group(1)
css += '\n[hidden] { display:none !important; }\nbutton { font:inherit; }\n'
page = re.sub(r'<style>.*?</style>', '<link rel="stylesheet" href="/style.css">', page, flags=re.S)
js = re.search(r'<script>(.*?)</script>', page, re.S).group(1)
js = re.sub(r'var EMBEDDED_TOKEN = .*?var meta =', 'var meta =', js, flags=re.S)
transport = (HERE / 'browser-transport.js').read_text()
start = js.index('function withToken(')
end = js.index('var UNREACH', start)
js = js[:start] + transport + '\n' + js[end:]
js = js.replace("Couldn't reach the TV — still on the same wifi?", "Could not reach the TV. Keep its manager open. A change may have applied; refresh the list before retrying.")
js = js.replace('loadMeta();\n  loadRecording();', 'setupPairing();')
# Preserve indentation-independent matching of the final initialization only.
js = re.sub(r'loadMeta\(\);\s*loadRecording\(\);\s*$', 'setupPairing();\n', js)
page = re.sub(r'<script>.*?</script>', '<script src="/app.js" defer></script>', page, flags=re.S)
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
