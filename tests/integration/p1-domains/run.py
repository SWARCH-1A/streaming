"""SPEC-10/11 real-provider integration. Own isolated Compose project; never seeds LIVE via SQL.

Requires Docker Compose >=2.24, Python dependencies from requirements.txt, and free loopback ports
18080/18081/18082/18085/18086/18888/11935/15438/15440. Fresh disposable stores per run.
"""
from __future__ import annotations

import argparse
from contextlib import nullcontext
import http.cookiejar
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import websocket

ROOT = Path(__file__).resolve().parents[3]
FIXTURE = Path(__file__).resolve().parent
PROJECT = "streaming-p1-domains"
ORIGIN = "http://localhost:3000"
CORE, STREAMING, CHAT = "http://localhost:18081", "http://localhost:18080", "http://localhost:18085"
PRIVATE = "http://localhost:18082"


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.cookies))

    def call(self, base, path, method="GET", body=None, headers=None, expect=200):
        request = urllib.request.Request(base + path, method=method, headers={"Accept": "application/json", **(headers or {})},
                                         data=json.dumps(body).encode() if body is not None else None)
        if body is not None:
            request.add_header("Content-Type", "application/json")
        try:
            response = self.http.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            if response.status != expect:
                # Do not dump payloads: owner config contains an ingest secret.
                code = json.loads(raw).get("code", "HTTP_ERROR") if raw.startswith(b"{") else "HTTP_ERROR"
                raise AssertionError(f"{method} {path}: expected {expect}, got {response.status} ({code})")
            return json.loads(raw) if raw else None

    def mutate(self, base, path, method="POST", body=None, expect=200, headers=None):
        if base == CORE:
            csrf = self.call(CORE, "/api/identity/csrf")
            headers = {**(headers or {}), csrf["headerName"]: csrf["token"]}
        return self.call(base, path, method, body, {"Origin": ORIGIN, **(headers or {})}, expect)

    def credential(self):
        return next(cookie.value for cookie in self.cookies if cookie.name == "stream_session")


