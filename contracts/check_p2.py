#!/usr/bin/env python3
"""Validate and generate P2 additions over P1, entirely offline."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError, ValidationError

import generate

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "contracts/p2.json"
OUTPUT = ROOT / "contracts/generated/p2"
PRIVATE = generate.FORBIDDEN | {"recoveryToken", "newPassword", "recipient", "resetUrl", "encryptedMaterial",
                                "providerToken", "serviceToken", "presignedUrl", "token"}


def load(source=SOURCE):
    base, sdl = generate.load()
    additions = generate.read_json(source.read_text(encoding="utf-8"))
    if additions.get("version") != 2 or additions.get("extends") != "p1.json":
        raise ValueError("Unsupported P2 contract source")
    if set(base["schemas"]) & set(additions["schemas"]):
        raise ValueError("P2 cannot silently overwrite a P1 schema")
    merged = {**base, "schemas": {**base["schemas"], **additions["schemas"]}}
    return base, additions, merged, sdl


def validate_payload(bundle, name, value, public=False, event=False):
    generate.validator(bundle, name).validate(value)
    validate_subtitle_identity(name, value)
    event = event or name.startswith("P2Event")
    if public or event:
        for node in generate.walk(value):
            if PRIVATE.intersection(node):
                raise ValueError("Private field in a public response or broker message")
    if event:
        validate_event_identity(value)


def validate_subtitle_identity(name, value):
    if name == "P2SubtitleTrack":
        expected = f"/vod-media/{value['vodId']}/subtitles/{value['trackId']}.vtt"
        if value["contentPath"] != expected:
            raise ValueError("Subtitle path does not match its VOD and track")
    elif name == "P2SubtitlePage":
        ids = [track["trackId"] for track in value["items"]]
        if len(ids) != len(set(ids)) or len({track["vodId"] for track in value["items"]}) > 1:
            raise ValueError("Subtitle page mixes VODs or duplicate tracks")
        for track in value["items"]:
            validate_subtitle_identity("P2SubtitleTrack", track)


def validate_event_identity(value):
    payload = value["payload"]
    kind = value["eventType"]
    if kind == "credential.recovery.delivery.requested.v1":
        if value["aggregateId"] != "recovery:" + payload["recoveryId"] or value["sequence"] != 1:
            raise ValueError("Recovery aggregate/sequence do not match payload")
        return
    if kind == "notification.delivery.requested.v1":
        if value["aggregateId"] != "delivery:" + payload["deliveryId"] or value["sequence"] != 1:
            raise ValueError("Delivery aggregate/sequence do not match payload")
        return
    if kind == "channel.follow.changed.v1":
        if (value["aggregateId"] != f"follow:{payload['channelId']}:{payload['followerUserId']}"
                or value["sequence"] != payload["followVersion"]):
            raise ValueError("Follow aggregate/sequence do not match payload")
        return
    if kind == "account.status.changed.v1":
        if value["aggregateId"] != "account:" + payload["userId"] or value["sequence"] != payload["accountVersion"]:
            raise ValueError("Account aggregate/sequence do not match payload")
        return
    lifecycle = kind == "stream.session.changed.v1"
    key, version, prefix = ("sessionId", "sessionVersion", "session:") if lifecycle else ("streamId", "projectionVersion", "stream:")
    if value["aggregateId"] != prefix + payload[key] or value["sequence"] != payload[version]:
        raise ValueError("Aggregate/sequence do not match payload")
    if lifecycle:
        expected = {"PREPARING": "OFFLINE", "LIVE": "PLAYABLE", "RECONNECT_GRACE": "RECONNECTING", "ENDED": "OFFLINE"}
        if expected[payload["status"]] != payload["availability"]:
            raise ValueError("Inconsistent lifecycle state")
        if payload["status"] in ("LIVE", "RECONNECT_GRACE") and not payload["firstLiveAtUtc"]:
            raise ValueError("A previously playable session requires firstLiveAtUtc")


def schema_bundle():
    _, additions, merged, _ = load()
    return additions, {"$schema": "https://json-schema.org/draft/2020-12/schema", "$defs": merged["schemas"]}


def validate(schema, name, value):
    # Keep the compatibility entry point identical to CLI/example validation.
    validate_payload({"schemas": schema["$defs"]}, name, value)


def verify_events(additions, merged):
    events = additions["events"]
    types = [event["eventType"] for event in events]
    subscriptions = [subscription for event in events for subscription in event["subscriptions"]]
    if len(types) != 9 or len(set(types)) != 9 or len(subscriptions) != 18 or len(set(subscriptions)) != 18:
        raise ValueError("The P2 registry must preserve nine types and eighteen independent subscriptions")
    documented = {}
    for line in (ROOT / "docs/contratos_p2_eventos.md").read_text().splitlines():
        columns = [column.strip() for column in line.split("|")]
        if len(columns) == 6 and columns[1].startswith(chr(96)) and columns[1].endswith(".v1" + chr(96)):
            documented[columns[1].strip(chr(96))] = re.findall(r"\x60([^\x60]+)\x60", columns[4])
    if documented != {event["eventType"]: event["subscriptions"] for event in events}:
        raise ValueError("Event registry differs from the documented contracts")
    for event in events:
        name = event["schema"]
        if name is None:
            if event["status"] != "pending-owner":
                raise ValueError("Missing payload must remain explicitly pending")
            continue
        if name not in additions["schemas"] or not additions["schemas"][name].get("examples"):
            raise ValueError("A ready event requires a schema and examples")
        for example in additions["schemas"][name]["examples"]:
            validate_payload(merged, name, example, event=True)
            if example["eventType"] != event["eventType"] or example["producer"] not in event["producers"]:
                raise ValueError("P2 event producer/type mismatch")


def verify(base, additions, merged, sdl):
    generate.verify(base, sdl)
    for name, schema in additions["schemas"].items():
        Draft202012Validator.check_schema(schema)
        for node in generate.walk(schema):
            if "$id" in node or "$dynamicRef" in node:
                raise ValueError("P2 schemas must use local static references")
            ref = node.get("$ref")
            if ref is not None and (not ref.startswith("#/$defs/") or ref[8:] not in merged["schemas"]):
                raise ValueError("Unresolved P2 reference")
        for value in schema.get("examples", []):
            validate_payload(merged, name, value)

    seen = {op["id"] for op in base["operations"]}
    routes = {(op["provider"], op["method"], op["path"]) for op in base["operations"]}
    required = {"id", "method", "path", "provider", "consumer", "owner", "auth", "request", "responses",
                "timeoutMs", "idempotency", "recovery", "spec", "traceability", "visibility", "limits", "requiredHeaders"}
    for op in additions["operations"]:
        if required - op.keys() or not all(op[key] for key in required - {"request", "responses", "requiredHeaders"}):
            raise ValueError("Incomplete P2 operation")
        identity = (op["provider"], op["method"], op["path"])
        if op["id"] in seen or identity in routes:
            raise ValueError("Duplicate P2 operation")
        seen.add(op["id"])
        routes.add(identity)
        if not (ROOT / op["traceability"]).is_file() or op["timeoutMs"] <= 0:
            raise ValueError("Missing P2 traceability or timeout")
        # contratos_p2_http.md: POST context/recipient queries are reads, not commands.
        is_read = op.get("semantics") == "read" and op["method"] == "POST"
        if op["method"] in {"POST", "PUT", "PATCH", "DELETE"} and not is_read and "Idempotency-Key" not in op["requiredHeaders"]:
            raise ValueError("P2 mutation lacks idempotency")
        for name in [op.get("query"), *(part.get("schema") for part in op.get("parts", {}).values())]:
            if name is not None:
                generate.validator(merged, name)
        if op["request"] is not None:
            generate.validator(merged, op["request"])
        if not op["responses"]:
            raise ValueError("P2 operation has no responses")
        for status, name in op["responses"].items():
            if not status.isdigit():
                raise ValueError("Invalid P2 HTTP status")
            if name is not None:
                generate.validator(merged, name)
                for value in merged["schemas"][name]["examples"]:
                    validate_payload(merged, name, value, public=op["visibility"] == "public")

    verify_events(additions, merged)

    for negative in additions["negativeExamples"]:
        try:
            validate_payload(merged, negative["schema"], negative["value"], public=negative.get("public", False))
        except (ValueError, ValidationError):
            continue
        raise ValueError("A P2 negative example unexpectedly validates")
    if set(additions["retiredP1Operations"]) - {op["id"] for op in base["operations"]}:
        raise ValueError("Unknown retired P1 operation")
    for pending in additions["pendingInterfaces"]:
        if not pending["blockedBy"] or not (ROOT / pending["traceability"]).is_file():
            raise ValueError("Missing pending-interface decision or traceability")


def artifacts(additions, merged, sdl):
    encode = lambda value: generate.encode_json(value) + "\n"
    files = {
        "schema.json": encode({"$schema": "https://json-schema.org/draft/2020-12/schema", "$defs": merged["schemas"]}),
        "operations.json": encode(additions["operations"]),
        "events.json": encode(additions["events"]),
        "index.d.ts": generate.typescript(merged, sdl).replace("contracts/p1.json and", "contracts/p2.json, inherited P1 schemas and", 1),
    }
    files["manifest.json"] = encode({"source": "contracts/p2.json", "base": "contracts/p1.json", "version": 2,
        "sha256": {name: hashlib.sha256(value.encode()).hexdigest() for name, value in files.items()}})
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--payload", nargs=2, metavar=("SCHEMA", "JSON_FILE"))
    parser.add_argument("--event", type=Path, help="Validate a broker envelope without publishing")
    parser.add_argument("--public", action="store_true")
    args = parser.parse_args()
    base, additions, merged, sdl = load()
    verify(base, additions, merged, sdl)
    if args.payload:
        name, path = args.payload
        validate_payload(merged, name, generate.read_json(Path(path).read_text()), public=args.public)
    if args.event:
        value = generate.read_json(args.event.read_text())
        entry = next((event for event in additions["events"] if event["eventType"] == value["eventType"]), None)
        if entry is None or entry["schema"] is None:
            raise ValueError("The event payload is not ready")
        validate_payload(merged, entry["schema"], value, event=True)
    files = artifacts(additions, merged, sdl)
    root_schema = ROOT / "contracts/generated/p2.schema.json"
    if args.write:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        for name, value in files.items():
            (OUTPUT / name).write_text(value, encoding="utf-8", newline="\n")
        root_schema.write_text(files["schema.json"], encoding="utf-8", newline="\n")
    else:
        if not root_schema.is_file() or root_schema.read_text() != files["schema.json"]:
            raise ValueError("Generated P2 event schema drift; run contracts/check_p2.py --write")
        for name, value in files.items():
            if not (OUTPUT / name).is_file() or (OUTPUT / name).read_text(encoding="utf-8") != value:
                raise ValueError("Generated P2 contract drift; run contracts/check_p2.py --write")
        if {path.name for path in OUTPUT.iterdir() if path.is_file()} - files.keys():
            raise ValueError("Unexpected P2 generated file")
    print(f"PASS: P2 ({len(additions['schemas'])} schemas, {len(additions['operations'])} operations, {len(additions['events'])} events)")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, SchemaError, ValidationError) as error:
        # Never print an exception or validator instance that might contain credentials.
        print(f"P2 contract validation failed ({type(error).__name__}).", file=sys.stderr)
        sys.exit(1)
