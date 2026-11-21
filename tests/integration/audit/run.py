#!/usr/bin/env python3
"""Disposable Core/VOD/RabbitMQ/PostgreSQL security audit acceptance. Never uses local volumes."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import secrets
import shutil
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def run(*args, data=None, check=True):
    p = subprocess.run(args, cwd=ROOT, input=data, capture_output=True, text=True)
    if check and p.returncode:
        # Container arguments include fixture secrets; never print the command or env.
        raise RuntimeError(f"{args[0]} failed ({p.returncode}): {p.stderr[-2000:]}")
    return p


def wait(check, label, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, ValueError, RuntimeError, AssertionError, urllib.error.URLError):
            pass
        time.sleep(.15)
    raise RuntimeError("Timeout: " + label)


def call(base, path, method="GET", body=None, token=None, headers=None, expected=200):
    h = dict(headers or {})
    if token:
        h["Authorization"] = "Bearer " + token
    if body is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, method=method, headers=h,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        res = HTTP.open(req, timeout=8)
    except urllib.error.HTTPError as error:
        res = error
    raw = res.read()
    assert res.code == expected, f"{method} {path}: {res.code}, expected {expected}: {raw[:300]!r}"
    if "application/json" in res.headers.get("Content-Type", ""):
        value = json.loads(raw)
        if isinstance(value, dict) and "requestId" in value and res.headers.get("X-Request-Id"):
            assert value["requestId"] == res.headers["X-Request-Id"], "Response correlation differs from header"
        return value
    return raw.decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Reuse images built from this exact checkout")
    args = parser.parse_args()
    global HTTP
    images = {name: "streaming-audit-test-" + name for name in ("core", "vod", "audit")}
    if not args.skip_build:
        for name, image in images.items():
            print("Building " + name, flush=True)
            run("docker", "build", "-q", "-t", image, "services/" + name)
    prefix = "audit-acceptance-" + uuid.uuid4().hex[:10]
    names = []
    with tempfile.TemporaryDirectory(prefix=prefix) as folder:
        state = Path(folder)
        # Docker Desktop binds require the canonical macOS /private/tmp path.
        state = state.resolve()
        password = secrets.token_urlsafe(36)
        writer = secrets.token_urlsafe(36)
        reader = secrets.token_urlsafe(36)
        query_token = secrets.token_urlsafe(36)
        vod_token = secrets.token_urlsafe(36)
        capture_token = secrets.token_urlsafe(36)
        gateway_token = secrets.token_urlsafe(36)
        stream_token = secrets.token_urlsafe(36)
        roles = ("streaming", "core", "chat", "vod", "notifications", "recovery-email", "audit", "operator")
        passwords = {role: secrets.token_urlsafe(36) for role in roles}
        spec = importlib.util.spec_from_file_location("audit_topology", ROOT / "infra/broker/topology.py")
        topology = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(topology)
        (state / "definitions.json").write_text(json.dumps(topology.definitions(passwords)))
        (state / "rabbitmq.conf").write_text(
            "listeners.tcp = none\nlisteners.ssl.default = 5671\n"
            "ssl_options.cacertfile = /fixture/ca.crt\nssl_options.certfile = /fixture/broker.crt\n"
            "ssl_options.keyfile = /fixture/broker.key\nssl_options.verify = verify_none\n"
            "definitions.import_backend = local_filesystem\ndefinitions.local.path = /fixture/definitions.json\n"
            "disk_free_limit.absolute = 100MB\n")
        # TLS on the actual broker path; development HTTP/SQL remains confined to this test network.
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=AuditFixtureCA",
            "-keyout", str(state / "ca.key"), "-out", str(state / "ca.crt"))
        run("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=broker",
            "-keyout", str(state / "broker.key"), "-out", str(state / "broker.csr"))
        (state / "broker.ext").write_text("subjectAltName=DNS:broker\nextendedKeyUsage=serverAuth\n")
        run("openssl", "x509", "-req", "-in", str(state / "broker.csr"), "-CA", str(state / "ca.crt"),
            "-CAkey", str(state / "ca.key"), "-CAcreateserial", "-days", "1", "-extfile", str(state / "broker.ext"), "-out", str(state / "broker.crt"))
        for service in ("audit", "audit-db"):
            run("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=" + service,
                "-keyout", str(state / (service + ".key")), "-out", str(state / (service + ".csr")))
            (state / (service + ".ext")).write_text("subjectAltName=DNS:" + service + ",DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n")
            run("openssl", "x509", "-req", "-in", str(state / (service + ".csr")), "-CA", str(state / "ca.crt"),
                "-CAkey", str(state / "ca.key"), "-CAcreateserial", "-days", "1", "-extfile", str(state / (service + ".ext")), "-out", str(state / (service + ".crt")))
        HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(state / "ca.crt"))))
        # Only public trust / broker materials go into container mounts, never the CA private key.
        shared = state / "shared"
        shared.mkdir(mode=0o755)
        for filename in ("ca.crt", "broker.crt", "broker.key", "rabbitmq.conf", "definitions.json", "audit.crt", "audit.key", "audit-db.crt", "audit-db.key"):
            shutil.copyfile(state / filename, shared / filename)
            (shared / filename).chmod(0o644)
        # The fixture parent is private on the host; non-root container runtimes can traverse its bind root.
        run("docker", "run", "--rm", "--user", "0", "--entrypoint", "keytool", "-v", f"{shared}:/fixture",
            images["core"], "-importcert", "-noprompt", "-alias", "audit-test", "-file", "/fixture/ca.crt",
            "-keystore", "/fixture/truststore.p12", "-storetype", "PKCS12", "-storepass", "changeit")
        (shared / "truststore.p12").chmod(0o644)

        def start(name, image, env=None, mounts=(), ports=(), command=(), options=()):
            cname = prefix + "-" + name
            command_line = ["docker", "run", "-d", "--name", cname, "--network", prefix, "--network-alias", name]
            for k, v in (env or {}).items():
                command_line += ["-e", k + "=" + v]
            for mount in mounts:
                command_line += ["-v", mount]
            for port in ports:
                command_line += ["-p", "127.0.0.1::" + str(port)]
            command_line += [*options, image, *command]
            run(*command_line)
            names.append(cname)
            return cname

        def address(name, port):
            binding = run("docker", "port", prefix + "-" + name, str(port) + "/tcp").stdout.strip()
            return "http://" + binding

        def sql(name, query, check=True, user=None, secret=None):
            command = ["docker", "exec", "-i"]
            if secret:
                command += ["-e", "PGPASSWORD=" + secret]
            command += [prefix + "-" + name, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-U", user or ("audit_owner" if name == "audit-db" else "fixture"), "-d", "audit" if name == "audit-db" else "fixture"]
            if secret:
                command += ["-h", "127.0.0.1"]
            return run(*command, data=query, check=check)

        try:
            run("docker", "network", "create", prefix)
            for name in ("core-db", "vod-db"):
                start(name, "postgres:18-alpine", {"POSTGRES_DB": "fixture", "POSTGRES_USER": "fixture", "POSTGRES_PASSWORD": password})
            start("audit-db", "postgres:18-alpine", {"POSTGRES_DB": "audit", "POSTGRES_USER": "audit_owner", "POSTGRES_PASSWORD": password,
                  "AUDIT_WRITER_DB_PASSWORD": writer, "AUDIT_READER_DB_PASSWORD": reader},
                  mounts=(f"{ROOT}/infra/audit/schema.sql:/docker-entrypoint-initdb.d/10-audit.sql:ro",
                          f"{ROOT}/infra/p1/postgres-tls.sh:/postgres-tls.sh:ro", f"{shared}/audit-db.crt:/run/secrets/server.crt:ro", f"{shared}/audit-db.key:/run/secrets/server.key:ro"),
                  options=("--entrypoint", "/bin/sh"), command=("/postgres-tls.sh", "postgres", "-c", "ssl=on", "-c", "ssl_cert_file=/tmp/p1-tls/server.crt", "-c", "ssl_key_file=/tmp/p1-tls/server.key"))
            for name in ("core-db", "vod-db", "audit-db"):
                wait(lambda name=name: sql(name, "SELECT 1").stdout.strip() == "1", name)
            wait(lambda: sql("audit-db", "SELECT to_regclass('audit.events') IS NOT NULL").stdout.strip() == "t", "audit schema")
            # Exercise SQL permissions/concurrency with the same runtime roles used by Logger/Auditor.
            print("Testing Logger validation, SQL privileges, deduplication and pagination", flush=True)
            run("docker", "run", "--rm", "--network", prefix, "-v", f"{ROOT}/services/audit:/src", "-w", "/src",
                "-v", "streaming-audit-go-mod:/go/pkg/mod", "-v", "streaming-audit-go-build:/root/.cache/go-build",
                "-e", f"AUDIT_TEST_WRITER_URL=postgres://audit_writer:{writer}@audit-db/audit?sslmode=disable",
                "-e", f"AUDIT_TEST_READER_URL=postgres://audit_reader:{reader}@audit-db/audit?sslmode=disable",
                "golang:1.26-alpine", "go", "test", "./...", "-count=1")
            sql("audit-db", "TRUNCATE audit.events,audit.reads RESTART IDENTITY")
            start("broker", "rabbitmq:4.3.7-management", mounts=(f"{shared}:/fixture:ro", f"{shared}/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro"))
            wait(lambda: run("docker", "exec", prefix + "-broker", "gosu", "rabbitmq", "rabbitmq-diagnostics", "-q", "check_running", check=False).returncode == 0, "broker", 120)
            broker = lambda role: f"amqps://{role}:{passwords[role]}@broker:5671/streaming-p2"
            run("docker", "run", "--rm", "--network", prefix, "-v", f"{ROOT}/services/audit:/src", "-w", "/src",
                "-v", f"{shared}:/fixture:ro", "-v", "streaming-audit-go-mod:/go/pkg/mod", "-v", "streaming-audit-go-build:/root/.cache/go-build",
                "-e", "AUDIT_TEST_VOD_BROKER_URL=" + broker("vod"), "-e", "AUDIT_TEST_BROKER_CA=/fixture/ca.crt",
                "golang:1.26-alpine", "go", "test", "./internal/audit", "-run", "TestBrokerProducerIsolation", "-count=1")
            start("audit", images["audit"], {"AUDIT_WRITER_DATABASE_URL": f"postgres://audit_writer:{writer}@audit-db/audit?sslmode=verify-full&sslrootcert=/fixture/ca.crt",
                  "AUDIT_READER_DATABASE_URL": f"postgres://audit_reader:{reader}@audit-db/audit?sslmode=verify-full&sslrootcert=/fixture/ca.crt", "AUDIT_QUERY_TOKEN": query_token,
                  "AUDIT_TLS_CERT_FILE": "/fixture/audit.crt", "AUDIT_TLS_KEY_FILE": "/fixture/audit.key", "SSL_CERT_FILE": "/fixture/ca.crt",
                  "AUDIT_BROKER_URL": broker("audit"), "AUDIT_BROKER_CA_FILE": "/fixture/ca.crt"}, mounts=(f"{shared}:/fixture:ro",), ports=(8096,))
            audit_base = address("audit", 8096).replace("http://", "https://")
            start("core", images["core"], {
                "CORE_DB_URL": "jdbc:postgresql://core-db:5432/fixture", "CORE_DB_USER": "fixture", "CORE_DB_PASSWORD": password,
                "CORE_RATE_LIMIT_HMAC_SECRET": secrets.token_urlsafe(36), "CORE_IMAGE_STORAGE_PROVIDER": "filesystem",
                "CORE_INTERNAL_ENABLED": "true", "CORE_INTERNAL_DEVELOPMENT_HTTP": "true", "CORE_STREAMING_SERVICE_TOKEN": stream_token,
                "CORE_VOD_SERVICE_TOKEN": vod_token, "CORE_EVENTS_TRANSPORT": "amqp", "CORE_AUDIT_ENABLED": "true",
                "CORE_AUDIT_BASE_URL": "https://audit:8096", "AUDIT_QUERY_TOKEN": query_token,
                "SPRING_RABBITMQ_HOST": "broker", "SPRING_RABBITMQ_PORT": "5671", "SPRING_RABBITMQ_VIRTUAL_HOST": "streaming-p2",
                "SPRING_RABBITMQ_USERNAME": "core", "SPRING_RABBITMQ_PASSWORD": passwords["core"], "SPRING_RABBITMQ_DYNAMIC": "false", "SPRING_RABBITMQ_SSL_ENABLED": "true",
                "JAVA_TOOL_OPTIONS": "-Djavax.net.ssl.trustStore=/fixture/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit",
            }, mounts=(f"{shared}:/fixture:ro",), ports=(8081,))
            core_base = address("core", 8081)
            wait(lambda: call(core_base, "/actuator/health") is not None, "Core", 120)
            start("vod", images["vod"], {
                "VOD_DEVELOPMENT_HTTP": "true", "VOD_AUDIT_ENABLED": "true", "VOD_DATABASE_URL": f"postgres://fixture:{password}@vod-db/fixture?sslmode=disable",
                "VOD_SIGNING_SECRET": secrets.token_urlsafe(36), "VOD_CORE_URL": "http://core:8082", "VOD_CORE_TOKEN": vod_token,
                "VOD_S3_ENDPOINT": "http://unused.invalid:9000", "VOD_S3_BUCKET": "audit-fixture", "VOD_S3_ACCESS_KEY": "fixture", "VOD_S3_SECRET_KEY": password,
                "VOD_MEDIA_URL": "http://unused.invalid:8890", "MEDIA_VOD_DELIVERY_TOKEN": secrets.token_urlsafe(36), "VOD_PUBLIC_ORIGIN": "https://vault.example.test",
                "VOD_CAPTURE_TOKEN": capture_token, "VOD_CHAT_TOKEN": secrets.token_urlsafe(36), "VOD_GATEWAY_TOKEN": gateway_token,
                "VOD_BROKER_URL": broker("vod"), "VOD_BROKER_CA_FILE": "/fixture/ca.crt",
            }, mounts=(f"{shared}:/fixture:ro",), ports=(8098, 8099))
            vod_base, vod_private = address("vod", 8098), address("vod", 8099)
            wait(lambda: call(vod_base, "/healthz") is not None, "VOD")
            wait(lambda: call(audit_base, "/readyz") is not None, "Audit")
            # Public native-session contracts, real Core authentication and real VOD ownership checks.
            fixture_password = "Audit fixture password 2026!"
            users = {}
            for handle in ("audit_owner", "audit_other", "audit_admin"):
                account = call(core_base, "/api/identity/registrations", "POST", {"email": handle + "@example.test", "handle": handle, "password": fixture_password}, headers={"Idempotency-Key": str(uuid.uuid4())}, expected=201)
                session = call(core_base, "/api/identity/mobile-sessions", "POST", {"login": handle, "password": fixture_password})
                users[handle] = {**account, "token": session["accessToken"]}
            owner, other, admin = (users[key] for key in ("audit_owner", "audit_other", "audit_admin"))
            # Administrative provisioning is explicit; no public registration grants ADMIN.
            sql("core-db", "UPDATE identity.accounts SET account_role='ADMIN' WHERE handle='audit_admin'")
            category = call(core_base, "/api/taxonomy")["categories"][0]["id"]
            initial = {"sessionId": "session_audit_fixture", "streamId": "stream_audit_fixture", "channelId": owner["channelId"], "streamGeneration": 1,
                       "title": "Original fixture recording", "description": "", "categoryId": category, "tagIds": [], "autopublish": False, "preferenceVersion": 0}
            recording = call(vod_private, "/internal/vod/recordings/" + initial["sessionId"], "PUT", initial,
                             headers={"X-Service-Name": "media-capture", "X-Service-Token": capture_token, "Idempotency-Key": str(uuid.uuid4())})
            vod_id = recording["vodId"]
            print("Testing real ownership denial and protected investigation", flush=True)
            denied_ids = set()
            for i in range(5):
                supplied = "x" * 81 if i == 0 else ("req.denied_1" if i == 1 else "req_denied_" + str(i))
                rejection = call(vod_base, "/api/vods/" + vod_id, "PATCH", {"expectedVersion": recording["version"], "title": "FORGED"}, token=other["token"],
                     headers={"Idempotency-Key": str(uuid.uuid4()), "X-Request-Id": supplied, "X-Audit-Actor": "usr_forged"}, expected=403)
                denied_ids.add(rejection["requestId"])
            after = call(vod_base, "/api/me/vods/" + vod_id, token=owner["token"])
            assert after["title"] == recording["title"]
            call(core_base, "/api/admin/audit-events", expected=401)
            call(core_base, "/api/admin/audit-events", token=other["token"], expected=403)
            call(audit_base, "/internal/audit/events", expected=401)

            def events(**filters):
                return call(core_base, "/api/admin/audit-events?" + urllib.parse.urlencode(filters), token=admin["token"])

            observed = wait(lambda: (page if len((page := events(targetId=vod_id, outcome="DENIED"))["items"]) == 5 else None), "five denied events")
            assert all(entry["event"]["payload"]["actorId"] == other["userId"] for entry in observed["items"])
            assert {entry["event"]["correlationId"] for entry in observed["items"]} == denied_ids
            first = events(targetId=vod_id, limit=2)
            assert len(first["items"]) == 2 and first["nextCursor"]
            second = events(targetId=vod_id, limit=2, cursor=first["nextCursor"])
            assert {e["position"] for e in first["items"]}.isdisjoint(e["position"] for e in second["items"])
            call(core_base, "/api/admin/audit-events?limit=101", token=admin["token"], expected=400)
            call(core_base, "/api/admin/audit-events?limit=1&limit=2", token=admin["token"], expected=400)
            for action, method, suffix, body in (("VOD_PUBLISH", "POST", "/publication", {"expectedVersion": recording["version"]}), ("VOD_DELETE", "DELETE", "", None)):
                correlation = "req_" + action.lower()
                call(vod_base, "/api/vods/" + vod_id + suffix, method, body, token=other["token"],
                     headers={"Idempotency-Key": str(uuid.uuid4()), "X-Request-Id": correlation, "If-Match": str(recording["version"])}, expected=403)
                denied = wait(lambda: events(requestId=correlation)["items"], action + " denial")
                assert denied[0]["event"]["payload"]["action"] == action
            # Success, idempotent retry and request correlation.
            patch = {"expectedVersion": recording["version"], "title": "Owner update"}
            key = str(uuid.uuid4())
            headers = {"Idempotency-Key": key, "X-Request-Id": "req_success"}
            updated = call(vod_base, "/api/vods/" + vod_id, "PATCH", patch, token=owner["token"], headers=headers)
            start_time = time.monotonic()
            visible = wait(lambda: events(requestId="req_success")["items"], "successful event")
            delay_ms = (time.monotonic() - start_time) * 1000
            assert len(visible) == 1 and visible[0]["event"]["payload"]["outcome"] == "SUCCESS"
            call(vod_base, "/api/vods/" + vod_id, "PATCH", patch, token=owner["token"], headers=headers)
            assert len(events(requestId="req_success")["items"]) == 1
            # Rejecting outbox inserts must roll back the domain mutation.
            sql("vod-db", "CREATE FUNCTION reject_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.body::jsonb->>'eventType'='security.audit.vod.v1' THEN RAISE EXCEPTION 'fixture audit failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER reject_audit_fixture BEFORE INSERT ON vod_outbox FOR EACH ROW EXECUTE FUNCTION reject_audit_fixture();")
            call(vod_base, "/api/vods/" + vod_id, "PATCH", {"expectedVersion": updated["version"], "title": "ROLLBACK"}, token=owner["token"], headers={"Idempotency-Key": str(uuid.uuid4())}, expected=503)
            assert call(vod_base, "/api/me/vods/" + vod_id, token=owner["token"])["title"] == "Owner update"
            sql("vod-db", "DROP TRIGGER reject_audit_fixture ON vod_outbox; DROP FUNCTION reject_audit_fixture()")
            # Administrative success remains a single logical event across retries.
            command = {"status": "SUSPENDED", "expectedVersion": 0, "reason": "Fixture reason must not enter central evidence"}
            status_headers = {"Idempotency-Key": str(uuid.uuid4()), "X-Request-Id": "req_status"}
            path = "/api/admin/users/" + other["userId"] + "/status"
            sql("core-db", "CREATE FUNCTION core.reject_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture audit failure'; END $$; CREATE TRIGGER reject_audit_fixture BEFORE INSERT ON core.security_audit_outbox FOR EACH ROW EXECUTE FUNCTION core.reject_audit_fixture();")
            call(core_base, path, "PUT", command, token=admin["token"], headers=status_headers, expected=503)
            assert sql("core-db", "SELECT account_status FROM identity.accounts WHERE handle='audit_other'").stdout.strip() == "ACTIVE"
            sql("core-db", "DROP TRIGGER reject_audit_fixture ON core.security_audit_outbox; DROP FUNCTION core.reject_audit_fixture()")
            call(core_base, path, "PUT", command, token=admin["token"], headers=status_headers)
            call(core_base, path, "PUT", command, token=admin["token"], headers=status_headers)
            wait(lambda: events(requestId="req_status")["items"], "Core state evidence")
            assert len(events(requestId="req_status")["items"]) == 1
            # Denials have independent commits, including unknown unauthenticated actor.
            denied_admin = wait(lambda: events(action="ADMIN_ACCESS", outcome="DENIED")["items"], "admin denial evidence")
            assert any(e["event"]["payload"]["actorId"] is None for e in denied_admin)
            # Stop Logger; accepted source events remain in durable queues and appear after restart.
            print("Testing restart, broker outage, rollback and immutable runtime roles", flush=True)
            run("docker", "stop", "-t", "3", prefix + "-audit")
            call(core_base, "/api/admin/audit-events", token=admin["token"], expected=503)
            call(vod_base, "/api/vods/" + vod_id, "PATCH", {"expectedVersion": updated["version"], "title": "While Logger offline"}, token=owner["token"], headers={"Idempotency-Key": str(uuid.uuid4()), "X-Request-Id": "req_logger_offline"})
            run("docker", "start", prefix + "-audit")
            audit_base = address("audit", 8096).replace("http://", "https://")
            wait(lambda: events(requestId="req_logger_offline")["items"], "Logger restart recovery")
            run("docker", "stop", "-t", "3", prefix + "-broker")
            current = call(vod_base, "/api/me/vods/" + vod_id, token=owner["token"])
            call(vod_base, "/api/vods/" + vod_id, "PATCH", {"expectedVersion": current["version"], "title": "While broker offline"}, token=owner["token"], headers={"Idempotency-Key": str(uuid.uuid4()), "X-Request-Id": "req_broker_offline"})
            assert int(sql("vod-db", "SELECT count(*) FROM vod_outbox WHERE NOT sent AND body::jsonb->>'correlationId'='req_broker_offline'").stdout) == 1
            run("docker", "restart", prefix + "-vod")
            vod_base, vod_private = address("vod", 8098), address("vod", 8099)
            wait(lambda: call(vod_base, "/healthz") is not None, "VOD after restart")
            run("docker", "start", prefix + "-broker")
            recovered = wait(lambda: events(requestId="req_broker_offline")["items"], "broker and VOD restart recovery", 90)
            assert len(recovered) == 1
            # Replay one outbox entry with its original ID to exercise consumer idempotence.
            sql("vod-db", "UPDATE vod_outbox SET sent=false WHERE body::jsonb->>'correlationId'='req_success'")
            wait(lambda: sql("vod-db", "SELECT count(*) FROM vod_outbox WHERE NOT sent AND body::jsonb->>'correlationId'='req_success'").stdout.strip() == "0", "duplicate replay")
            assert len(events(requestId="req_success")["items"]) == 1
            # Reuse its identity with changed evidence: it must be quarantined, never overwritten.
            sql("vod-db", "UPDATE vod_outbox SET body=jsonb_set(body::jsonb,'{payload,reasonCode}','\"CONFLICT\"'::jsonb),sent=false WHERE body::jsonb->>'correlationId'='req_success'")
            wait(lambda: "audit.vod.dlq\t1" in run("docker", "exec", prefix + "-broker", "gosu", "rabbitmq", "rabbitmqctl", "-q", "-p", "streaming-p2", "list_queues", "name", "messages_ready").stdout, "conflict DLQ")
            assert events(requestId="req_success")["items"][0]["event"]["payload"]["reasonCode"] == "COMMITTED"
            for role, secret in (("audit_writer", writer), ("audit_reader", reader)):
                for statement in ("DELETE FROM audit.events", "UPDATE audit.events SET action='FORGED'", "TRUNCATE audit.events"):
                    assert sql("audit-db", statement, check=False, user=role, secret=secret).returncode != 0
            assert int(sql("audit-db", "SELECT count(*) FROM audit.reads").stdout) > 0
            deletable = {**initial, "sessionId": "session_delete_fixture", "streamId": "stream_delete_fixture"}
            to_delete = call(vod_private, "/internal/vod/recordings/" + deletable["sessionId"], "PUT", deletable,
                             headers={"X-Service-Name": "media-capture", "X-Service-Token": capture_token, "Idempotency-Key": str(uuid.uuid4())})
            call(vod_base, "/api/vods/" + to_delete["vodId"], "DELETE", token=owner["token"],
                 headers={"Idempotency-Key": str(uuid.uuid4()), "If-Match": str(to_delete["version"]), "X-Request-Id": "req_delete"}, expected=202)
            assert len(wait(lambda: events(requestId="req_delete")["items"], "owner deletion"))==1
            current = call(vod_base, "/api/me/vods/" + vod_id, token=owner["token"])
            removal = {"vodId": vod_id, "expectedVersion": current["version"], "reason": "Private fixture removal reason"}
            removed = call(vod_private, "/internal/vod/content-removals", "POST", removal,
                           headers={"X-Service-Name": "gateway", "X-Service-Token": gateway_token, "X-Session-Credential": admin["token"], "Idempotency-Key": str(uuid.uuid4()), "X-Request-Id": "req_remove"}, expected=202)
            removed_event = wait(lambda: events(requestId="req_remove")["items"], "administrative removal")
            assert len(removed_event)==1 and removed_event[0]["event"]["payload"]["actorId"]==admin["userId"]
            evidence = events(targetId=vod_id, limit=100)
            wire = json.dumps(evidence)
            for secret in (owner["token"], other["token"], admin["token"], fixture_password, query_token, capture_token, command["reason"], removal["reason"]):
                assert secret not in wire
            # Fresh role authority: suspending the auditor denies its next query.
            sql("core-db", "UPDATE identity.accounts SET account_status='SUSPENDED' WHERE handle='audit_admin'")
            call(core_base, "/api/admin/audit-events", token=admin["token"], expected=401)
            print(json.dumps({"result": "PASS", "vodDeniedEvents": 5, "nominalVisibilityMs": round(delay_ms),
                              "verified": ["real Core/VOD authorization", "admin-only investigation", "request correlation", "source rollback", "idempotency", "SQL privileges", "Logger/broker/VOD restart", "durable outbox", "consumer deduplication", "conflict DLQ", "no secrets", "auditor revocation"]}, ensure_ascii=False), flush=True)
        except Exception:
            # Save diagnostics in private temporary storage, never print unfiltered application logs.
            diagnostics=Path(tempfile.mkdtemp(prefix=prefix+"-failure-"))
            for name in names:
                logs=run("docker", "logs", name, check=False)
                (diagnostics / (name + ".log")).write_text(logs.stdout+logs.stderr)
            print("Private fixture diagnostics: " + str(diagnostics), flush=True)
            raise
        finally:
            for name in reversed(names):
                run("docker", "rm", "-f", "-v", name, check=False)
            run("docker", "network", "rm", prefix, check=False)


if __name__ == "__main__":
    main()
