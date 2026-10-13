#!/usr/bin/env python3
"""
OpenTV — local mock Stalker / Ministra portal (for development & testing).

Why this exists
---------------
Testing the Stalker portal code path against a real provider "line" is unreliable
(servers go down, rotate the MAC, rate-limit) and, for the kind of lines handed out
as free trials, not something to depend on. This is a tiny, dependency-free server
that speaks the exact slice of the Stalker/Ministra protocol OpenTV's StalkerApi
client calls, and returns a handful of channels pointing at FREE, LEGAL test streams
(Mux "Big Buck Bunny", Apple "BipBop", Bitmovin BBB). So you can exercise the whole
path — handshake -> token -> get_profile -> get_genres -> get_all_channels ->
create_link -> play — deterministically, and actually see video.

Run it
------
    python3 stalker_mock.py            # listens on 0.0.0.0:8080
    python3 stalker_mock.py 9000       # ...or a port you pick

It prints the exact values to type into OpenTV. Point the box at this machine's
LAN IP; the control API is served here, the video comes straight from public CDNs.

In OpenTV
---------
    Add source  ->  Stalker portal
      Portal URL : http://<this-machine-LAN-IP>:8080
      MAC        : 00:1A:79:00:00:01   (any non-empty MAC works)

Every request is logged to the console, so you can watch the handshake and each
call land — handy when a real user reports a portal quirk and you want to mimic it.

Not affiliated with Infomir. No copyrighted content is served or proxied.
"""

import json
import re
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# Free, legal developer test streams (HLS). Swap/extend freely.
STREAMS = {
    "1": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",                                             # Big Buck Bunny (Mux)
    "2": "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",  # Apple BipBop
    "3": "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",                               # Sintel (Bitmovin)
}

GENRES = [
    {"id": "1", "title": "Test Entertainment", "alias": "ent", "censored": 0},
    {"id": "2", "title": "Test Movies", "alias": "mov", "censored": 0},
]

# Each channel's `cmd` embeds its stream id; create_link reads it back and mints the real URL,
# exactly like a real portal hands out a just-in-time link.
CHANNELS = [
    {"id": "1", "name": "Big Buck Bunny (test)",  "number": "1", "tv_genre_id": "1",
     "cmd": "ffmpeg http://localhost/ch/1", "logo": "", "xmltv_id": ""},
    {"id": "2", "name": "Apple BipBop (test)",    "number": "2", "tv_genre_id": "1",
     "cmd": "ffmpeg http://localhost/ch/2", "logo": "", "xmltv_id": ""},
    {"id": "3", "name": "Sintel (test movie)",    "number": "3", "tv_genre_id": "2",
     "cmd": "ffmpeg http://localhost/ch/3", "logo": "", "xmltv_id": ""},
]


def js(payload):
    """Stalker wraps every response in a top-level {"js": ...}."""
    return json.dumps({"js": payload}).encode("utf-8")


def dispatch(qs):
    """Return the response body for a Stalker API request, keyed on type+action."""
    type_ = (qs.get("type", [""])[0]).lower()
    action = (qs.get("action", [""])[0]).lower()

    if action == "handshake":
        # Real portals return a random token; a fixed one is fine for a mock.
        return js({"token": "opentv-mock-token", "random": "0123456789abcdef"})

    if action == "get_profile":
        return js({"id": "1", "name": "OpenTV Mock", "status": 0, "blocked": "0",
                   "watchdog_timeout": "120", "timeslot": "0"})

    if type_ == "itv" and action == "get_genres":
        return js(GENRES)

    if type_ == "itv" and action in ("get_all_channels", "get_ordered_list"):
        return js({"data": CHANNELS, "total_items": len(CHANNELS),
                   "max_page_items": len(CHANNELS), "selected_item": 0})

    if type_ == "itv" and action == "create_link":
        cmd = qs.get("cmd", [""])[0]
        m = re.search(r"(\d+)\s*$", cmd.strip())
        stream_id = m.group(1) if m else "1"
        url = STREAMS.get(stream_id, STREAMS["1"])
        # Real portals prefix with "ffmpeg " / "auto "; the client strips it back to the URL.
        return js({"id": stream_id, "cmd": "ffmpeg " + url})

    # Unknown call: hand back an empty object so the client fails gracefully, not fatally.
    return js({})


class Handler(BaseHTTPRequestHandler):
    server_version = "OpenTVMockStalker/1.0"

    def do_GET(self):  # noqa: N802 (http.server API)
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        body = dispatch(qs)

        mac = ""
        cookie = self.headers.get("Cookie", "")
        mm = re.search(r"mac=([^;]+)", cookie)
        if mm:
            mac = mm.group(1)
        print("  {:<34} type={:<4} action={:<18} mac={}".format(
            parsed.path, qs.get("type", [""])[0], qs.get("action", [""])[0], mac))

        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # we print our own concise line above


def lan_ip():
    """Best-effort LAN IP (the address the TV should point at)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    ip = lan_ip()
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("=" * 64)
    print(" OpenTV mock Stalker portal — running")
    print("=" * 64)
    print(" In OpenTV:  Add source -> Stalker portal")
    print("   Portal URL : http://{}:{}".format(ip, port))
    print("   MAC        : 00:1A:79:00:00:01   (any non-empty MAC works)")
    print("-" * 64)
    print(" Channels: Big Buck Bunny, Apple BipBop, Sintel (free test streams)")
    print(" Requests will be logged below. Ctrl-C to stop.")
    print("=" * 64)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
