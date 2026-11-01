#!/usr/bin/env python3
"""Chat replay against the integrated local stack (`python3 infra/local/dev.py up`).

Real RTMPS source with a reconnect cut, Chat WebSocket, owner moderation, native capture, VOD
processing/publication/deletion and p2.chat.replay over the VOD timeline. Uses fictitious accounts
created here; prints only scenario summaries. The replay pages are written to the private state
directory so they can be checked with contracts/check_p2.py --payload P2ReplayPage.
Standard library only; Docker runs the encoder from the local live image.
"""
from __future__ import annotations
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]
STATE = ROOT / "infra/p1/.state-local"
PROJECT = "streaming-local"
NETWORK = PROJECT + "_default"
LIVE_IMAGE = "streaming-local-live"
ORIGIN = CA = TLS = None


def configure(state=STATE, project=PROJECT, network=None, origin=None, live_image=LIVE_IMAGE):
    """Explicit fixture configuration; importing never reads the personal installation."""
    global STATE, PROJECT, NETWORK, ORIGIN, CA, TLS, LIVE_IMAGE
    STATE, PROJECT = Path(state).resolve(), project
    NETWORK, LIVE_IMAGE = network or project + "_default", live_image
    env = dict(line.split("=", 1) for line in (STATE / "environment.env").read_text().splitlines() if "=" in line)
    ORIGIN, CA = origin or env["WEB_ORIGIN"], STATE / "ca.crt"
    TLS = ssl.create_default_context(cafile=str(CA))
    # Local CA predates Python's strict keyUsage requirement; chain/host stay verified.
    TLS.verify_flags &= ~getattr(ssl, "VERIFY_X509_STRICT", 0)


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=TLS),
                                                urllib.request.HTTPCookieProcessor(self.cookies))

    def call(self, path, method="GET", body=None, headers=None, expect=200):
        hdr = {"Accept": "application/json", "Origin": ORIGIN, "X-Request-ID": str(uuid.uuid4()), **(headers or {})}
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
            raw = response.read()
            if expect is not None and response.status != expect:
                raise AssertionError(f"{method} {path.split('?')[0]} expected {expect}, got {response.status}: {raw[:200]!r}")
            return response.status, (json.loads(raw) if raw else None)

    def mutate(self, path, method="POST", body=None, headers=None, expect=200):
        _, csrf = self.call("/api/identity/csrf")
        return self.call(path, method, body, {csrf["headerName"]: csrf["token"], "Idempotency-Key": str(uuid.uuid4()),
                                              **(headers or {})}, expect)

    def credential(self):
        return next(c.value for c in self.cookies if c.name == "stream_session")


class Socket:
    """Minimal RFC 6455 text client: enough for chat.ready, message.send and their replies."""

    def __init__(self, path, cookie):
        url = urllib.parse.urlsplit(ORIGIN)
        raw = socket.create_connection((url.hostname, url.port or 443), timeout=10)
        self.sock = TLS.wrap_socket(raw, server_hostname=url.hostname)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {url.netloc}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {ORIGIN}\r\n"
                           f"Cookie: stream_session={cookie}\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(1)
            if not chunk:
                raise AssertionError("WebSocket handshake closed")
            head += chunk
        if not head.startswith(b"HTTP/1.1 101"):
            raise AssertionError("WebSocket handshake rejected: " + head.split(b"\r\n")[0].decode())
        self.pending = []

    def _read(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise AssertionError("WebSocket closed")
            out += chunk
        return out

    def _frame(self):
        b1, b2 = self._read(2)
        length = b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read(8))[0]
        return b1 & 0x0F, self._read(length)

    def _send(self, opcode, payload):
        mask = os.urandom(4)
        header = bytes([0x80 | opcode])
        n = len(payload)
        header += bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n)
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send(self, value):
        self._send(0x1, json.dumps(value).encode())

    def read(self):
        """Blocks for the next server frame (socket timeout 10 s) and queues text frames."""
        opcode, payload = self._frame()
        if opcode == 0x9:
            self._send(0xA, payload)
        elif opcode == 0x8:
            raise AssertionError("WebSocket closed by server")
        elif opcode == 0x1:
            self.pending.append(json.loads(payload))

    def first(self, predicate, timeout=10):
        end = time.monotonic() + timeout
        while True:
            for i, value in enumerate(self.pending):
                if predicate(value):
                    return self.pending.pop(i)
            if time.monotonic() >= end:
                raise AssertionError("Expected WS frame missing")
            self.read()

    def expect(self, kind, client_id=None, timeout=10):
        return self.first(lambda v: v.get("type") == kind and (client_id is None or v.get("clientMessageId") == client_id), timeout)

    def close(self):
        try:
            self._send(0x8, b"")
        finally:
            self.sock.close()


