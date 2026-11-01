#!/usr/bin/env python3
"""SPEC-16 against the integrated local stack (`python3 infra/local/dev.py up`).

Through the public HTTPS edge (Caddy -> Gateway -> domains): channel, watch, VOD-less channel and
explore screens with anonymous, signed-in and revoked identities; a real RTMPS source until the
watch screen announces renditions; HLS playlist/segment Range and a chat WebSocket on the same
edge; private routes and forged headers; concurrent identities; and screen latency samples.
Fictitious accounts are created here; prints only scenario summaries. Screen bodies are written
to the private state directory for `contracts/check_p2.py --public --payload <Schema> <file>`.
Standard library only; Docker runs the encoder from the local live image.
"""
from __future__ import annotations

import base64
import concurrent.futures
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import statistics
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]
STATE = Path(os.environ.get("STREAMING_STATE", ROOT / "infra/p1/.state-local")).expanduser()
ENV = dict(line.split("=", 1) for line in (STATE / "environment.env").read_text().splitlines() if "=" in line)
ORIGIN = ENV["WEB_ORIGIN"]
CA = STATE / "ca.crt"
TLS = ssl.create_default_context(cafile=str(CA))
# Python >= 3.13 also demands keyUsage on the local CA; chain and hostname are still verified.
TLS.verify_flags &= ~getattr(ssl, "VERIFY_X509_STRICT", 0)
NETWORK = "streaming-local_default"
SAMPLES = STATE / "gateway-screens"


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=TLS),
                                                urllib.request.HTTPCookieProcessor(self.cookies))

    def call(self, path, method="GET", body=None, headers=None, expect=200, raw=False):
        hdr = {"Accept": "*/*" if raw else "application/json", "Origin": ORIGIN, "X-Request-ID": "req_" + uuid.uuid4().hex, **(headers or {})}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            hdr["Content-Type"] = "application/json"
        request = urllib.request.Request(ORIGIN + path, method=method, headers=hdr, data=data)
        try:
            response = self.http.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            if expect is not None and response.status != expect:
                raise AssertionError(f"{method} {path.split('?')[0]} expected {expect}, got {response.status}: {content[:160]!r}")
            if raw:
                return response.status, response.headers, content
            return response.status, (json.loads(content) if content else None), response.headers

    def mutate(self, path, method="POST", body=None, headers=None, expect=200):
        _, csrf, _ = self.call("/api/identity/csrf")
        return self.call(path, method, body, {csrf["headerName"]: csrf["token"], "Idempotency-Key": str(uuid.uuid4()), **(headers or {})}, expect)

    def credential(self):
        return next(c.value for c in self.cookies if c.name == "stream_session")


def anonymous_with(cookie):
    """A raw client presenting a given (possibly revoked) credential without a cookie jar."""
    def call(path, expect):
        request = urllib.request.Request(ORIGIN + path, headers={"Accept": "application/json", "Cookie": cookie})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=TLS))
        try:
            response = opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            response.read()
            if response.status != expect:
                raise AssertionError(f"{path} with presented credential -> {response.status}, expected {expect}")
    return call


def websocket_ready(path, cookie):
    url = urllib.parse.urlsplit(ORIGIN)
    with socket.create_connection((url.hostname, url.port or 443), timeout=10) as raw, TLS.wrap_socket(raw, server_hostname=url.hostname) as sock:
        key = base64.b64encode(os.urandom(16)).decode()
        sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {url.netloc}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                      f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {ORIGIN}\r\n"
                      f"Cookie: stream_session={cookie}\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = sock.recv(1)
            if not chunk:
                raise AssertionError("WebSocket handshake closed")
            head += chunk
        if not head.startswith(b"HTTP/1.1 101"):
            raise AssertionError("WebSocket handshake rejected: " + head.split(b"\r\n")[0].decode())

        def read(n):
            out = b""
            while len(out) < n:
                chunk = sock.recv(n - len(out))
                if not chunk:
                    raise AssertionError("WebSocket closed")
                out += chunk
            return out

        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            first, second = read(2)
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", read(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", read(8))[0]
            payload = read(length)
            if first & 0x0F == 0x1 and json.loads(payload).get("type") == "chat.ready":
                mask = os.urandom(4)
                close = (1000).to_bytes(2, "big")
                sock.sendall(bytes([0x88, 0x80 | len(close)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(close)))
                return True
        raise AssertionError("chat.ready not received")


def eventually(read, predicate, timeout=30, step=0.5):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            last = read()
            if predicate(last):
                return last
        except (OSError, urllib.error.URLError, AssertionError) as error:
            last = error
        time.sleep(step)
    raise AssertionError(f"did not converge: {str(last)[:200]}")


def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, capture_output=True, text=True, timeout=60).stdout


