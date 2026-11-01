"""SPEC-16 edge checks: the real infra/reverse-proxy/Caddyfile in front of stub upstreams.

Runs caddy:2-alpine in a disposable container. Every upstream is a stdlib stub on the host that
echoes what it received, so the checks observe what actually crosses the public boundary:
CA-04 WebSocket Upgrade/close and HLS/VOD MIME, Range/206 and nested paths; CA-05 /internal and
private headers; and the route table (screens -> Gateway, the rest -> their owner). No real data,
credentials or services are involved. Requires Docker; prints only scenario summaries.
"""
from __future__ import annotations

import base64
import hashlib
import http.client
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
UPSTREAMS = ("CORE", "STREAMING", "CHAT", "HLS", "NOTIFICATIONS", "VOD", "GATEWAY", "WEB")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MEDIA = bytes(range(256)) * 64  # 16 KiB fictitious segment
LARGE = 256 * 1024 * 1024  # 256 MiB advertised; never fully produced when the client cancels
PROGRESS = {"large": 0, "large_done": threading.Event(), "flood": 0, "flood_done": threading.Event()}


def stub(name: str):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def _echo(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            body = json.dumps({"upstream": name, "method": self.command, "path": self.path,
                               "headers": {k.lower(): v for k, v in self.headers.items()}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _large(self):
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(LARGE))
            self.end_headers()
            chunk = b"\0" * 65536
            try:
                while PROGRESS["large"] < LARGE:
                    self.wfile.write(chunk)
                    PROGRESS["large"] += len(chunk)
            except OSError:
                pass  # the edge closed the upstream after the client cancelled
            finally:
                PROGRESS["large_done"].set()
                self.close_connection = True

        def _media(self):
            mime = "application/vnd.apple.mpegurl" if self.path.endswith(".m3u8") else "video/mp2t"
            spec = self.headers.get("Range", "")
            body, status, extra = MEDIA, 200, {}
            if spec.startswith("bytes="):
                start, _, end = spec[6:].partition("-")
                start, end = int(start), int(end or len(MEDIA) - 1)
                body, status = MEDIA[start:end + 1], 206
                extra["Content-Range"] = f"bytes {start}-{end}/{len(MEDIA)}"
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("X-Upstream", name)
            for key, value in extra.items():
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _websocket(self):
            accept = base64.b64encode(hashlib.sha1((self.headers["Sec-WebSocket-Key"] + WS_GUID).encode()).digest()).decode()
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", accept)
            self.end_headers()
            self.wfile.flush()
            payload = read_frame(self.rfile)
            if payload == b"flood":
                return self._flood()
            self.wfile.write(frame(0x1, b"echo:" + payload))
            self.wfile.write(frame(0x8, (1000).to_bytes(2, "big") + b"bye"))
            self.wfile.flush()
            self.close_connection = True

        def _flood(self):
            data = frame(0x2, b"\1" * 65000)
            self.connection.settimeout(3)
            try:
                while PROGRESS["flood"] < LARGE:
                    self.wfile.write(data)
                    PROGRESS["flood"] += len(data)
            except OSError:
                pass  # blocked by backpressure until the timeout, or closed
            finally:
                PROGRESS["flood_done"].set()
                self.close_connection = True

        def do_GET(self):
            if self.headers.get("Upgrade", "").lower() == "websocket":
                return self._websocket()
            if self.path.endswith("/large.ts"):
                return self._large()
            if self.path.startswith(("/hls/", "/vod-media/")):
                return self._media()
            self._echo()

        do_HEAD = do_GET
        do_POST = do_PUT = do_DELETE = do_PATCH = _echo

    server = ThreadingHTTPServer(("0.0.0.0", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def frame(opcode: int, payload: bytes, mask: bool = False) -> bytes:
    head = bytes([0x80 | opcode])
    length = len(payload)
    marker = 0x80 if mask else 0
    if length < 126:
        head += bytes([marker | length])
    elif length < 65536:
        head += bytes([marker | 126]) + length.to_bytes(2, "big")
    else:
        head += bytes([marker | 127]) + length.to_bytes(8, "big")
    if not mask:
        return head + payload
    key = os.urandom(4)
    return head + key + bytes(b ^ key[i % 4] for i, b in enumerate(payload))


def read_frame(stream) -> bytes:
    first, second = stream.read(2)
    length = second & 0x7F
    if length == 126:
        length = int.from_bytes(stream.read(2), "big")
    key = stream.read(4) if second & 0x80 else b""
    data = stream.read(length)
    return bytes(b ^ key[i % 4] for i, b in enumerate(data)) if key else data


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Edge:
    def __init__(self, port: int):
        self.port = port

    def request(self, method, path, headers=None, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        data = response.read()
        connection.close()
        return response, data

    def echo(self, method, path, headers=None):
        response, data = self.request(method, path, headers)
        if response.status != 200:
            raise AssertionError(f"{method} {path} -> {response.status}")
        return json.loads(data)


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    print(f"PASS {name}")


def scenarios(edge: Edge):
    routes = {
        ("GET", "/api/screens/channels/fixture_01"): "GATEWAY",
        ("GET", "/api/screens/watch/str_fixture"): "GATEWAY",
        ("GET", "/api/screens/vods/vod_fixture"): "GATEWAY",
        ("GET", "/api/screens/explore?title=x"): "GATEWAY",
        ("POST", "/api/admin/content-removals"): "GATEWAY",
        ("GET", "/api/admin/users"): "CORE",
        ("GET", "/api/channels/by-handle/fixture_01"): "CORE",
        ("POST", "/api/discovery/graphql"): "CORE",
        ("GET", "/api/channels/chn_fixture/streams"): "STREAMING",
        ("GET", "/api/streams/str_fixture"): "STREAMING",
        ("GET", "/api/chat/sessions/ses_fixture/messages"): "CHAT",
        ("GET", "/api/vods?channelId=chn_fixture"): "VOD",
        ("GET", "/api/notifications"): "NOTIFICATIONS",
        ("GET", "/watch/str_fixture"): "WEB",
    }
    for (method, path), owner in routes.items():
        got = edge.echo(method, path)["upstream"]
        if got != owner:
            raise AssertionError(f"{method} {path} reached {got}, expected {owner}")
    check(f"route table: {len(routes)} public paths reach their owner", True)

    for path in ("/internal/vod/content-removals", "/actuator/health", "/health/ready", "/readyz", "/metrics", "/api/unknown"):
        response, data = edge.request("GET", path)
        if response.status != 404:
            raise AssertionError(f"{path} -> {response.status}")
    check("CA-05 /internal, health, metrics and unknown /api answer 404 at the edge", True)

    forged = {"X-Service-Name": "vod", "X-Service-Token": "forged", "X-Session-Credential": "forged",
              "Forwarded": "for=203.0.113.9", "X-Forwarded-For": "203.0.113.9", "X-Real-IP": "203.0.113.9",
              "Cookie": "stream_session=fixture", "X-Request-ID": "req_edge"}
    received = edge.echo("GET", "/api/screens/watch/str_fixture", forged)["headers"]
    check("CA-05 X-Service-*, X-Session-Credential and Forwarded are removed",
          not {"x-service-name", "x-service-token", "x-session-credential", "forwarded"} & received.keys())
    check("CA-05 forged X-Forwarded-For/X-Real-IP replaced by the observed peer",
          "203.0.113.9" not in received.get("x-forwarded-for", "") and "203.0.113.9" not in received.get("x-real-ip", "")
          and received.get("x-forwarded-for"))
    check("identity and correlation still reach the Gateway",
          received.get("cookie") == "stream_session=fixture" and received.get("x-request-id") == "req_edge")

    response, data = edge.request("GET", "/hls/ses_fixture/segment_1.ts", {"Range": "bytes=100-299"})
    check("CA-04 live HLS keeps 206, Content-Range, MIME and exact bytes",
          response.status == 206 and response.getheader("Content-Range") == f"bytes 100-299/{len(MEDIA)}"
          and response.getheader("Content-Type") == "video/mp2t" and data == MEDIA[100:300])
    response, data = edge.request("GET", "/hls/ses_fixture/index.m3u8")
    check("CA-04 live playlist keeps its MIME", response.status == 200 and response.getheader("Content-Type") == "application/vnd.apple.mpegurl")
    response, _ = edge.request("GET", "/hls/ses_fixture/../../internal/x")
    check("CA-04 HLS traversal is not proxied", response.status in (400, 404))
    response, data = edge.request("GET", "/vod-media/vod_fixture/rend_720/seg_00001.ts", {"Range": "bytes=0-"})
    check("CA-04 VOD nested subdirectories keep Range/206 to VOD",
          response.status == 206 and response.getheader("X-Upstream") == "VOD" and data == MEDIA)

    streaming_cancel(edge.port)
    websocket(edge.port)
    backpressure(edge.port)


def streaming_cancel(port: int):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as raw:
        raw.sendall(b"GET /vod-media/vod_fixture/rend_720/large.ts HTTP/1.1\r\nHost: localhost\r\n\r\n")
        started = time.monotonic()
        received = b""
        while len(received) < 1024 * 1024:
            received += raw.recv(65536)
        first_mib = time.monotonic() - started
    check("CA-04 media is streamed: the first MiB arrives before the upstream finishes",
          first_mib < 5 and not PROGRESS["large_done"].is_set())
    check("CA-04 client cancellation stops the upstream transfer", PROGRESS["large_done"].wait(10) and PROGRESS["large"] < LARGE // 2)
    print(f"INFO cancelled transfer stopped after {PROGRESS['large'] // (1024 * 1024)} MiB of {LARGE // (1024 * 1024)} MiB")


def ws_handshake(port: int):
    key = base64.b64encode(os.urandom(16)).decode()
    raw = socket.create_connection(("127.0.0.1", port), timeout=10)
    raw.sendall((f"GET /realtime/chat/sessions/ses_fixture HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                 f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                 f"Origin: http://localhost\r\n\r\n").encode())
    stream = raw.makefile("rb")
    status = stream.readline()
    while stream.readline() not in (b"\r\n", b""):
        pass
    return raw, stream, key, status


def backpressure(port: int):
    raw, stream, _, status = ws_handshake(port)
    with raw:
        raw.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
        raw.sendall(frame(0x1, b"flood", mask=True))
        time.sleep(4)  # the client reads nothing: the upstream must block instead of the edge buffering it all
        written = PROGRESS["flood"]
    PROGRESS["flood_done"].wait(10)
    check("CA-04 WebSocket backpressure: an unread client blocks the upstream writer", b" 101 " in status and written < 64 * 1024 * 1024)
    print(f"INFO upstream blocked after {written // (1024 * 1024)} MiB while the client was not reading")


def websocket(port: int):
    key = base64.b64encode(os.urandom(16)).decode()
    with socket.create_connection(("127.0.0.1", port), timeout=10) as raw:
        raw.sendall((f"GET /realtime/chat/sessions/ses_fixture HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                     f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                     f"Origin: http://localhost\r\n\r\n").encode())
        stream = raw.makefile("rb")
        status = stream.readline()
        headers = {}
        while (line := stream.readline()) not in (b"\r\n", b""):
            name, _, value = line.decode().partition(":")
            headers[name.strip().lower()] = value.strip()
        expected = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        check("CA-04 WebSocket Upgrade reaches Chat with a valid accept", b" 101 " in status and headers.get("sec-websocket-accept") == expected)
        raw.sendall(frame(0x1, b"hola", mask=True))
        check("CA-04 WebSocket frames cross both ways", read_frame(stream) == b"echo:hola")
        closing = read_frame(stream)
        check("CA-04 WebSocket close code is preserved", int.from_bytes(closing[:2], "big") == 1000)


def main():
    if subprocess.run(["docker", "info"], capture_output=True).returncode != 0:
        print("Docker is required", file=sys.stderr)
        return 2
    servers = {name: stub(name) for name in UPSTREAMS}
    port = free_port()
    name = f"gateway-edge-{os.getpid()}"
    with tempfile.TemporaryDirectory() as temporary:
        caddyfile = Path(temporary) / "Caddyfile"
        caddyfile.write_text((ROOT / "infra/reverse-proxy/Caddyfile").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
        env = []
        for upstream, server in servers.items():
            env += ["-e", f"{upstream}_UPSTREAM=http://host.docker.internal:{server.server_address[1]}"]
        subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "--add-host", "host.docker.internal:host-gateway",
                        "-p", f"127.0.0.1:{port}:3000", "-v", f"{caddyfile.parent}:/etc/caddy:ro", *env, "caddy:2-alpine"],
                       check=True, capture_output=True)
        try:
            deadline = time.monotonic() + 30
            while True:
                try:
                    Edge(port).request("GET", "/api/unknown")
                    break
                except OSError:
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(0.3)
            scenarios(Edge(port))
        finally:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)
            for server in servers.values():
                server.shutdown()
    print("PASS SPEC-16 edge checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