def eventually(read, predicate, timeout=60, step=0.5):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            last = read()
            if predicate(last):
                return last
        except (OSError, AssertionError, urllib.error.URLError) as error:
            last = error
        time.sleep(step)
    raise AssertionError(f"Scenario did not converge within {timeout}s (last: {str(last)[:200]})")


def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, text=True, capture_output=True).stdout.strip()


def chat_sql(statement):
    container = docker("ps", "--filter", f"label=com.docker.compose.project={PROJECT}",
                       "--filter", "label=com.docker.compose.service=chat-pg", "--format", "{{.Names}}").splitlines()[0]
    return docker("exec", container, "psql", "-U", "chat", "-d", "chat", "-v", "ON_ERROR_STOP=1", "-At", "-c", statement)


def quoted(value):
    assert all(c.isalnum() or c in "_-" for c in value)
    return "'" + value + "'"


class Publisher:
    def __init__(self, ingest_key):
        self.key, self.name = ingest_key, None

    def start(self):
        self.name = "chat-replay-e2e-" + secrets.token_hex(4)
        docker("run", "-d", "--rm", "--name", self.name, "--network", NETWORK, "-v", f"{CA}:/run/ca.crt:ro",
               "--entrypoint", "ffmpeg", LIVE_IMAGE, "-hide_banner", "-loglevel", "error",
               "-re", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
               "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "30", "-pix_fmt", "yuv420p",
               "-c:a", "aac", "-b:a", "96k", "-tls_verify", "1", "-ca_file", "/run/ca.crt",
               "-f", "flv", "rtmps://media-server:1936/live/" + self.key)

    def stop(self):
        if self.name:
            docker("stop", "-t", "2", self.name, check=False)
            self.name = None


def media_offset(timeline, session_ms):
    """Same rule as VOD's MapOffset (ADR-022): gaps go to the next seam, the tail to the last ms."""
    for interval in timeline["intervals"]:
        if session_ms < interval["sessionStartMs"]:
            return interval["mediaStartMs"]
        if session_ms < interval["sessionStartMs"] + interval["durationMs"]:
            return interval["mediaStartMs"] + session_ms - interval["sessionStartMs"]
    return timeline["durationMs"] - 1


def prepare_stream(owner, channel_id, category_id, existing=False):
    title = "Replay de chat E2E " + secrets.token_hex(3)
    _, config = owner.call(f"/api/channels/{channel_id}/streams", "POST",
                          {"title": title, "categoryId": category_id, "tagIds": []},
                          {"Idempotency-Key": str(uuid.uuid4())}, 200 if existing else 201)
    if existing:
        assert not config.get("streamKey"), "existing configuration must not reveal its secret"
        owner.mutate(f"/api/streams/{config['streamId']}", "PATCH", {"title": title})
        # Previous fixture ended the session. Rotate explicitly; never assume POST returns a key twice.
        _, key = owner.mutate(f"/api/streams/{config['streamId']}/ingest-keys/rotate")
        assert key["streamId"] == config["streamId"]
        return config, key["streamKey"]
    return config, config["streamKey"]