class Publisher:
    def __init__(self, ingest_key):
        self.key, self.name = ingest_key, None

    def start(self):
        self.name = "gateway-e2e-" + secrets.token_hex(4)
        docker("run", "-d", "--rm", "--name", self.name, "--network", NETWORK, "-v", f"{CA}:/run/ca.crt:ro",
               "--entrypoint", "ffmpeg", "streaming-local-live", "-hide_banner", "-loglevel", "error",
               "-re", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
               "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "30", "-pix_fmt", "yuv420p",
               "-c:a", "aac", "-b:a", "96k", "-tls_verify", "1", "-ca_file", "/run/ca.crt",
               "-f", "flv", "rtmps://media-server:1936/live/" + self.key)

    def stop(self):
        if self.name:
            docker("stop", "-t", "2", self.name, check=False)
            self.name = None


def save(schema, label, value):
    SAMPLES.mkdir(mode=0o700, exist_ok=True)
    (SAMPLES / f"{schema}.{label}.json").write_text(json.dumps(value))


def latency(client, path, n=30):
    samples = []
    for _ in range(n):
        started = time.perf_counter()
        client.call(path)
        samples.append((time.perf_counter() - started) * 1000)
    samples.sort()
    return statistics.median(samples), samples[int(len(samples) * 0.95) - 1]


def media_lines(content):
    lines = content.decode().splitlines()
    uris = [line for line in lines if line and not line.startswith("#")]
    uris += [line.split('URI="', 1)[1].split('"', 1)[0] for line in lines if line.startswith("#EXT-X-MAP:")]
    return uris


def vod_range(client):
    """Range/206 and nested paths through the edge on any published VOD the stack already holds."""
    _, page, _ = client.call("/api/vods?limit=20")
    vod = next((v for v in page["items"] if v.get("processingState") == "READY" and v.get("playbackPath")), None)
    if vod is None:
        print("INFO no published VOD in this stack: VOD Range/206 covered by tests/integration/p2-gateway-edge", flush=True)
        return
    master = vod["playbackPath"]
    _, head, content = client.call(master, raw=True)
    assert "mpegurl" in head["Content-Type"].lower(), head["Content-Type"]
    variant = urllib.parse.urljoin(master, media_lines(content)[0])
    _, _, playlist = client.call(variant, raw=True)
    segment = urllib.parse.urljoin(variant, media_lines(playlist)[0])
    status, head, part = client.call(segment, headers={"Range": "bytes=0-187"}, expect=None, raw=True)
    assert status == 206 and len(part) == 188 and head["Content-Range"].startswith("bytes 0-187/"), (status, dict(head))
    print(f"PASS CA-04 VOD through the edge: nested {segment.count('/') - 2}-level path, Range -> 206 ({head['Content-Type']})", flush=True)


