"""Generate synthetic media and serve fake providers on loopback only."""
import argparse
import contextlib
import json
from pathlib import Path
import socket
import select
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


def generate(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    for channel, color, frequency in (("a", "red", 440), ("b", "blue", 880)):
        folder = root / channel
        folder.mkdir(exist_ok=True)
        command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                   "-f", "lavfi", "-i", f"color=c={color}:s=320x180:r=15",
                   "-f", "lavfi", "-i", f"sine=frequency={frequency}:sample_rate=48000",
                   "-t", "12", "-map_metadata", "-1", "-threads", "1",
                   "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline",
                   "-g", "15", "-sc_threshold", "0", "-c:a", "aac", "-b:a", "64k",
                   "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact"]
        subprocess.run(command + ["-f", "hls", "-hls_time", "1", "-hls_list_size", "0",
                                 "-hls_segment_filename", str(folder / "segment-%02d.ts"),
                                 str(folder / "index.m3u8")], check=True, timeout=60,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # Remux rather than encode twice. This file is also the sole DVR media fixture.
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i",
                        str(folder / "index.m3u8"), "-map_metadata", "-1", "-c", "copy",
                        "-f", "mpegts", str(root / f"{channel}.ts")], check=True, timeout=30,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return root


class Provider(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, root, port=0):
        self.root = Path(root).resolve()
        self.lock = threading.Lock()
        self.active = 0
        self.peak = 0
        self.requests = 0
        self.recovery_requests = {}
        self.rate_request_times_ms = []
        super().__init__(("127.0.0.1", port), Handler)

    @property
    def base(self):
        return f"http://127.0.0.1:{self.server_port}"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Request URLs can contain fake credentials; never log even those.

    def reply(self, data, content_type="application/json", status=200):
        if isinstance(data, (dict, list)):
            data = json.dumps(data).encode()
        elif isinstance(data, str):
            data = data.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        with contextlib.suppress(BrokenPipeError, ConnectionResetError):
            self.wfile.write(data)

    def do_GET(self):
        with self.server.lock:
            self.server.requests += 1
        parts = urlsplit(self.path)
        path, query = parts.path, parse_qs(parts.query)
        base = self.server.base
        if path == "/playlist.m3u":
            self.reply(f'#EXTM3U url-tvg="{base}/epg.xml"\n'
                       f'#EXTINF:-1 tvg-id="a",Synthetic A\n{base}/a/index.m3u8\n'
                       f'#EXTINF:-1 tvg-id="b",Synthetic B\n{base}/b.ts\n', "audio/x-mpegurl")
        elif path == "/epg.xml":
            self.reply('<?xml version="1.0"?><tv><channel id="a"><display-name>Synthetic A</display-name>'
                       '</channel><programme channel="a" start="20260101000000 +0000" '
                       'stop="20260101010000 +0000"><title>Synthetic programme</title></programme></tv>',
                       "application/xml")
        elif path == "/player_api.php":
            if query.get("username") != ["fixture"] or query.get("password") != ["fixture"]:
                self.reply({"user_info": {"auth": 0}}, status=401)
                return
            action = query.get("action", [""])[0]
            responses = {
                "": {"user_info": {"auth": 1, "status": "Active", "username": "fixture",
                                      "max_connections": "1", "active_cons": "0"},
                     "server_info": {"timezone": "UTC"}},
                "get_live_categories": [{"category_id": "1", "category_name": "Synthetic"}],
                "get_live_streams": [{"stream_id": i, "name": f"Synthetic {c.upper()}",
                                      "category_id": "1", "epg_channel_id": c, "num": i}
                                     for i, c in enumerate(("a", "b"), 1)]}
            self.reply(responses.get(action, []))
        elif path in ("/portal.php", "/server/load.php"):
            action = query.get("action", [""])[0]
            if action == "handshake":
                self.reply({"js": {"token": "synthetic-only-token", "random": "synthetic"}})
            elif self.headers.get("Authorization") != "Bearer synthetic-only-token":
                self.reply({"js": {"error": "invalid authentication"}}, status=401)
            else:
                responses = {"get_profile": {"id": "1", "blocked": "0", "status": 0},
                             "get_genres": [{"id": "1", "title": "Synthetic"}],
                             "get_all_channels": {"data": [
                                 {"id": str(i), "name": f"Synthetic {c.upper()}", "number": i,
                                  "tv_genre_id": "1", "cmd": f"ffmpeg {base}/{c}.ts"}
                                 for i, c in enumerate(("a", "b"), 1)]},
                             "create_link": {"cmd": f"ffmpeg {base}/{'a' if query.get('cmd') == ['a'] else 'b'}.ts"}}
                self.reply({"js": responses.get(action, [])})
        elif path.startswith("/recovery/"):
            mode = path.rsplit("/", 1)[-1]
            if mode not in {"disconnect.ts", "stall.ts", "twice.ts", "offline.ts", "rate.ts", "slow.ts"}:
                self.reply({}, status=404)
                return
            with self.server.lock:
                count = self.server.recovery_requests.get(mode, 0) + 1
                self.server.recovery_requests[mode] = count
                if mode == "rate.ts":
                    self.server.rate_request_times_ms.append(time.monotonic_ns() // 1_000_000)
                    self.server.rate_request_times_ms = self.server.rate_request_times_ms[-16:]
            if mode == "rate.ts" and count == 1:
                self.send_response(429)
                self.send_header("Retry-After", "3")
                self.send_header("Content-Length", "0")
                self.end_headers()
            elif mode == "offline.ts" or (mode == "twice.ts" and count <= 2):
                self.reply({}, status=503)
            elif mode == "slow.ts" and count == 1:
                time.sleep(2)
                self.reply((self.server.root / "a.ts").read_bytes(), "video/mp2t")
            elif mode in {"disconnect.ts", "stall.ts"} and count == 1:
                data = (self.server.root / "a.ts").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Content-Length", str(len(data) * 10))
                self.end_headers()
                try:
                    self.wfile.write(data)
                    self.wfile.flush()
                    if mode == "stall.ts":
                        # A genuine media load stalls after valid decoded video, until the viewer
                        # closes it. A bounded server deadline prevents fixture thread leakage.
                        deadline = time.monotonic() + 90
                        while time.monotonic() < deadline:
                            readable, _, _ = select.select([self.connection], [], [], .25)
                            if readable and not self.connection.recv(1, socket.MSG_PEEK):
                                break
                    self.connection.shutdown(socket.SHUT_RDWR)
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                self.close_connection = True
            else:
                self.reply((self.server.root / "a.ts").read_bytes(), "video/mp2t")
        elif path == "/fault/auth":
            self.reply({}, status=401)
        elif path == "/fault/slow":
            time.sleep(1)
            self.reply({})
        elif path == "/fault/interrupt":
            self.send_response(200)
            self.send_header("Content-Length", "100000")
            self.end_headers()
            self.wfile.write(b"incomplete")
            self.wfile.flush()
            self.connection.shutdown(socket.SHUT_RDWR)
            self.close_connection = True
        elif path == "/limited.ts":
            with self.server.lock:
                admitted = self.server.active < 1
                if admitted:
                    self.server.active += 1
                    self.server.peak = max(self.server.peak, self.server.active)
            if not admitted:
                self.reply({}, status=429)
                return
            try:
                data = (self.server.root / "a.ts").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Content-Length", str(len(data) * 40))
                self.end_headers()
                for _ in range(40):
                    self.wfile.write(data)
                    self.wfile.flush()
                    time.sleep(.05)
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with self.server.lock:
                    self.server.active -= 1
        elif path == "/stats":
            with self.server.lock:
                self.reply({"active": self.server.active, "peak": self.server.peak,
                            "requests": self.server.requests, "rate_request_times_ms": self.server.rate_request_times_ms})
        else:
            # Xtream-shaped paths map only known synthetic identities and stream IDs.
            aliases = {f"/live/fixture/fixture/{i}.{ext}": f"/{c}/index.m3u8" if ext == "m3u8" else f"/{c}.ts"
                       for i, c in enumerate(("a", "b"), 1) for ext in ("ts", "m3u8")}
            alias = path in aliases
            path = aliases.get(path, path)
            target = (self.server.root / path.lstrip("/")).resolve()
            if not target.is_relative_to(self.server.root) or not target.is_file():
                self.reply({}, status=404)
                return
            mime = "video/mp2t" if target.suffix == ".ts" else "application/vnd.apple.mpegurl"
            data = target.read_bytes()
            if alias and target.suffix == ".m3u8":
                data = data.decode().replace("segment-", f"{base}/{target.parent.name}/segment-").encode()
            self.reply(data, mime)


@contextlib.contextmanager
def serve(root, port=0):
    server = Provider(root, port)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default="work/iptv-media")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--generate-only", action="store_true")
    args = parser.parse_args()
    generate(args.root)
    if not args.generate_only:
        with serve(args.root, args.port):
            print("Synthetic IPTV provider ready on loopback", flush=True)
            threading.Event().wait()


if __name__ == "__main__":
    main()
