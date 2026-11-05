"""Deterministic RabbitMQ mapping of the neutral P2 event registry."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VHOST = "streaming-p2"
DELAYS = (1, 5, 30)


def routing_keys(event):
    kind = event["eventType"]
    if kind == "stream.session.changed.v1":
        return [kind + ".initial", kind + ".update"]
    if kind == "notification.delivery.requested.v1":
        return [kind + "." + medium for medium in ("inbox", "email", "push")]
    return [kind]


def password_hash(password):
    salt = os.urandom(4)
    return base64.b64encode(salt + hashlib.sha256(salt + password.encode()).digest()).decode()


def definitions(passwords):
    events = json.loads((ROOT / "contracts/p2.json").read_text(encoding="utf-8"))["events"]
    subscriptions = sorted({s for e in events for s in e["subscriptions"]})
    exchanges, queues, bindings = [], [], []

    def exchange(name, kind):
        exchanges.append(dict(name=name, vhost=VHOST, type=kind, durable=True, auto_delete=False, internal=False, arguments={}))

    def bind(queue, source, key):
        bindings.append(dict(source=source, vhost=VHOST, destination=queue, destination_type="queue", routing_key=key, arguments={}))

    def queue(name, arguments=None):
        args = {"x-queue-type": "quorum"}
        args.update(arguments or {})
        queues.append(dict(name=name, vhost=VHOST, durable=True, auto_delete=False, arguments=args))

    exchange("p2.events", "topic")
    exchange("p2.redelivery", "direct")
    for sub in subscriptions:
        exchange(sub + ".retry", "direct")
        exchange(sub + ".dead", "direct")
        queue(sub)
        queue(sub + ".dlq")
        bind(sub, "p2.redelivery", sub)
        bind(sub + ".dlq", sub + ".dead", "failed")
        for delay in DELAYS:
            queue(f"{sub}.retry.{delay}", {"x-message-ttl": delay * 1000, "x-dead-letter-exchange": "p2.redelivery",
                                         "x-dead-letter-routing-key": sub})
            bind(f"{sub}.retry.{delay}", sub + ".retry", str(delay))
    for event in events:
        for sub in event["subscriptions"]:
            keys = routing_keys(event)
            if sub == "notifications.live":
                keys = ["stream.session.changed.v1.initial"]
            elif sub.startswith("notifications.delivery."):
                keys = ["notification.delivery.requested.v1." + sub.rsplit(".", 1)[1]]
            for key in keys:
                bind(sub, "p2.events", key)

    permissions, topic_permissions = [], []
    for role in passwords:
        if role == "operator":
            permissions.append(dict(user=role, vhost=VHOST, configure=".*", write=".*", read=".*"))
            topic_permissions.append(dict(user=role, vhost=VHOST, exchange="p2.events", write=".*", read=".*"))
            continue
        owned = ["notifications.recovery.email"] if role == "recovery-email" else [s for s in subscriptions if s.split(".")[0] == role]
        if role == "notifications":
            owned = [s for s in owned if s != "notifications.recovery.email"]
        resources = "|".join(re.escape(s) + r"\.(retry|dead)" for s in owned)
        write = r"^(p2\.events" + ("|" + resources if resources else "") + ")$"
        read = "^(" + "|".join(re.escape(s) for s in owned) + ")$" if owned else "^$"
        permissions.append(dict(user=role, vhost=VHOST, configure="^$", write=write, read=read))
        keys = [k for e in events if role in e["producers"] for k in routing_keys(e)]
        topic_permissions.append(dict(user=role, vhost=VHOST, exchange="p2.events",
                                      write="^(" + "|".join(re.escape(k) for k in keys) + ")$" if keys else "^$", read=".*"))
    return {
        "vhosts": [{"name": VHOST}],
        "users": [dict(name=role, password_hash=password_hash(password), hashing_algorithm="rabbit_password_hashing_sha256",
                       tags=["administrator"] if role == "operator" else []) for role, password in passwords.items()],
        "permissions": permissions, "topic_permissions": topic_permissions,
        "exchanges": exchanges, "queues": queues, "bindings": bindings,
        "policies": [{
            "vhost": VHOST, "name": "p2-durable", "pattern": ".*", "apply-to": "quorum_queues", "priority": 10,
            "definition": {"overflow": "reject-publish", "max-length-bytes": 268435456,
                           "delivery-limit": -1, "dead-letter-strategy": "at-least-once"}
        }],
    }
