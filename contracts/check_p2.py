#!/usr/bin/env python3
"""Validate the implemented P2 event contracts; generate their neutral schema offline."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError

HERE = Path(__file__).resolve().parent
FORBIDDEN = {"email", "password", "passwordHash", "sessionCredential", "providerToken", "streamKey",
             "ingestKey", "serviceToken", "presignedUrl", "recoveryToken", "token"}


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)


def objects(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)


def schema_bundle():
    base, p2 = read(HERE / "p1.json"), read(HERE / "p2.json")
    if p2["version"] != 2 or p2["extends"] != "p1.json":
        raise ValueError("Unsupported P2 source")
    return p2, {"$schema": "https://json-schema.org/draft/2020-12/schema",
                "$defs": {**base["schemas"], **p2["schemas"]}}


def validate(schema, name, value):
    Draft202012Validator({**schema, "$ref": "#/$defs/" + name}, format_checker=FormatChecker()).validate(value)
    for obj in objects(value):
        if FORBIDDEN.intersection(obj):
            raise ValueError("Private data in event")
    payload = value["payload"]
    lifecycle = value["eventType"] == "stream.session.changed.v1"
    key, version, prefix = ("sessionId", "sessionVersion", "session:") if lifecycle else ("streamId", "projectionVersion", "stream:")
    if value["aggregateId"] != prefix + payload[key] or value["sequence"] != payload[version]:
        raise ValueError("Aggregate/sequence do not match payload")
    if lifecycle:
        expected = {"PREPARING": "OFFLINE", "LIVE": "PLAYABLE", "RECONNECT_GRACE": "RECONNECTING", "ENDED": "OFFLINE"}
        if expected[payload["status"]] != payload["availability"]:
            raise ValueError("Inconsistent lifecycle state")
        if payload["status"] in ("LIVE", "RECONNECT_GRACE") and not payload["firstLiveAtUtc"]:
            raise ValueError("A previously playable session requires firstLiveAtUtc")


def verify(p2, schema):
    for name in p2["schemas"]:
        Draft202012Validator.check_schema(schema["$defs"][name])
    for obj in objects(schema["$defs"]):
        if "$ref" in obj and (not obj["$ref"].startswith("#/$defs/") or obj["$ref"][8:] not in schema["$defs"]):
            raise ValueError("Nonlocal/unresolved schema reference")
    types = [e["eventType"] for e in p2["events"]]
    if len(types) != 9 or len(set(types)) != 9 or sum(len(e["subscriptions"]) for e in p2["events"]) != 18:
        raise ValueError("The P2 registry must preserve nine types and eighteen subscriptions")
    subscriptions = [s for e in p2["events"] for s in e["subscriptions"]]
    if len(set(subscriptions)) != 18:
        raise ValueError("Subscriptions must remain independent")
    documented = {}
    text = (HERE.parent / "docs/contratos_p2_eventos.md").read_text(encoding="utf-8")
    for line in text.splitlines():
        columns = [column.strip() for column in line.split("|")]
        if len(columns) == 6 and columns[1].startswith(chr(96)) and columns[1].endswith(".v1" + chr(96)):
            documented[columns[1].strip(chr(96))] = re.findall(r"\x60([^\x60]+)\x60", columns[4])
    if documented != {event["eventType"]: event["subscriptions"] for event in p2["events"]}:
        raise ValueError("Event registry differs from the documented contracts")
    for event in p2["events"]:
        if event["schema"] is None:
            if event["status"] != "pending-owner":
                raise ValueError("Missing payload must remain explicitly pending")
            continue
        name = event["schema"]
        for example in p2["schemas"][name]["examples"]:
            if example["eventType"] != event["eventType"] or example["producer"] not in event["producers"]:
                raise ValueError("Producer/type differs from registry")
            validate(schema, name, example)
    for negative in p2["negativeExamples"]:
        try:
            validate(schema, negative["schema"], negative["value"])
        except (ValueError, ValidationError):
            continue
        raise ValueError("A negative example was accepted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--event", type=Path, help="Validate a real/synthetic envelope without publishing")
    args = parser.parse_args()
    p2, schema = schema_bundle()
    verify(p2, schema)
    output = HERE / "generated/p2.schema.json"
    encoded = json.dumps(schema, ensure_ascii=False, indent=2) + "\n"
    if args.write:
        output.write_text(encoded, encoding="utf-8")
    elif not output.exists() or output.read_text(encoding="utf-8") != encoded:
        raise SystemExit("Run python contracts/check_p2.py --write")
    if args.event:
        value = read(args.event)
        event = next((e for e in p2["events"] if e["eventType"] == value["eventType"]), None)
        if event is None or event["schema"] is None:
            raise SystemExit("The event payload is not ready")
        validate(schema, event["schema"], value)
    print("P2: two event schemas validated; nine types/eighteen subscriptions registered; remaining payloads pending")


if __name__ == "__main__":
    main()
