#!/usr/bin/env python3
"""Private operator: inspect or transfer a DLQ message without rewriting its envelope."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import sys
from datetime import datetime, timezone
from urllib.parse import quote

import pika

SUBSCRIPTION = re.compile(r"^(core|chat|streaming|vod|notifications)\.[a-z.]+$")


def audit(action, subscription, event_id, reason):
    path = Path("/audit/operations.jsonl")
    path.parent.mkdir(exist_ok=True, parents=True)
    record = dict(at=datetime.now(timezone.utc).isoformat(), actor="operator", action=action,
                  consumer=subscription, eventId=event_id, reason=reason)
    with path.open("a", encoding="utf-8") as target:
        target.write(json.dumps(record) + "\n")
        target.flush()
        os.fsync(target.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "redrive", "close", "metrics"))
    parser.add_argument("--subscription")
    parser.add_argument("--event-id")
    parser.add_argument("--reason", default="")
    args = parser.parse_args()
    context = ssl.create_default_context(cafile="/run/secrets/ca.crt")
    password = os.environ["BROKER_OPERATOR_PASSWORD"]
    if args.action == "metrics":
        import base64
        import urllib.request
        request = urllib.request.Request("https://broker:15671/api/queues/" + quote("streaming-p2", safe=""))
        request.add_header("Authorization", "Basic " + base64.b64encode(("operator:" + password).encode()).decode())
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
        with opener.open(request, timeout=5) as response:
            for queue in json.load(response):
                print(json.dumps({key: queue.get(key) for key in ("name", "messages", "messages_ready", "messages_unacknowledged", "head_message_timestamp")}))
        return
    if not args.subscription or not SUBSCRIPTION.fullmatch(args.subscription):
        raise ValueError("subscription required")
    if args.action != "inspect" and (not args.event_id or not args.reason.strip()):
        raise ValueError("event-id and reason required")
    connection = pika.BlockingConnection(pika.ConnectionParameters(
        host="broker", port=5671, virtual_host="streaming-p2", heartbeat=30,
        credentials=pika.PlainCredentials("operator", password),
        ssl_options=pika.SSLOptions(context, "broker"), socket_timeout=3, blocked_connection_timeout=5))
    try:
        channel = connection.channel()
        channel.confirm_delivery()
        method, properties, body = channel.basic_get(args.subscription + ".dlq", auto_ack=False)
        if method is None:
            print("DLQ empty")
            return
        try:
            event_id = json.loads(body).get("eventId")
        except (ValueError, AttributeError):
            event_id = properties.message_id
        if not isinstance(event_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", event_id):
            event_id = "invalid-" + hashlib.sha256(body).hexdigest()[:24]
        if args.action == "inspect":
            headers = properties.headers or {}
            print(json.dumps(dict(eventId=event_id, consumer=args.subscription,
                                 cause=headers.get("x-p2-cause"), attempts=headers.get("x-p2-attempt"),
                                 firstFailure=headers.get("x-p2-first-failure"), lastFailure=headers.get("x-p2-last-failure"))))
            channel.basic_nack(method.delivery_tag, requeue=True)
            return
        if args.event_id != event_id:
            raise ValueError("DLQ head differs; inspect first")
        if args.action == "redrive":
            # A new operational attempt retains the original event ID and exact body.
            headers = dict(properties.headers or {})
            headers["x-p2-attempt"] = 0
            headers["x-p2-redrive-at"] = datetime.now(timezone.utc).isoformat()
            properties.headers = headers
            properties.delivery_mode = 2
            channel.basic_publish("p2.redelivery", args.subscription, body, properties, mandatory=True)
        audit(args.action, args.subscription, event_id, args.reason)
        channel.basic_ack(method.delivery_tag)
        print(json.dumps(dict(action=args.action, consumer=args.subscription, eventId=event_id)))
    finally:
        # Any unacknowledged delivery returns to the DLQ, including unknown publish results.
        connection.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Broker operation failed; original message remains recoverable. Inspect configuration/DLQ locally.", file=sys.stderr)
        sys.exit(1)