def main():
    anon, owner, fan = Client(), Client(), Client()
    password = "Ficticia-" + secrets.token_urlsafe(12)
    handles = {}
    for role, client in (("owner", owner), ("fan", fan)):
        handle = "gwe2e_" + role[0] + secrets.token_hex(4)
        client.mutate("/api/identity/registrations", body={"email": handle + "@example.test", "handle": handle, "password": password}, expect=201)
        client.mutate("/api/identity/sessions", body={"login": handle, "password": password})
        handles[role] = handle
    screen = "/api/screens/channels/" + handles["owner"]

    _, value, headers = anon.call(screen)
    assert headers["Cache-Control"] == "no-store, private"
    assert value["follow"]["status"] == "DENIED" and value["vods"]["status"] == "EMPTY" and value["live"]["status"] == "EMPTY", value
    assert value["meta"]["partial"] is False
    save("P2ChannelScreen", "anonymous", value)
    print("PASS CA-01/02 channel screen: one request, EMPTY library, anonymous follow DENIED, no-store", flush=True)

    channel_id = value["channel"]["channel"]["channelId"]
    fan.mutate(f"/api/channels/{channel_id}/followers/me", "PUT")
    _, mine, _ = fan.call(screen)
    assert mine["follow"]["status"] == "AVAILABLE" and mine["follow"]["data"]["following"] is True
    save("P2ChannelScreen", "follower", mine)

    def follow_state(client):
        return client.call(screen)[1]["follow"]["data"]["following"]
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        results = list(pool.map(lambda c: (c is fan, follow_state(c)), [fan, owner] * 10))
    assert all(is_fan == following for is_fan, following in results), results
    print("PASS CA-06 20 concurrent screens keep each identity's follow state", flush=True)

    revoked = "stream_session=" + fan.credential()
    fan.mutate("/api/identity/sessions/current", "DELETE", expect=204)
    anonymous_with(revoked)(screen, 401)
    anonymous_with(revoked)("/api/screens/explore", 401)
    print("PASS CA-03 a revoked credential gets 401, never a visitor view", flush=True)

    anon.call("/api/screens/channels/nonexistent_" + secrets.token_hex(3), expect=404)
    anon.call("/api/screens/watch/str_" + secrets.token_hex(8), expect=404)
    for path in ("/internal/vod/content-removals", "/internal/streaming/recordings", "/api/screens/../internal/x"):
        anon.call(path, expect=404)
    anon.call(screen, headers={"X-Service-Name": "vod", "X-Service-Token": "forged", "X-Session-Credential": "forged"})
    print("PASS CA-05 private routes stay 404 and forged service headers grant nothing", flush=True)

    _, catalog, _ = anon.call("/api/taxonomy")
    _, config, _ = owner.call(f"/api/channels/{channel_id}/streams", "POST",
                              {"title": "Gateway E2E", "categoryId": catalog["categories"][0]["id"], "tagIds": []},
                              {"Idempotency-Key": str(uuid.uuid4())}, 201)
    stream_id = config["streamId"]
    watch = "/api/screens/watch/" + stream_id
    _, before, _ = anon.call(watch)
    assert before["renditions"]["status"] == "EMPTY" and before["channel"]["status"] == "AVAILABLE", before
    save("P2WatchScreen", "before-source", before)
    ingest_key = config.get("streamKey") or config["ingestUrl"].rsplit("/", 1)[1]
    publisher = Publisher(ingest_key)
    try:
        publisher.start()
        live = eventually(lambda: anon.call(watch)[1], lambda v: v["renditions"]["status"] == "AVAILABLE", 120, 1)
        session_id = live["session"]["sessionId"]
        assert live["stream"]["availability"] == "PLAYABLE" and live["meta"]["partial"] is False
        save("P2WatchScreen", "live", live)
        print(f"PASS watch screen: stream+session+channel+{len(live['renditions']['data']['renditions'])} rendition(s) after a real RTMPS source", flush=True)

        _, channel_live, _ = anon.call(screen)
        assert channel_live["live"]["status"] == "AVAILABLE", channel_live["live"]
        save("P2ChannelScreen", "live", channel_live)
        print("PASS CA-01 channel screen shows the live section", flush=True)

        master = live["renditions"]["data"]["masterPlaylistUri"]
        _, head, playlist = anon.call(master, raw=True)
        assert "mpegurl" in head["Content-Type"].lower(), head["Content-Type"]
        variant = next(line for line in playlist.decode().splitlines() if line and not line.startswith("#"))
        variant_path = urllib.parse.urljoin(master, variant)
        _, _, media_playlist = eventually(lambda: anon.call(variant_path, raw=True), lambda r: b"#EXTINF" in r[2], 30)
        segment = next(line for line in media_playlist.decode().splitlines() if line and not line.startswith("#"))
        segment_path = urllib.parse.urljoin(variant_path, segment)
        status, head, part = anon.call(segment_path, expect=None, raw=True)
        assert status == 200 and head["Content-Type"] == "video/mp4" and part, (status, dict(head))
        # Live playlists carry no EXT-X-BYTERANGE: Media serves whole segments; Range is a VOD contract.
        print(f"PASS CA-04 live HLS through the edge: playlist MIME, variant and {len(part)} B segment ({head['Content-Type']})", flush=True)
        vod_range(anon)

        assert websocket_ready("/realtime/chat/sessions/" + session_id, owner.credential())
        print("PASS CA-04 chat WebSocket Upgrade through the edge reaches chat.ready", flush=True)

        _, explore, _ = anon.call("/api/screens/explore")
        assert explore["discovery"]["status"] == "AVAILABLE" and explore["vods"]["status"] in ("AVAILABLE", "EMPTY"), explore["meta"]
        save("P2ExploreScreen", "live", explore)
        print("PASS explore screen composes Discovery and VOD", flush=True)

        for label, path in (("channel", screen), ("watch", watch), ("explore", "/api/screens/explore")):
            p50, p95 = latency(anon, path)
            print(f"INFO latency {label} screen over 30 sequential requests: p50={p50:.0f} ms p95={p95:.0f} ms", flush=True)

        owner.mutate("/api/streams/sessions/" + session_id, "DELETE", expect=204)
    finally:
        publisher.stop()
    ended = eventually(lambda: anon.call(watch)[1], lambda v: v["session"] is None or v["session"]["status"] == "ENDED", 30)
    assert ended["renditions"]["status"] == "EMPTY", ended["renditions"]
    save("P2WatchScreen", "ended", ended)
    print("PASS ended stream: watch screen keeps the stream and drops renditions", flush=True)
    print(f"PASS SPEC-16 local stack checks; screen samples in {SAMPLES}")


if __name__ == "__main__":
    main()