def main(keep=False, minimum_seconds=0, existing_owner=None):
    if TLS is None:
        raise RuntimeError("Call configure before running the replay scenario")
    anon, owner, fan = Client(), Client(), Client()
    password = "Ficticia-" + secrets.token_urlsafe(12)
    handles = {}
    account = None
    for role, client in (("owner", owner), ("fan", fan)):
        if role == "owner" and existing_owner:
            owner, account, password = existing_owner["client"], existing_owner["owner"], existing_owner["password"]
            handles[role] = existing_owner["handle"]
            continue
        handle = "chre2e_" + role[0] + secrets.token_hex(4)
        _, registered = client.mutate("/api/identity/registrations", body={"email": handle + "@example.test", "handle": handle, "password": password}, expect=201)
        if role == "owner":
            account = registered
        client.mutate("/api/identity/sessions", body={"login": handle, "password": password})
        handles[role] = handle
    _, channel = owner.call("/api/channels/by-handle/" + handles["owner"])
    channel_id = channel["channel"]["channelId"]
    _, settings = owner.call(f"/api/channels/{channel_id}/vod-settings")
    if not existing_owner:
        assert settings["enabled"], "autopublish is enabled by default"
    # Draft first: the replay must follow VOD readability before and after publication.
    owner.mutate(f"/api/channels/{channel_id}/vod-settings", "PUT", {"enabled": False, "expectedVersion": settings["version"]})
    _, catalog = anon.call("/api/taxonomy")
    config, ingest_key = prepare_stream(owner, channel_id, catalog["categories"][0]["id"], bool(existing_owner))
    publisher = Publisher(ingest_key)
    sockets = []
    try:
        publisher.start()
        live = eventually(lambda: anon.call("/api/streams/" + config["streamId"])[1], lambda v: v["availability"] == "PLAYABLE", 90)
        media_started = time.monotonic()
        sid = live["sessionId"]
        print("PASS RTMPS source is PLAYABLE", flush=True)
        fan_ws, owner_ws = Socket("/realtime/chat/sessions/" + sid, fan.credential()), Socket("/realtime/chat/sessions/" + sid, owner.credential())
        sockets += [fan_ws, owner_ws]
        fan_ws.expect("chat.ready")
        owner_ws.expect("chat.ready")
        sent = {}

        def say(ws, text, required=True):
            message = {"type": "message.send", "clientMessageId": str(uuid.uuid4()), "text": text}
            ws.send(message)
            value = ws.first(lambda v: v.get("clientMessageId") == message["clientMessageId"] and v["type"] in ("message.accepted", "error"))
            if value["type"] == "error":
                if required:
                    raise AssertionError(f"send '{text}' rejected: {value.get('code')}")
                return value["code"]
            sent[text] = value
            time.sleep(1.2)  # one message per second per account
            return None

        time.sleep(4)
        say(fan_ws, "uno")
        say(owner_ws, "hola a todos")
        say(fan_ws, "dos (será retirado)")
        say(fan_ws, "tres")
        print("PASS chat messages accepted during the first generation", flush=True)

        # Reconnect cut: the session stays in grace and a new source generation follows.
        publisher.stop()
        eventually(lambda: anon.call("/api/streams/sessions/" + sid)[1], lambda v: v["availability"] == "RECONNECTING", 25)
        during_cut = say(fan_ws, "durante el corte", required=False)
        print("INFO message during the reconnect cut: " + ("accepted" if during_cut is None else "rejected " + during_cut), flush=True)
        publisher.start()
        eventually(lambda: anon.call("/api/streams/sessions/" + sid)[1], lambda v: v["status"] == "LIVE" and v["availability"] == "PLAYABLE", 25)
        time.sleep(4)
        say(fan_ws, "después del corte")
        say(owner_ws, "gracias por venir")
        time.sleep(4)
        print("PASS reconnect within grace kept the session and chat", flush=True)

        removed = sent["dos (será retirado)"]["messageId"]
        owner.mutate(f"/api/chat/sessions/{sid}/messages/{removed}", "DELETE")
        print("PASS owner removed a message", flush=True)
        # Allow the native recorder to collect enough playable media, excluding the cut.
        while time.monotonic() - media_started < minimum_seconds + 12:
            time.sleep(1)
        owner.call("/api/streams/sessions/" + sid, "DELETE", expect=204)
        publisher.stop()
        eventually(lambda: anon.call("/api/streams/sessions/" + sid)[1], lambda v: v["status"] == "ENDED", 30)
        for ws in sockets:
            ws.close()
        sockets.clear()

        accepted = [text for text in sent]
        eventually(lambda: chat_sql(f"SELECT count(*) FROM chat_messages WHERE session_id={quoted(sid)}"), lambda v: v == str(len(accepted)), 30)
        print(f"PASS {len(accepted)} messages archived in Chat PostgreSQL", flush=True)

        vod = eventually(lambda: next((v for v in owner.call("/api/me/vods")[1]["items"] if v["sessionId"] == sid), None),
                         lambda v: v is not None and v["processingState"] in ("READY", "FAILED"), 300, 2)
        assert vod["processingState"] == "READY", f"VOD processing failed: {vod.get('failureCode')}"
        vod_id = vod["vodId"]
        print(f"PASS VOD {vod['processingState']} {vod['publicationState']} durationMs={vod['durationMs']} mappingVersion={vod['mappingVersion']}", flush=True)
        eventually(lambda: chat_sql(f"SELECT publication_state || ':' || mapping_version FROM chat_vod_links WHERE vod_id={quoted(vod_id)}"),
                   lambda v: v == f"DRAFT:{vod['mappingVersion']}", 60)
        print("PASS Chat consumed vod.availability.changed.v1 (draft link)", flush=True)

        window = f"/api/chat/vods/{vod_id}/messages?fromMs=0&toMs={min(vod['durationMs'], 300000)}&limit=50"
        status, _ = anon.call(window, expect=None)
        assert status == 404, f"an anonymous viewer cannot read a draft replay: {status}"
        status, draft_page = owner.call(window)
        assert draft_page["items"], "the owner reads the draft replay"
        print("PASS draft replay: owner 200, anonymous 404 (VOD decides)", flush=True)

        _, vod = owner.mutate(f"/api/vods/{vod_id}/publication", body={"expectedVersion": vod["version"]})
        eventually(lambda: chat_sql(f"SELECT available::text FROM chat_vod_links WHERE vod_id={quoted(vod_id)}"), lambda v: v == "true", 60)
        _, timeline = anon.call(f"/api/vods/{vod_id}/timeline")
        _, page = anon.call(window)
        (STATE / "chat-replay-e2e-page.json").write_text(json.dumps(page))
        texts = [item["text"] for item in page["items"]]
        expected = [text for text in accepted if text != "dos (será retirado)"]
        assert texts == expected, f"replay order/content: {texts} != {expected}"
        assert page["mappingVersion"] == timeline["mappingVersion"] and page["nextCursor"] is None
        for item in page["items"]:
            assert item["mediaOffsetMs"] == media_offset(timeline, item["streamOffsetMs"]), item
            assert 0 <= item["mediaOffsetMs"] < vod["durationMs"]
        sequences = [item["sequence"] for item in page["items"]]
        assert sequences == sorted(sequences) and page["moderationVersion"] >= 2
        gaps = len(timeline["intervals"]) - 1
        print(f"PASS published replay: {len(texts)} messages, removed one omitted, mediaOffsetMs matches P2Timeline "
              f"({len(timeline['intervals'])} intervals, {gaps} compacted cut)", flush=True)
        for item in page["items"]:
            print(f"      seq={item['sequence']} gen={item['streamGeneration']} session={item['streamOffsetMs']}ms -> video={item['mediaOffsetMs']}ms  {item['text']}", flush=True)

        paged, cursor = [], None
        while True:
            _, part = anon.call(window.replace("limit=50", "limit=2") + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else ""))
            paged += part["items"]
            cursor = part["nextCursor"]
            if not cursor:
                break
        assert [i["messageId"] for i in paged] == [i["messageId"] for i in page["items"]]
        target = page["items"][len(page["items"]) // 2]
        start = max(0, target["mediaOffsetMs"] - 500)
        _, seek = anon.call(f"/api/chat/vods/{vod_id}/messages?fromMs={start}&toMs={target['mediaOffsetMs'] + 1}")
        assert target["messageId"] in [i["messageId"] for i in seek["items"]]
        assert all(start <= i["mediaOffsetMs"] <= target["mediaOffsetMs"] for i in seek["items"])
        print("PASS pagination with cursor (limit=2) and seek window", flush=True)

        if keep:
            print(f"KEEP published VOD for manual review: {ORIGIN}/vod/{vod_id} (channel {handles['owner']})", flush=True)
            return {"client": owner, "owner": account, "handle": handles["owner"], "password": password,
                    "vod": vod, "messages": page["items"], "timeline": timeline}
        _, current = owner.call(f"/api/me/vods/{vod_id}")
        owner.mutate(f"/api/vods/{vod_id}", "DELETE", headers={"If-Match": f'"{current["version"]}"'}, expect=202)
        eventually(lambda: anon.call(window, expect=None)[0], lambda v: v == 404, 60)
        eventually(lambda: chat_sql(f"SELECT count(*) FROM chat_sessions WHERE session_id={quoted(sid)}"), lambda v: v == "0", 60)
        print("PASS VOD deletion: replay 404 and Chat history deleted (cleanup delegated to Chat)", flush=True)
    finally:
        publisher.stop()
        for ws in sockets:
            try:
                ws.close()
            except OSError:
                pass


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=STATE)
    parser.add_argument("--project", default=PROJECT)
    parser.add_argument("--network")
    parser.add_argument("--origin")
    parser.add_argument("--live-image", default=LIVE_IMAGE)
    parser.add_argument("--keep", action="store_true")
    args = parser.parse_args()
    configure(args.state, args.project, args.network, args.origin, args.live_image)
    main(keep=args.keep)
