"""No arbitrary URLs: validate every redirect, variant, key and segment before I/O."""
import http.client
import ipaddress
import re
import socket
import time
import urllib.error
import urllib.request
from urllib.parse import unquote, urljoin, urlsplit

PUBLIC_ROOTS = {
    "devstreaming-cdn.apple.com": "/videos/streaming/examples/img_bipbop_adv_example_ts/",
    "test-streams.mux.dev": "/x36xhzz/",
}


class ProbeFailure(Exception):
    """Only fixed classification strings may be persisted, never exception text."""


def validate_url(url, local_origin=None):
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise ProbeFailure("source_policy") from None
    if (parts.username or parts.password or parts.query or parts.fragment or
            any(c.isspace() or ord(c) < 32 for c in url) or "\\" in url):
        raise ProbeFailure("source_policy")
    path = unquote(parts.path)
    if "%" in path or any(p in (".", "..") for p in path.split("/")):
        raise ProbeFailure("source_policy")
    if local_origin:
        if parts.scheme != "http" or parts.netloc != urlsplit(local_origin).netloc or parts.hostname != "127.0.0.1":
            raise ProbeFailure("source_policy")
    elif (parts.scheme != "https" or port not in (None, 443) or
          parts.hostname not in PUBLIC_ROOTS or not path.startswith(PUBLIC_ROOTS[parts.hostname])):
        raise ProbeFailure("source_policy")
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


class Client:
    def __init__(self, timeout=10, local_origin=None, request_limit=12):
        self.timeout = timeout
        self.local_origin = local_origin
        self.requests = 0
        self.bytes = 0
        self.request_limit = request_limit
        self.deadline = time.monotonic() + timeout * 3
        # Explicit loopback bypass is only for this in-process fixture. Public probes retain proxies.
        handlers = [NoRedirect()]
        if local_origin:
            handlers.append(urllib.request.ProxyHandler({}))
        self.opener = urllib.request.build_opener(*handlers)

    def get(self, url, limit=512 * 1024, segment=False):
        for _ in range(4):
            validate_url(url, self.local_origin)
            remaining = self.deadline - time.monotonic()
            if remaining <= 0 or self.requests >= self.request_limit or self.bytes >= 4 * 1024 * 1024:
                raise ProbeFailure("request_budget")
            if not self.local_origin:
                try:
                    addresses = socket.getaddrinfo(urlsplit(url).hostname, 443, type=socket.SOCK_STREAM)
                    if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
                        raise ProbeFailure("source_policy")
                except OSError:
                    raise ProbeFailure("dns_unavailable") from None
            headers = {"User-Agent": "Nuvix-authorized-playback-tests/1", "Accept-Encoding": "identity"}
            if segment:
                headers["Range"] = f"bytes=0-{limit - 1}"
            self.requests += 1
            try:
                response = self.opener.open(urllib.request.Request(url, headers=headers), timeout=min(self.timeout, remaining))
            except urllib.error.HTTPError as error:
                if error.code in (301, 302, 303, 307, 308):
                    location = error.headers.get("Location", "")
                    error.close()
                    if not location:
                        raise ProbeFailure("unsafe_redirect") from None
                    url = urljoin(url, location)
                    continue
                error.close()
                raise ProbeFailure("http_unavailable") from None
            except (OSError, urllib.error.URLError, http.client.HTTPException):
                raise ProbeFailure("network_unavailable") from None
            try:
                with response:
                    if response.status not in (200, 206) or (not segment and response.status != 200):
                        raise ProbeFailure("http_unavailable")
                    if response.headers.get("Content-Encoding", "identity") != "identity":
                        raise ProbeFailure("unsupported_encoding")
                    try:
                        expected = int(response.headers["Content-Length"]) if "Content-Length" in response.headers else None
                    except ValueError:
                        raise ProbeFailure("invalid_response_length") from None
                    if expected is not None and (expected < 0 or (not segment and expected > limit)):
                        raise ProbeFailure("response_too_large")
                    # read1 returns available data; checking the deadline between chunks prevents drip feeds.
                    data = bytearray()
                    maximum = limit if segment else limit + 1
                    while len(data) < maximum:
                        if time.monotonic() >= self.deadline:
                            raise ProbeFailure("request_budget")
                        chunk = response.read1(min(16384, maximum - len(data)))
                        if not chunk:
                            break
                        data.extend(chunk)
                    self.bytes += len(data)
                    if not segment and expected is not None and len(data) != expected:
                        raise ProbeFailure("interrupted_response")
                    if not segment and len(data) > limit:
                        raise ProbeFailure("response_too_large")
                    if not data:
                        raise ProbeFailure("empty_response")
                    return bytes(data), url
            except (OSError, http.client.HTTPException):
                raise ProbeFailure("interrupted_response") from None
        raise ProbeFailure("redirect_budget")


def playlist(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or not lines[0].startswith("#EXTM3U"):
        raise ProbeFailure("invalid_playlist")
    entries, pending = [], False
    for line in lines[1:]:
        if line.startswith("#EXTINF:"):
            if pending:
                raise ProbeFailure("invalid_playlist")
            pending = True
        elif not line.startswith("#"):
            if not pending:
                raise ProbeFailure("invalid_playlist")
            entries.append(line)
            pending = False
    if pending or not entries:
        raise ProbeFailure("invalid_playlist")
    return entries


def hls_references(text, base):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or lines[0] != "#EXTM3U":
        raise ProbeFailure("invalid_manifest")
    references = [urljoin(base, line) for line in lines if not line.startswith("#")]
    # Validate URI attributes too: keys, maps, audio renditions and alternate video cannot escape policy.
    references += [urljoin(base, uri) for line in lines if line.startswith("#")
                   for uri in re.findall(r'URI="([^"]+)"', line)]
    if not references:
        raise ProbeFailure("invalid_manifest")
    return lines, references


def probe_hls(url, client):
    seen = set()
    for _ in range(4):
        if url in seen:
            raise ProbeFailure("manifest_cycle")
        seen.add(url)
        body, final = client.get(url)
        try:
            lines, references = hls_references(body.decode("utf-8-sig"), final)
        except (UnicodeError, ValueError):
            raise ProbeFailure("invalid_manifest") from None
        for reference in references:
            validate_url(reference, client.local_origin)
        if any(line.startswith("#EXT-X-KEY:") and "METHOD=NONE" not in line for line in lines):
            raise ProbeFailure("encrypted_fixture_unsupported")
        if any(line.startswith("#EXT-X-STREAM-INF:") for line in lines):
            url = next((urljoin(final, line) for line in lines if not line.startswith("#")), None)
            if url is None:
                raise ProbeFailure("invalid_manifest")
            continue
        if not any(line.startswith("#EXTINF:") for line in lines):
            raise ProbeFailure("invalid_manifest")
        segments = [urljoin(final, line) for line in lines if not line.startswith("#")][:2]
        if not segments:
            raise ProbeFailure("invalid_manifest")
        for segment in segments:
            data, _ = client.get(segment, limit=256 * 1024, segment=True)
            # A bounded media prefix is evidence of transport availability, not a playback event.
            if len(data) < 188 or (data[0] != 0x47 and b"ftyp" not in data[:32] and b"styp" not in data[:32]):
                raise ProbeFailure("invalid_media_prefix")
        return {"manifests": len(seen), "segments": len(segments), "requests": client.requests,
                "bytes_checked": client.bytes}
    raise ProbeFailure("manifest_depth")