def eventually(read, predicate, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = read()
            if predicate(value):
                return value
        except (urllib.error.URLError, http.client.RemoteDisconnected, TimeoutError, ConnectionError, AssertionError):
            pass
        time.sleep(.25)
    raise AssertionError("Authoritative state did not converge within its test deadline")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--streaming-image", help="Reuse an already built image of the unchanged current Streaming sources")
    parser.add_argument("--skip-build", action="store_true", help="Only after building all current sources with this fixture")
    parser.add_argument("--build-only", action="store_true", help="Build current sources, then leave the disposable fixture stopped")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("p1contracts", ROOT / "contracts/generate.py")
    contracts = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(contracts)
    bundle, _ = contracts.load()
    checked = 0

    def validate(name, payload, public=True):
        nonlocal checked
        contracts.validate_payload(bundle, name, payload, public)
        checked += 1
        return payload

    env = os.environ.copy()
    env.update({key: secrets.token_urlsafe(32) for key in (
        "CORE_DB_PASSWORD", "CORE_RATE_LIMIT_HMAC_SECRET", "CORE_STREAMING_SERVICE_TOKEN", "CORE_STREAMING_CATALOG_SERVICE_TOKEN",
        "CORE_STREAMING_CONSUMER_TOKEN", "CHAT_CORE_SERVICE_TOKEN", "CHAT_SESSION_EVENTS_TOKEN", "MEDIA_AUTH_TOKEN", "MEDIA_HLS_SECRET",
        "MEDIA_CONTROL_PASSWORD", "MEDIA_DATABASE_PASSWORD", "STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN")})
    env.update({"CORE_PORT": "18081", "CORE_DB_PORT": "15440", "CHAT_PORT": "18085", "CHAT_INTERNAL_PORT": "18086",
                "STREAMING_HTTP_PORT": "18080", "STREAMING_HLS_PORT": "18888", "STREAMING_RTMP_PORT": "11935", "STREAMING_DB_PORT": "15438", "WEB_ORIGIN": ORIGIN})
    if args.streaming_image:
        env["P1_STREAMING_IMAGE"] = args.streaming_image
    command = ["docker", "compose", "-p", PROJECT, "-f", str(FIXTURE / "compose.yaml")]
    publisher = None
    connections = []
    with nullcontext(tempfile.mkdtemp(prefix="streaming-p1-domains-", dir="/private/tmp")) as scratch:
        with open(Path(scratch) / "docker.log", "w+") as log:
            def compose(*args, capture=False):
                result = subprocess.run([*command, *args], cwd=ROOT, env=env, text=True,
                                        stdout=subprocess.PIPE if capture else log, stderr=log)
                if result.returncode:
                    raise RuntimeError(f"Compose {args[0]} failed; isolated log: {log.name}")
                return result.stdout

            def publish(config):
                url = "rtmp://mediamtx:1935/live/" + config["streamId"] + "?user=publisher&pass=" + urllib.parse.quote(config["streamKey"], safe="")
                return subprocess.Popen([*command, "exec", "-T", "streaming", "sh", "-c",
                    'echo $$ > /tmp/p1-source.pid; exec "$@"', "p1", "ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
                    "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25", "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
                    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "25", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-f", "flv", url], cwd=ROOT, env=env, stdout=log, stderr=log)

            def stop_publisher():
                nonlocal publisher
                if publisher is not None:
                    compose("exec", "-T", "streaming", "sh", "-c", 'kill -INT "$(cat /tmp/p1-source.pid)"')
                    publisher.wait(timeout=10)
                    publisher = None

            def ws(client, sid):
                connection = websocket.create_connection(CHAT.replace("http:", "ws:") + "/realtime/chat/sessions/" + sid,
                    origin=ORIGIN, cookie="stream_session=" + client.credential(), timeout=5, http_proxy_host=None)
                connections.append(connection)
                ready = json.loads(connection.recv())
                validate("ChatReady", ready)
                assert ready["type"] == "chat.ready"
                return connection

            def frame(connection, kind, predicate=lambda value: True):
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    value = json.loads(connection.recv())
                    if value["type"] == kind and predicate(value):
                        return value
                raise AssertionError("Expected WebSocket frame not received")

            try:
                # Remove only this fixed test project's disposable stores, also after a previous interrupted run.
                compose("down", "--volumes", "--remove-orphans")
                if not args.skip_build:
                    compose("build", "core", "chat", *([] if args.streaming_image else ["streaming"]))
                if args.build_only:
                    return
                compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "120")
                anonymous = Client()
                for base, path in [(CORE, "/actuator/health"), (STREAMING, "/health/ready"), (CHAT, "/readyz")]:
                    eventually(lambda: anonymous.call(base, path), lambda value: value is not None)
                owner = Client()
                account = owner.mutate(CORE, "/api/identity/registrations", body={"email": "p1fixture@example.test", "handle": "p1_fixture", "password": "fixture-only complex password"}, expect=201,
                                       headers={"Idempotency-Key": str(uuid.uuid4())})
                validate("Registration", account)
                owner.mutate(CORE, "/api/identity/sessions", body={"login": "p1_fixture", "password": "fixture-only complex password"})
                catalog = validate("Taxonomy", anonymous.call(CORE, "/api/taxonomy"))
                tags = [tag["id"] for tag in catalog["tags"][:6]]
                assert len(tags) == 6
                channel = account["channelId"]
                bootstrap_path = "/api/channels/by-handle/p1_fixture"
                absent = validate("ChannelBootstrap", anonymous.call(CORE, bootstrap_path))
                assert absent["availability"] == "OFFLINE" and absent["streamStatusFresh"] and absent["stream"] is None
                config = owner.mutate(STREAMING, "/api/channels/" + channel + "/streams", body={"title": "Integración real", "categoryId": "cat_00000000000000000000000000000001", "tagIds": []},
                                      expect=201, headers={"Idempotency-Key": str(uuid.uuid4())})
                configured = validate("ChannelBootstrap", anonymous.call(CORE, bootstrap_path))
                assert configured["stream"]["streamId"] == config["streamId"] and configured["stream"]["session"] is None
                publisher = publish(config)
                live = eventually(lambda: validate("ChannelBootstrap", anonymous.call(CORE, bootstrap_path)), lambda b: b["availability"] == "PLAYABLE")
                sid = live["stream"]["sessionId"]
                assert live["stream"]["session"]["sessionId"] == sid
                metadata = owner.mutate(STREAMING, "/api/streams/" + config["streamId"], "PATCH", {"tagIds": tags[:5]})
                version = metadata["metadataVersion"]
                owner.mutate(STREAMING, "/api/streams/" + config["streamId"], "PATCH", {"tagIds": tags}, expect=422)
                unchanged = owner.call(STREAMING, "/api/channels/" + channel + "/streams")
                assert unchanged["metadataVersion"] == version and unchanged["tagIds"] == tags[:5]
                query = json.loads((ROOT / "contracts/generated/graphql-queries.json").read_text())[0]["query"]

                def streams(variables):
                    response = anonymous.call(CORE, "/api/discovery/graphql", "POST", {"query": query, "variables": variables})
                    validate("GraphQLResponse", response)
                    assert not response.get("errors")
                    return {item["streamId"] for item in response["data"]["streams"]["items"]}

                eventually(lambda: streams({"q": "  INTEGRACIÓN ", "categoryId": config["categoryId"], "tagId": tags[0]}), lambda ids: ids == {config["streamId"]})
                assert streams({"categoryId": config["categoryId"], "tagId": tags[5]}) == set()
                # Decode an actual frame; fetching a manifest alone is insufficient playback evidence.
                compose("exec", "-T", "streaming", "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", "http://127.0.0.1:8888/hls/" + sid + "/index.m3u8", "-frames:v", "1", "-f", "null", "-")
                lease = validate("ViewerLease", anonymous.call(STREAMING, "/api/streams/sessions/" + sid + "/viewer-leases", "POST", headers={"Idempotency-Key": str(uuid.uuid4())}, expect=201), False)
                anonymous.call(STREAMING, "/api/streams/viewer-leases/" + lease["leaseId"] + "/heartbeat", "PUT", headers={"Authorization": "ViewerLease " + lease["leaseToken"]})
                eventually(lambda: anonymous.call(STREAMING, "/api/streams/sessions/" + sid), lambda s: s["viewerCount"] == 1)
                anonymous.call(STREAMING, "/api/streams/viewer-leases/" + lease["leaseId"], "DELETE", headers={"Authorization": "ViewerLease " + lease["leaseToken"]}, expect=204)
                eventually(lambda: anonymous.call(STREAMING, "/api/streams/sessions/" + sid), lambda s: s["viewerCount"] == 0)
                service_headers = {"X-Service-Name": "chat", "X-Service-Token": env["CHAT_CORE_SERVICE_TOKEN"], "X-Session-Credential": owner.credential()}
                context = validate("ChatContext", owner.call(PRIVATE, "/internal/core/chat/message-context", "POST", {"sessionId": sid, "clientMessageId": str(uuid.uuid4())}, service_headers), False)
                assert context["writeAllowed"] and context["timelinePositionMs"] > 0
                validate("ChatSnapshot", anonymous.call(PRIVATE, "/internal/core/chat/sessions/" + sid, headers=service_headers), False)
                connection = ws(owner, sid)
                cmid = str(uuid.uuid4())
                send = {"type": "message.send", "clientMessageId": cmid, "text": "Hola integración"}
                connection.send(json.dumps(send))
                ack = validate("MessageAccepted", frame(connection, "message.accepted"))
                history_path = "/api/chat/sessions/" + sid + "/messages"
                history = validate("ChatHistory", anonymous.call(CHAT, history_path))
                assert history["snapshotSequence"] == ack["sequence"] == 1 and history["items"][0]["text"] == send["text"]
                connection.send(json.dumps(send))
                assert frame(connection, "message.accepted") == ack
                owner.mutate(CORE, "/api/profile/me", "PATCH", {"displayName": "Autora actualizada"})
                time.sleep(1.05)
                send["clientMessageId"] = str(uuid.uuid4())
                connection.send(json.dumps(send)); frame(connection, "message.accepted")
                changed = anonymous.call(CHAT, history_path)["items"]
                assert changed[0]["author"]["displayName"] != changed[1]["author"]["displayName"] == "Autora actualizada"
                # Redis/AOF and Chat restart retain ACK, sequence, dedupe and author snapshots.
                for connection in connections: connection.close()
                connections.clear()
                compose("restart", "chat-redis", "chat")
                eventually(lambda: anonymous.call(CHAT, "/readyz"), lambda value: value["status"] == "UP")
                recovered = validate("ChatHistory", anonymous.call(CHAT, history_path))
                assert recovered["items"] == changed and recovered["snapshotSequence"] == 2
                connection = ws(owner, sid)
                # Chat outage cannot interrupt HLS. Its independent durable lifecycle outbox catches up.
                connection.close(); connections.clear(); compose("stop", "chat")
                compose("exec", "-T", "streaming", "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", "http://127.0.0.1:8888/hls/" + sid + "/index.m3u8", "-frames:v", "1", "-f", "null", "-")
                stop_publisher()
                grace = eventually(lambda: anonymous.call(CORE, bootstrap_path), lambda b: b["availability"] == "RECONNECTING")
                assert grace["stream"]["sessionId"] == sid
                eventually(lambda: streams({}), lambda ids: ids == set())
                publisher = publish(config)
                recovered_live = eventually(lambda: anonymous.call(CORE, bootstrap_path), lambda b: b["availability"] == "PLAYABLE")
                assert recovered_live["stream"]["sessionId"] == sid
                compose("start", "chat")
                eventually(lambda: anonymous.call(CHAT, "/readyz"), lambda value: value["status"] == "UP")
                assert anonymous.call(CHAT, history_path)["snapshotSequence"] == 2
                connection = ws(owner, sid)
                compose("stop", "core")
                connection.send(json.dumps({"type": "message.send", "clientMessageId": str(uuid.uuid4()), "text": "sin Core"}))
                assert frame(connection, "error")["code"] == "CORE_UNAVAILABLE"
                assert anonymous.call(CHAT, history_path)["snapshotSequence"] == 2
                compose("exec", "-T", "streaming", "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", "http://127.0.0.1:8888/hls/" + sid + "/index.m3u8", "-frames:v", "1", "-f", "null", "-")
                compose("start", "core")
                eventually(lambda: anonymous.call(CORE, "/actuator/health"), lambda h: h["status"] == "UP")
                owner.mutate(CORE, "/api/identity/sessions/current", "DELETE", expect=204)
                owner.mutate(CORE, "/api/profile/me", "PATCH", {"displayName": "revocado"}, expect=401)
                owner.mutate(STREAMING, "/api/streams/" + config["streamId"], "PATCH", {"title": "revocado"}, expect=401)
                connection.send(json.dumps({"type": "message.send", "clientMessageId": str(uuid.uuid4()), "text": "revocado"}))
                assert frame(connection, "error")["code"] == "AUTH_REQUIRED"
                assert anonymous.call(CHAT, history_path)["snapshotSequence"] == 2
                connection.close(); connections.clear()
                owner.mutate(CORE, "/api/identity/sessions", body={"login": "p1_fixture", "password": "fixture-only complex password"})
                connection = ws(owner, sid)
                # Streaming failure leaves local channel data intact, never invents authoritative OFFLINE.
                compose("stop", "streaming")
                unknown = validate("ChannelBootstrap", anonymous.call(CORE, bootstrap_path))
                assert unknown["availability"] == "UNKNOWN" and not unknown["streamStatusFresh"] and unknown["profile"]["displayName"] == "Autora actualizada"
                connection.send(json.dumps({"type": "message.send", "clientMessageId": str(uuid.uuid4()), "text": "sin Streaming"}))
                assert frame(connection, "error")["code"] == "STREAMING_UNAVAILABLE"
                assert anonymous.call(CHAT, history_path)["snapshotSequence"] == 2
                compose("start", "streaming")
                eventually(lambda: anonymous.call(STREAMING, "/health/ready"), lambda value: value is not None)
                ended = eventually(lambda: anonymous.call(CHAT, history_path), lambda h: h["roomStatus"] == "READ_ONLY", timeout=40)
                assert ended["snapshotSequence"] == 2
                eventually(lambda: streams({}), lambda ids: ids == set())
                print(f"PASS real Core/Streaming/MediaMTX/Chat/Redis: HLS frame, channel states, fresh context/author, WS dedupe, AOF restart, grace, independent delivery, logout, UNKNOWN, fail-closed outages, leases, taxonomy limits and Discovery AND/normalization/exact sets; {checked} neutral payload validations")
            except BaseException:
                # Diagnostic data stays in the private temporary folder; never publish it unreviewed.
                subprocess.run([*command,"logs","--no-color","--tail","100"],cwd=ROOT,env=env,stdout=log,stderr=log)
                raise
            finally:
                for connection in connections: connection.close()
                if publisher is not None:
                    try: stop_publisher()
                    except (RuntimeError, subprocess.TimeoutExpired): publisher.terminate()
                compose("down", "--volumes", "--remove-orphans")


if __name__ == "__main__":
    main()
